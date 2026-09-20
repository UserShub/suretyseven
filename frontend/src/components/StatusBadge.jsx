const STYLES = {
  SUBMITTED: 'badge badge-neutral',
  IN_REVIEW: 'badge badge-neutral',
  NEEDS_ATTENTION: 'badge badge-warn',
  APPROVED: 'badge badge-good',
  REFERRED: 'badge badge-warn',
  DECLINED: 'badge badge-bad',
  FAILED: 'badge badge-bad'
}

export default function StatusBadge({ status }) {
  return <span className={STYLES[status] || 'badge badge-neutral'}>{status}</span>
}
