import { renderToString } from 'react-dom/server'
import App from './App.tsx'
import type { Lang } from './i18n'

export const render = (lang: Lang) => renderToString(<App lang={lang} />)
