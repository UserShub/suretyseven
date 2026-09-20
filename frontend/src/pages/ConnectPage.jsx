import { useState } from 'react'
import { fetchAccessToken, ApiError } from '../api/client'

export default function ConnectPage({ onConnected }) {
  const [clientId, setClientId] = useState('')
  const [clientSecret, setClientSecret] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState(null)

  async function handleSubmit(e) {
    e.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      await fetchAccessToken(clientId.trim(), clientSecret)
      onConnected(clientId.trim())
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setError('Invalid client ID or client secret.')
      } else {
        setError('Could not reach the underwriting service. Is the backend running?')
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="card">
      <h1>Connect as a Broker</h1>
      <p className="muted">
        This API uses real OAuth2 client-credentials authentication — every broker is
        its own OAuth2 client, and can only ever see its own applications.
      </p>

      <form onSubmit={handleSubmit} className="form">
        <label>
          Client ID
          <input value={clientId} onChange={e => setClientId(e.target.value)} placeholder="demo-broker-1" required />
        </label>
        <label>
          Client Secret
          <input
            type="password"
            value={clientSecret}
            onChange={e => setClientSecret(e.target.value)}
            placeholder="demo-secret-1"
            required
          />
        </label>

        {error && <div className="alert alert-error">{error}</div>}

        <button type="submit" disabled={submitting}>
          {submitting ? 'Connecting…' : 'Connect'}
        </button>
      </form>

      <div className="alert alert-warn security-note">
        <strong>This screen is a demo convenience, not a real security pattern.</strong>{' '}
        Client-credentials is a machine-to-machine grant — a real broker integration would
        call this API server-to-server and never type a client secret into a browser. This
        token is kept in memory only (never saved to disk) and disappears on refresh, but a
        production frontend for a real broker-facing product would authenticate people, not
        embed a client secret client-side at all.
      </div>

      <details className="hints">
        <summary>Demo credentials</summary>
        <p className="muted">Two brokers are seeded for local development, so you can also test that one broker never sees another's applications:</p>
        <ul>
          <li><code>demo-broker-1</code> / <code>demo-secret-1</code></li>
          <li><code>demo-broker-2</code> / <code>demo-secret-2</code></li>
        </ul>
      </details>
    </div>
  )
}
