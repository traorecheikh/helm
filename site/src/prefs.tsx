import { createContext, useCallback, useContext, useEffect, useSyncExternalStore, type ReactNode } from 'react'
import { dict, type Dict, type Lang } from './i18n'

type Theme = 'light' | 'dark'
type Prefs = { theme: Theme; toggleTheme: () => void; lang: Lang; t: Dict }

const PrefsContext = createContext<Prefs | null>(null)
const stored = (key: string) => {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}
export const store = (key: string, value: string) => {
  try {
    localStorage.setItem(key, value)
  } catch {
    // private mode: the choice just does not persist
  }
}

// The theme lives on <html class="dark"> (set by the inline script in index.html); the server snapshot is always light.
const subscribeTheme = (onChange: () => void) => {
  const observer = new MutationObserver(onChange)
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
  return () => observer.disconnect()
}
const clientTheme = (): Theme => (document.documentElement.classList.contains('dark') ? 'dark' : 'light')
const serverTheme = (): Theme => 'light'

export function PrefsProvider({ lang, children }: { lang: Lang; children: ReactNode }) {
  const theme = useSyncExternalStore(subscribeTheme, clientTheme, serverTheme)

  useEffect(() => {
    const media = matchMedia('(prefers-color-scheme: dark)')
    const follow = (e: MediaQueryListEvent) => {
      if (!stored('helm-theme')) document.documentElement.classList.toggle('dark', e.matches)
    }
    media.addEventListener('change', follow)
    return () => media.removeEventListener('change', follow)
  }, [])

  const toggleTheme = useCallback(() => {
    const next = theme === 'dark' ? 'light' : 'dark'
    store('helm-theme', next)
    document.documentElement.classList.toggle('dark', next === 'dark')
  }, [theme])

  return <PrefsContext.Provider value={{ theme, toggleTheme, lang, t: dict[lang] }}>{children}</PrefsContext.Provider>
}

export function usePrefs() {
  const ctx = useContext(PrefsContext)
  if (!ctx) throw new Error('usePrefs outside PrefsProvider')
  return ctx
}
