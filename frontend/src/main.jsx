import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter, Routes, Route } from 'react-router-dom'
import App from './App.jsx'
import SubmitPage from './pages/SubmitPage.jsx'
import StatusPage from './pages/StatusPage.jsx'
import ApplicationsListPage from './pages/ApplicationsListPage.jsx'
import './index.css'

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<App />}>
          <Route index element={<SubmitPage />} />
          <Route path="applications" element={<ApplicationsListPage />} />
          <Route path="applications/:applicationId" element={<StatusPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  </React.StrictMode>
)
