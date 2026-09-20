# AI Usage

This project was built with heavy AI assistance (Claude), which the assignment
explicitly permits and asks to be documented honestly rather than hidden. This
file describes what was actually asked for, what Claude produced, and —
importantly — what still needs independent verification before treating this
as finished work, since it hasn't been compiled or run in the environment it
was written in.

## What was asked of AI

The initial prompt: read the assignment PDF, then design and build a full
implementation (Spring Boot backend, React frontend), deployable on free-tier
hosting. A follow-up prompt explicitly asked for two significant upgrades over
the first version: replacing in-process `@Async` fire-and-forget with a real
Kafka event-driven architecture, and replacing a placeholder API-key auth
scheme with real OAuth2 client-credentials authentication, one client per
broker, with row-level authorization so a broker can only see their own
applications — and to confirm both were achievable on free infrastructure
before building them.

## What Claude generated

The full first draft, both times: the original synchronous/in-process design,
and then the substantial rework to Kafka + OAuth2 covering: the Kafka
producer/consumer wiring (`KafkaConfig`, topic declarations, dead-letter
handling), all four `@KafkaListener` classes, the rewritten
`EvaluationService`/`EvaluationPersistence` (failures now propagate instead of
being swallowed, so Kafka's error handler can act on them), the embedded
Spring Authorization Server setup (`AuthorizationServerConfig`,
`BrokerRegisteredClientRepository`, `ResourceServerConfig`), the `brokers`
table and its Flyway migration, broker-scoped repository/service/controller
changes, the rewritten test suite (including `@EmbeddedKafka`-based
integration tests and real OAuth2 token acquisition in tests), the
docker-compose Kafka container, the Aiven/Neon/Render/Netlify deployment
docs, and the frontend's OAuth2 "Connect as a Broker" screen.

## Design decisions worth calling out as AI's, not the assignment's or the user's

- **Kafka retry/backoff at the consumer level (2×, 3s), layered on top of
  Resilience4j's own retry inside the external API client.** The user asked
  for "Kafka event" without specifying retry semantics; the two-layer retry
  (fine-grained inside one evaluation attempt, coarser across whole
  evaluation attempts) was Claude's design choice.
- **Extending the outbox pattern to the "start evaluation" step, not just the
  "notify downstream" step.** The user's request was about replacing
  fire-and-forget with Kafka, not specifically about the outbox; extending
  the outbox to the submit path was Claude's call, made because publishing
  to Kafka directly inside the create-application transaction would
  reintroduce a dual-write problem.
- **A separate `.DLT` topic per topic, with a dedicated dead-letter listener
  for `applications.submitted.DLT` that marks applications FAILED.** This
  replaces what was previously a DB-polling recovery job; that the DLT
  listener is the sole place that transitions to FAILED is a design decision,
  not something spelled out in the request.
- **Embedding Spring Authorization Server in the same process as the
  resource server**, sharing the JWKSource bean directly, rather than running
  it as a separate service or using a third-party IdP (Auth0/Keycloak/etc).
  Chosen specifically to avoid needing to host and pay for a second service,
  and to sidestep an HTTP-based issuer-uri discovery startup-order problem.
- **The frontend's "Connect as a Broker" screen** (typing a client secret
  into a browser) as a demo-only stand-in for a real machine-to-machine
  caller. This is explicitly flagged as NOT a legitimate production pattern
  in three places (the UI itself, the README, and architecture.md) —
  Claude's own judgment call about how to keep the demo usable without
  quietly implying this is how a real integration should work.

## What has NOT been verified and should be, before relying on this

This was generated and written to disk without network access to actually run
`mvn test`, `docker compose up`, or deploy to Aiven/Neon/Render — the sandbox
it was built in has no outbound network access. Beyond the general Spring
Boot/React caveats from the original build, the Kafka + OAuth2 rework
specifically introduces several pieces that are standard, documented patterns
but were never compiled or executed here:

- **`docker-compose.yml`'s Kafka service** (`apache/kafka:3.8.0`, KRaft mode,
  single node) uses environment variable names recalled from documentation,
  not verified against that exact image tag. If it fails to start, check
  Apache's official Kafka Docker image docs for the current KRaft
  single-node env var names for that version.
- **`@EmbeddedKafka` + `${spring.embedded.kafka.brokers}`** in
  `ApplicationApiIntegrationTest` is a standard `spring-kafka-test` pattern,
  but the exact property name and its interaction with `@TestPropertySource`
  placeholder resolution timing was not exercised by an actual test run here.
- **Spring Authorization Server's default `PasswordEncoder` resolution** for
  verifying a broker's `client_secret` — an explicit `PasswordEncoder` bean
  was added (`PasswordEncoderFactories.createDelegatingPasswordEncoder()`) to
  make this unambiguous rather than relying on an internal default, but this
  was reasoned from documentation, not confirmed by running the token
  endpoint.
- **Aiven's inline-PEM Kafka SSL properties**
  (`ssl.truststore.type: PEM`, `ssl.truststore.certificates`, etc. in
  `application-prod.yml`) rely on a Kafka-clients feature (KIP-651) that
  should be present in the Kafka client version Spring Boot 3.3's `spring-kafka`
  bundles, but this exact property combination against a real Aiven service
  was not tested end-to-end. If it doesn't work, Aiven's own downloaded
  client configuration snippet (in their console, per-service) is the
  fallback source of truth.
- **The claim that Aiven's Kafka free tier allows this app's own credentials
  to create topics at startup** (`KafkaConfig`'s `NewTopic` beans) is an
  assumption; the README's deployment section names the manual
  topic-creation fallback for exactly this reason.
- **Whether `@EnableKafka` + Spring Boot's Kafka autoconfiguration correctly
  auto-detects the single `DefaultErrorHandler` bean** in `KafkaConfig` and
  applies it to the default listener container factory — this is documented
  Spring Boot behavior, but, again, not exercised by an actual run here.

## What a submitter should personally verify/own before turning this in

Run it locally end to end (`docker compose up --build`), obtain a real token
via `POST /oauth2/token`, submit an application, and watch it move through
`SUBMITTED` → `IN_REVIEW` → a decision by polling the API or the frontend —
confirming the Kafka round trip actually works, not just that it compiles.
Deliberately break something (use an `ERROR-` prefixed applicant ID) and watch
it land on `FAILED` via the dead-letter path. Confirm broker isolation
yourself by creating an application as `demo-broker-1` and trying (and
failing) to fetch it as `demo-broker-2`. Read `EvaluationService`,
`KafkaOutboxPublisher`, and `AuthorizationServerConfig` closely enough to
explain the retry/backoff/dead-letter chain and the token-issuance flow in
your own words in an interview — this rework specifically was built to
demonstrate patterns (event-driven architecture, OAuth2) that are exactly what
an interviewer is likely to probe on in follow-up questions.
