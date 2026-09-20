import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { listApplications, ApiError } from '../api/client'
import StatusBadge from '../components/StatusBadge.jsx'

const PAGE_SIZE = 10

export default function ApplicationsListPage() {
  const [page, setPage] = useState(0)
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    listApplications({ page, size: PAGE_SIZE })
      .then(result => {
        if (cancelled) return
        setData(result)
        setError(null)
      })
      .catch(err => {
        if (cancelled) return
        setError(err instanceof ApiError ? (err.body?.message || err.message) : 'Could not reach the underwriting service.')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => { cancelled = true }
  }, [page])

  return (
    <div className="card card-wide">
      <div className="list-header">
        <h1>Applications</h1>
        <Link to="/" className="new-app-link">+ New Application</Link>
      </div>

      {error && <div className="alert alert-error">{error}</div>}
      {loading && !data && <p className="muted">Loading…</p>}

      {data && data.content.length === 0 && (
        <p className="muted">No applications submitted yet.</p>
      )}

      {data && data.content.length > 0 && (
        <>
          <table className="list-table">
            <thead>
              <tr>
                <th>Application ID</th>
                <th>Applicant</th>
                <th>Bond Type</th>
                <th>Amount</th>
                <th>Status</th>
                <th>Decision</th>
                <th>Score</th>
                <th>Created</th>
              </tr>
            </thead>
            <tbody>
              {data.content.map(app => (
                <tr key={app.applicationId}>
                  <td><Link to={`/applications/${app.applicationId}`} className="row-link">{app.applicationId}</Link></td>
                  <td>{app.applicantId}</td>
                  <td>{app.bondType}</td>
                  <td>${Number(app.bondAmount).toLocaleString()}</td>
                  <td><StatusBadge status={app.status} /></td>
                  <td>{app.decision || '—'}</td>
                  <td>{app.score ?? '—'}</td>
                  <td>{new Date(app.createdAt).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className="pagination">
            <button disabled={page <= 0} onClick={() => setPage(p => p - 1)}>&larr; Previous</button>
            <span className="muted">Page {data.page + 1} of {Math.max(data.totalPages, 1)} &middot; {data.totalElements} total</span>
            <button disabled={page + 1 >= data.totalPages} onClick={() => setPage(p => p + 1)}>Next &rarr;</button>
          </div>
        </>
      )}
    </div>
  )
}
