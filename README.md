# SuretySeven — Bond Underwriting Workflow

A production-minded, **event-driven** implementation of the take-home assignment:
accept a bond application, validate it, pull additional applicant data from an
external API, score it, decide, expose status through an API, and notify a
downstream system — all as Kafka events rather than in-process calls — with
real per-broker OAuth2 authentication and authorization.

**Stack:** Spring Boot 3 / Java 17, Apache Kafka, embedded Spring Authorization
Server, PostgreSQL, React (Vite) frontend.
See [`docs/architecture.md`](docs/architecture.md) for the full design writeup
and diagram, and [`AI_USAGE.md`](AI_USAGE.md) for how AI was used to build this.

## 1. Problem interpretation

The assignment allows a synchronous REST implementation as a perfectly good
solution — this goes a step further, deliberately: every stage of the workflow
(submitted → evaluated → decisioned → notified) is a **Kafka event**, not an
in-process call, and the transactional outbox pattern guarantees a database
commit and a Kafka publish are never observably out of sync. Crash recovery and
retry-after-failure are Kafka's job (consumer redelivery + a dead-letter topic)
rather than a hand-rolled DB-polling recovery job. Authentication is real
OAuth2 client-credentials, one client per broker, with row-level authorization
so a broker can only ever see their own applications. The trade-offs of this
heavier design vs. a simpler synchronous one are spelled out in
[`docs/architecture.md §8`](docs/architecture.md#8-key-trade-offs) — this is
not "the only correct architecture," it's a deliberate, documented choice.

## 2. Project layout

```
backend/    Spring Boot service — API, OAuth2 auth server + resource server,
            Kafka producers/consumers, mock external systems, tests
frontend/   React (Vite) SPA — connect as a broker, submit an application,
            watch its status resolve live
docs/       architecture.md + architecture.png
docker-compose.yml   one-command local run: Postgres + Kafka + backend + frontend
```

## 3. Running it

### Option A — one command (recommended)

```bash
docker compose up --build
```

Brings up Postgres, a single-node Kafka broker (KRaft mode, no Zookeeper), the
backend, and the frontend — no external accounts needed for local dev.

- Frontend: http://localhost:5173
- Backend: http://localhost:8080
- Postgres: localhost:5432
- Kafka: localhost:9094 (host-mapped; `kafka:9092` inside the compose network)

### Option B — run backend and frontend separately (faster edit loop)

You'll still need *some* Kafka broker reachable at `localhost:9094` — easiest
is to start just that one container:

```bash
docker compose up kafka
```

Then, backend (needs JDK 17 + Maven; uses in-memory H2, no Postgres needed):

```bash
cd backend
mvn spring-boot:run
```

Frontend (needs Node 20+):

```bash
cd frontend
cp .env.example .env
npm install
npm run dev
```

There's no API key anymore — the frontend authenticates via the real OAuth2
flow on its "Connect as a Broker" screen (see §6 for demo credentials).

## 4. Running tests

```bash
cd backend
mvn test
```

Covers:
- `ScoringServiceTest` — pins down every row of the scoring table (§5).
- `EvaluationServiceTest` — success/failure branches, asserting failures
  **propagate** (so Kafka's error handler can retry/dead-letter them).
- `ApplicationServiceTest` — idempotency (including the concurrent-duplicate
  race), broker-id stamping, and outbox-event enqueuing.
- `ApplicationApiIntegrationTest` — full HTTP + **real embedded Kafka**
  round trip: happy path through to a delivered downstream notification,
  permanent external failure through Kafka retry/backoff to a dead-lettered
  `FAILED` status, real OAuth2 token acquisition and rejection of bad
  credentials, idempotent replay, validation errors, and — importantly —
  that one broker can never see another broker's application (by ID or in
  the list endpoint).

## 5. Scoring model

| Condition | Points |
|---|---|
| Credit score ≥ 750 | +30 |
| Credit score 700–749 | +20 |
| Credit score < 700 | +5 |
| Years in business ≥ 5 | +20 |
| Years in business < 5 | +10 |
| Bond amount ≤ 10% of annual revenue | +30 |
| Bond amount > 10% of annual revenue | +10 |
| Existing exposure < 20% of annual revenue | +20 |
| Existing exposure ≥ 20% of annual revenue | +5 |

Decision: **≥ 80 → APPROVE**, **50–79 → REFER**, **< 50 → DECLINE**
(configurable in `application.yml` under `underwriting.thresholds`). Each rule
is its own Spring bean implementing `ScoringRule` — unchanged from the
simpler design; scoring logic is independent of how evaluation gets triggered.

## 6. Authentication

Every broker is a real OAuth2 client. Get a token, then use it as a bearer
token on every `/applications` call.

Two demo brokers are seeded (`db/migration/V2__oauth2_brokers.sql`) so you can
also verify tenant isolation yourself:

| Client ID | Client Secret |
|---|---|
| `demo-broker-1` | `demo-secret-1` |
| `demo-broker-2` | `demo-secret-2` |

```bash
curl -X POST https://suretyseven-87oi.onrender.com/oauth2/token \
  -u demo-broker-1:demo-secret-1 \
  -d "grant_type=client_credentials&scope=applications.read applications.write"
```

Response:

```json
{
  "access_token": "eyJraWQiOiJ...",
  "token_type": "Bearer",
  "expires_in": 899,
  "scope": "applications.read applications.write"
}
```

Tokens are short-lived (15 minutes) on purpose — see
[`docs/architecture.md §7`](docs/architecture.md#7-authentication--authorization).

**Important caveat**: client-credentials is a machine-to-machine grant. The
frontend's "Connect as a Broker" screen — where you type a client secret into
a browser form — is a **demo convenience only**, clearly labeled as such in
the UI itself. A real broker integration calls this API server-to-server and
never exposes a secret client-side.

## 7. API

### `POST /applications`
Requires `applications.write` scope.

```bash
curl -X POST https://suretyseven-87oi.onrender.com/applications \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 3f29b1c2-1a34-4e2e-9c1a-1a2b3c4d5e6f" \
  -d '{
    "applicantId": "COMP-123",
    "bondType": "CONTRACT",
    "bondAmount": 500000,
    "effectiveDate": "2026-10-01",
    "obligee": { "name": "ABC Construction LLC" }
  }'
```

`bondType` must be one of `CONTRACT`, `COMMERCIAL`, `COURT`, `LICENSE_AND_PERMIT`.
Returns `201 Created` (or `200 OK` on a retried `Idempotency-Key`) with status
`SUBMITTED` — evaluation happens asynchronously via Kafka; poll the next
endpoint for the outcome.

### `GET /applications/{applicationId}`
Requires `applications.read` scope. Returns `404` if the ID doesn't exist
**or** belongs to a different broker than the caller.

```bash
curl https://suretyseven-87oi.onrender.com/applications/APP-7F3A2C1B9D -H "Authorization: Bearer $TOKEN"
```

Once evaluated:

```json
{
  "applicationId": "APP-7F3A2C1B9D",
  "status": "APPROVED",
  "score": 100,
  "decision": "APPROVE",
  "applicantId": "COMP-123",
  "bondType": "CONTRACT",
  "bondAmount": 500000,
  "effectiveDate": "2026-10-01",
  "obligeeName": "ABC Construction LLC",
  "scoreBreakdown": [
    { "factor": "CREDIT_SCORE", "detail": "760 >= 750", "points": 30 },
    { "factor": "YEARS_IN_BUSINESS", "detail": "8 >= 5", "points": 20 },
    { "factor": "BOND_TO_REVENUE", "detail": "bondAmount is 4.2% of annualRevenue (<= 10%)", "points": 30 },
    { "factor": "EXISTING_EXPOSURE", "detail": "existingExposure is 12.5% of annualRevenue (< 20%)", "points": 20 }
  ],
  "createdAt": "2026-09-18T10:15:00Z",
  "updatedAt": "2026-09-18T10:15:03Z"
}
```

### `GET /applications`
Paginated, newest-first, scoped to the caller's own applications only.

```bash
curl "https://suretyseven-87oi.onrender.com/applications?page=0&size=10" -H "Authorization: Bearer $TOKEN"
```

Query params: `page` (0-indexed, default 0), `size` (default 20), `sort`
(default `createdAt,desc`).

### Trying the failure paths

The mock Applicant API reads a prefix on the `applicantId` to simulate a
scenario:

| Prefix | Behavior |
|---|---|
| *(none)* | Deterministic success |
| `SLOW-` | ~3s delay, then succeeds |
| `ERROR-` | HTTP 500 every time |
| `TIMEOUT-` | Hangs past any client timeout |
| `MALFORMED-` | 200 with an unparsable body |

e.g. `applicantId: "ERROR-COMP-1"` will exhaust Resilience4j's retries, get
marked `NEEDS_ATTENTION`, get retried a couple more times by Kafka's error
handler, and eventually land on `FAILED` once dead-lettered.

## 8. Architectural decisions

Summarized here; full reasoning and diagram in
[`docs/architecture.md`](docs/architecture.md):

- Kafka events (not in-process async) drive every stage transition; crash
  recovery and retry-after-failure are Kafka's job via consumer redelivery,
  a `DefaultErrorHandler` with backoff, and dead-letter topics.
- Transactional outbox for BOTH "start evaluation" and "notify downstream" —
  a DB write and its corresponding Kafka publish always commit together.
- Real OAuth2 client-credentials, one client per broker, via an embedded
  Spring Authorization Server sharing its signing key directly with the
  resource server (no HTTP round-trip to validate a token).
- Two-layer authorization: coarse OAuth2 scopes + fine-grained row-level
  ownership checks in the service layer.
- Idempotency via a unique `idempotency_key` constraint.
- Pluggable scoring rules and configurable thresholds (unchanged from a
  simpler design — orthogonal to how evaluation gets triggered).
- Flyway-managed schema.
- Correlation IDs threaded through every log line.

## 9. Known limitations

- Client-credentials-in-a-browser is a demo pattern, not production auth for
  a real SPA — see §6.
- Broker onboarding is Flyway-seed-only; no admin API.
- The Authorization Server's signing key is generated fresh at startup, not
  persisted — a restart invalidates outstanding (15-min) tokens.
- No rate limiting on the public endpoints.
- The applications list has no filtering/search yet, just pagination/sort.
- Kafka consumers (`ApplicationSubmittedListener`, `DownstreamNotificationListener`)
  run in-process alongside the API rather than as separately-scaled workers.
- The mock Applicant API's failure simulations are deliberately crude.

## 10. What I'd do next with another 2 weeks

- A real broker onboarding admin API/console.
- Persist the Authorization Server's RSA key (DB or secrets manager).
- Move Kafka consumers to their own horizontally-scaled deployment.
- Filtering/search on `GET /applications`.
- A metrics dashboard (Micrometer): outbox dead-letter rate, Kafka consumer
  lag, external API latency/error rate.
- An admin view over `FAILED` applications and dead-lettered events.

## 11. Deployment on free-tier infrastructure

| Piece | Where | Why |
|---|---|---|
| Database | **[Neon](https://neon.tech)** | Permanent free tier, no time-boxed expiry, scales to zero when idle |
| Kafka | **[Aiven](https://aiven.io)** free Apache Kafka | A real, no-credit-card-required free tier (launched with no time limit, though the service idles when unused and auto-wakes on the next connection; fixed throughput ceiling, no SLA — fine for a demo) |
| Backend | **[Render](https://render.com)** Web Service (Docker) | Deploys from `backend/Dockerfile`; free tier sleeps after ~15 min idle |
| Frontend | **[Netlify](https://netlify.com)** | Static hosting for the Vite build |

### 11.1 Database (Neon)
Same as before: create a free project, copy the connection string, convert to
a `jdbc:postgresql://...` URL. Flyway runs both migrations automatically on
first boot.

### 11.2 Kafka (Aiven)
1. Create a free Aiven account, start an Apache Kafka service (no credit card
   required for the free plan).
2. From the service's console, download the **Access Key**, **Access
   Certificate**, and **CA Certificate** (Aiven's default mutual-TLS bundle).
3. Note the service's bootstrap server URI (e.g. `kafka-xxxx.aivencloud.com:12345`).
4. Some managed Kafka (Aiven's free tier included) disables auto-topic-creation
   — this app creates its own topics via `KafkaConfig`'s `NewTopic` beans at
   startup, which works as long as the app's credentials have topic-create
   permission (true by default for the account owner's own certificate). If
   topic creation fails, create these four topics manually via the Aiven
   console: `applications.submitted`, `applications.submitted.DLT`,
   `applications.decisioned`, `applications.decisioned.DLT`.

### 11.3 Backend (Render)
New **Web Service** → this repo, root directory `backend`, environment
**Docker**. Environment variables:

| Key | Value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | from Neon |
| `KAFKA_BOOTSTRAP_SERVERS` | Aiven's bootstrap URI |
| `KAFKA_CA_CERT` | contents of Aiven's `ca.pem` |
| `KAFKA_ACCESS_CERT` | contents of Aiven's `service.cert` |
| `KAFKA_ACCESS_KEY` | contents of Aiven's `service.key` |
| `OAUTH2_ISSUER` | your Render URL, e.g. `https://your-service.onrender.com` |
| `ALLOWED_ORIGINS` | your Netlify URL |
| `APPLICANT_API_BASE_URL` | your own Render URL (the mock lives in this same service) |

`KAFKA_CA_CERT`/`KAFKA_ACCESS_CERT`/`KAFKA_ACCESS_KEY` are the **raw PEM file
contents** (multi-line env vars — Render supports this), consumed directly via
Kafka's inline-PEM SSL config (`ssl.truststore.type: PEM`, etc. — see
`application-prod.yml`), so there's no keystore-conversion step needed.

### 11.4 Frontend (Netlify)
Unchanged from before: base directory `frontend`, build command `npm run
build`, publish directory `dist`, one env var `VITE_API_BASE_URL` pointing at
your Render backend. No API-key env var anymore — brokers authenticate at
runtime via the Connect screen.

Live Link : https://suretyseven-shubhankar.netlify.app
Backend Link : https://suretyseven-87oi.onrender.com
