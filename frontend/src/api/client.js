const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'

// Module-level, in-memory only (never localStorage/sessionStorage -- see
// ConnectPage's on-screen note on why client_credentials-in-a-browser is a
// demo convenience, not something to persist across page reloads).
let accessToken = null
let onSessionExpired = null

export function setAccessToken(token) {
  accessToken = token
}

export function hasAccessToken() {
  return !!accessToken
}

/** App.jsx registers a callback here so a 401 (expired/invalid token) can drop back to the connect screen. */
export function setOnSessionExpired(callback) {
  onSessionExpired = callback
}

class ApiError extends Error {
  constructor(status, body) {
    super(body?.message || `Request failed with status ${status}`)
    this.status = status
    this.body = body
  }
}

async function request(path, options = {}) {
  const res = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      ...(options.headers || {})
    }
  })

  const isJson = res.headers.get('content-type')?.includes('application/json')
  const body = isJson ? await res.json().catch(() => null) : null

  if (!res.ok) {
    if (res.status === 401) {
      accessToken = null
      onSessionExpired?.()
    }
    throw new ApiError(res.status, body)
  }
  return body
}

/**
 * Exchanges a broker's client_id/client_secret for an access token via the
 * real OAuth2 client_credentials grant, hitting this backend's own
 * /oauth2/token endpoint directly (not through the `request` helper above,
 * since this call itself doesn't carry a bearer token -- it uses HTTP
 * Basic with the client credentials instead, per the OAuth2 spec).
 */
export async function fetchAccessToken(clientId, clientSecret) {
  const body = new URLSearchParams()
  body.set('grant_type', 'client_credentials')
  body.set('scope', 'applications.read applications.write')

  const res = await fetch(`${API_BASE_URL}/oauth2/token`, {
    method: 'POST',
    headers: {
      Authorization: `Basic ${btoa(`${clientId}:${clientSecret}`)}`,
      'Content-Type': 'application/x-www-form-urlencoded'
    },
    body
  })

  const isJson = res.headers.get('content-type')?.includes('application/json')
  const data = isJson ? await res.json().catch(() => null) : null

  if (!res.ok) {
    throw new ApiError(res.status, data)
  }
  setAccessToken(data.access_token)
  return data
}

export function createApplication(payload, idempotencyKey) {
  return request('/applications', {
    method: 'POST',
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {},
    body: JSON.stringify(payload)
  })
}

export function getApplication(applicationId) {
  return request(`/applications/${applicationId}`)
}

export function listApplications({ page = 0, size = 10 } = {}) {
  return request(`/applications?page=${page}&size=${size}`)
}

export { ApiError }
