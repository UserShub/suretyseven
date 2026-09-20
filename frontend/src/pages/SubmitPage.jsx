import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { createApplication, ApiError } from '../api/client'

const BOND_TYPES = ['CONTRACT', 'COMMERCIAL', 'COURT', 'LICENSE_AND_PERMIT']

const SIMULATION_HINTS = [
  { prefix: '', label: 'Normal (deterministic mock data)' },
  { prefix: 'SLOW-', label: 'Slow external API (~3s)' },
  { prefix: 'ERROR-', label: 'External API 500s every time' },
  { prefix: 'TIMEOUT-', label: 'External API hangs/times out' },
  { prefix: 'MALFORMED-', label: 'External API returns broken JSON' }
]

function todayPlus(days) {
  const d = new Date()
  d.setDate(d.getDate() + days)
  return d.toISOString().slice(0, 10)
}

export default function SubmitPage() {
  const navigate = useNavigate()
  const [form, setForm] = useState({
    applicantId: 'COMP-123',
    bondType: 'CONTRACT',
    bondAmount: '500000',
    effectiveDate: todayPlus(30),
    obligeeName: 'ABC Construction LLC'
  })
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState(null)

  function update(field, value) {
    setForm(f => ({ ...f, [field]: value }))
  }

  async function handleSubmit(e) {
    e.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      const payload = {
        applicantId: form.applicantId.trim(),
        bondType: form.bondType,
        bondAmount: Number(form.bondAmount),
        effectiveDate: form.effectiveDate,
        obligee: { name: form.obligeeName.trim() }
      }
      // A fresh idempotency key per submit -- this is what a real client
      // would persist and reuse on retry of THIS SAME logical request.
      const idempotencyKey = crypto.randomUUID()
      const result = await createApplication(payload, idempotencyKey)
      navigate(`/applications/${result.applicationId}`)
    } catch (err) {
      if (err instanceof ApiError) {
        const details = err.body?.details?.join('; ')
        setError(details ? `${err.body.message}: ${details}` : (err.body?.message || err.message))
      } else {
        setError('Could not reach the underwriting service. Is the backend running?')
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="card">
      <h1>Submit a Bond Application</h1>
      <p className="muted">
        This calls a mock external Applicant API and a mock downstream notification receiver — see the
        prefixes below to try the failure paths.
      </p>

      <form onSubmit={handleSubmit} className="form">
        <label>
          Applicant ID
          <input
            value={form.applicantId}
            onChange={e => update('applicantId', e.target.value)}
            required
          />
        </label>

        <label>
          Bond Type
          <select value={form.bondType} onChange={e => update('bondType', e.target.value)}>
            {BOND_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
          </select>
        </label>

        <label>
          Bond Amount (USD)
          <input
            type="number"
            min="1"
            step="0.01"
            value={form.bondAmount}
            onChange={e => update('bondAmount', e.target.value)}
            required
          />
        </label>

        <label>
          Effective Date
          <input
            type="date"
            value={form.effectiveDate}
            onChange={e => update('effectiveDate', e.target.value)}
            required
          />
        </label>

        <label>
          Obligee Name
          <input
            value={form.obligeeName}
            onChange={e => update('obligeeName', e.target.value)}
            required
          />
        </label>

        {error && <div className="alert alert-error">{error}</div>}

        <button type="submit" disabled={submitting}>
          {submitting ? 'Submitting…' : 'Submit Application'}
        </button>
      </form>

      <details className="hints">
        <summary>Try a failure scenario</summary>
        <p className="muted">Prefix the Applicant ID to trigger a mock external-API behavior:</p>
        <ul>
          {SIMULATION_HINTS.map(h => (
            <li key={h.label}><code>{h.prefix || '(none)'}</code> — {h.label}</li>
          ))}
        </ul>
      </details>
    </div>
  )
}
