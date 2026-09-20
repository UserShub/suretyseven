import { useEffect, useState } from 'react'
import { Outlet, Link, useLocation, useNavigate } from 'react-router-dom'
import { setAccessToken, setOnSessionExpired } from './api/client'
import ConnectPage from './pages/ConnectPage.jsx'

export default function App() {
  const location = useLocation()
  const navigate = useNavigate()
  const [brokerLabel, setBrokerLabel] = useState(null)

  useEffect(() => {
    // If a request ever comes back 401 (token expired -- these are
    // short-lived, 15 minutes), drop back to the connect screen rather
    // than leaving the app stuck showing stale/broken pages.
    setOnSessionExpired(() => {
      setBrokerLabel(null)
    })
  }, [])

  if (!brokerLabel) {
    return (
      <div className="page">
        <header className="topbar">
          <span className="brand">SuretySeven</span>
          <span className="brand-sub">Bond Underwriting</span>
        </header>
        <main className="content">
          <ConnectPage onConnected={id => setBrokerLabel(id)} />
        </main>
        <footer className="footer">Take-home demo &middot; mock external Applicant API &amp; mock downstream notifications</footer>
      </div>
    )
  }

  function handleDisconnect() {
    setAccessToken(null)
    setBrokerLabel(null)
    navigate('/')
  }

  const isSubmit = location.pathname === '/'
  const isList = location.pathname.startsWith('/applications')

  return (
    <div className="page">
      <header className="topbar">
        <Link to="/" className="brand">SuretySeven</Link>
        <span className="brand-sub">Bond Underwriting</span>
        <nav className="tabs">
          <Link to="/" className={isSubmit ? 'tab tab-active' : 'tab'}>New Application</Link>
          <Link to="/applications" className={isList ? 'tab tab-active' : 'tab'}>Applications</Link>
        </nav>
        <div className="broker-session">
          <span className="muted">Connected as <strong>{brokerLabel}</strong></span>
          <button className="disconnect-btn" onClick={handleDisconnect}>Disconnect</button>
        </div>
      </header>
      <main className="content">
        <Outlet />
      </main>
      <footer className="footer">Take-home demo &middot; mock external Applicant API &amp; mock downstream notifications</footer>
    </div>
  )
}
