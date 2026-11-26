import { StrictMode } from 'react'
import { createRoot, hydrateRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'

const root = document.getElementById('root')!
const lang = location.pathname.startsWith(`${import.meta.env.BASE_URL}fr`) ? 'fr' : 'en'
const app = (
  <StrictMode>
    <App lang={lang} />
  </StrictMode>
)

if (import.meta.env.DEV) createRoot(root).render(app)
else hydrateRoot(root, app)
