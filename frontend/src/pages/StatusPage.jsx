import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getApplication, ApiError } from '../api/client'
import StatusBadge from '../components/StatusBadge.jsx'

const IN_FLIGHT_STATUSES = new Set(['SUBMITTED', 'IN_REVIEW', 'NEEDS_ATTENTION'])
const POLL_MS = 2000

export default function StatusPage() {
  const { applicationId } = useParams()
  const [application, setApplication] = useState(null)
  const [error, setError] = useState(null)
  const timerRef = useRef(null)

  useEffect(() => {
    let cancelled = false

    async function poll() {
      try {
        const data = await getApplication(applicationId)
        if (cancelled) return
        setApplication(data)
        setError(null)
        if (IN_FLIGHT_STATUSES.has(data.status)) {
          timerRef.current = setTimeout(poll, POLL_MS)
        }
      } catch (err) {
        if (cancelled) return
        if (err instanceof ApiError && err.status === 404) {
          setError('No application found with that ID.')
        } else {
          setError('Lost contact with the underwriting service — retrying…')
          timerRef.current = setTimeout(poll, POLL_MS)
        }
      }
    }

    poll()
    return () => {
      cancelled = true
      clearTimeout(timerRef.current)
    }
  }, [applicationId])

  return (
    <div className="card">
      <Link to="/" className="back-link">&larr; Submit another application</Link>
      <h1>Application {applicationId}</h1>

      {error && <div className="alert alert-error">{error}</div>}

      {!application && !error && <p className="muted">Loading…</p>}

      {application && (
        <>
          <div className="status-row">
            <StatusBadge status={application.status} />
            {application.decision && <span className="decision">Decision: <strong>{application.decision}</strong></span>}
            {application.score != null && <span className="score">Score: <strong>{application.score}</strong> / 100</span>}
          </div>

          {IN_FLIGHT_STATUSES.has(application.status) && (
            <p className="muted">Evaluating… this page updates automatically.</p>
          )}

          {application.failureReason && (
            <div className="alert alert-warn">{application.failureReason}</div>
          )}

          <section className="details-grid">
            <div><span className="label">Applicant</span><span>{application.applicantId}</span></div>
            <div><span className="label">Bond Type</span><span>{application.bondType}</span></div>
            <div><span className="label">Bond Amount</span><span>${Number(application.bondAmount).toLocaleString()}</span></div>
            <div><span className="label">Effective Date</span><span>{application.effectiveDate}</span></div>
            <div><span className="label">Obligee</span><span>{application.obligeeName}</span></div>
            <div><span className="label">Created</span><span>{new Date(application.createdAt).toLocaleString()}</span></div>
            <div><span className="label">Updated</span><span>{new Date(application.updatedAt).toLocaleString()}</span></div>
          </section>

          {Array.isArray(application.scoreBreakdown) && (
            <section className="breakdown">
              <h2>Score Breakdown</h2>
              <table>
                <thead>
                  <tr><th>Factor</th><th>Detail</th><th>Points</th></tr>
                </thead>
                <tbody>
                  {application.scoreBreakdown.map((row, i) => (
                    <tr key={i}>
                      <td>{row.factor}</td>
                      <td>{row.detail}</td>
                      <td>{row.points}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          )}
        </>
      )}
    </div>
  )
}
