import { ArrowUpRight, Download, Globe, Moon, Radar, Server, ShieldCheck, Sun, Check } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/components/ui/accordion'
import { CoolifyLogo, DokployLogo } from './brand'
import { MotionConfig } from 'motion/react'
import type { Lang } from './i18n'
import { LICENSE } from './license'
import { PrefsProvider, store, usePrefs } from './prefs'
import { CommandBlock, Phone, Reveal } from './parts'
import { APK_URL, REPO_URL } from './site'

const wrap = 'mx-auto w-full max-w-6xl px-5'
const section = 'py-20 md:py-28'
const h2 = 'text-[clamp(2rem,4.5vw,3.5rem)] leading-none font-semibold tracking-[-0.03em]'

const shots = {
  home: ['l_home', 'd_home'],
  apps: ['l_apps2', 'd_apps'],
  shield: ['l_shield', 'd_shield'],
  server: ['l_server', 'd_server'],
} as const

function Header() {
  const { t, lang, theme, toggleTheme } = usePrefs()
  const other = lang === 'en' ? 'fr' : 'en'
  return (
    <header className="sticky top-0 z-20 border-b bg-background/85 backdrop-blur">
      <div className={`${wrap} flex h-14 items-center justify-between`}>
        <a href="#main" className="flex items-center gap-2.5 text-lg font-semibold tracking-tight">
          <img src={`${import.meta.env.BASE_URL}favicon.svg`} alt="" width={28} height={28} className="size-7" />
          Helm
        </a>
        <div className="flex items-center gap-1">
          <Button variant="ghost" size="lg" asChild className="font-semibold">
            <a
              href={`${import.meta.env.BASE_URL}${other === 'fr' ? 'fr/' : ''}`}
              hrefLang={other}
              lang={other}
              aria-label={t.switchLang}
              onClick={() => store('helm-lang', other)}
            >
              {t.langLabel}
            </a>
          </Button>
          <Button variant="ghost" size="icon-lg" onClick={toggleTheme} aria-label={t.themeLabel} aria-pressed={theme === 'dark'}>
            {theme === 'dark' ? <Sun /> : <Moon />}
          </Button>
        </div>
      </div>
    </header>
  )
}

function Hero() {
  const { t } = usePrefs()
  return (
    <section className="relative overflow-hidden">
      <div className="dots absolute inset-x-0 top-0 h-[36rem]" aria-hidden="true" />
      <div
        className={`${wrap} relative grid grid-cols-1 items-center gap-14 pt-14 pb-20 md:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)] md:pt-20 md:pb-24`}
      >
        <div>
          <h1 className="text-[clamp(2.8rem,6vw,5rem)] leading-[0.93] font-semibold tracking-[-0.045em]">
            {t.heroTitle.map((line) => (
              <span key={line} className="block">
                {line}{' '}
              </span>
            ))}
          </h1>
          <p className="mt-6 max-w-md text-xl text-muted-foreground">{t.heroSub}</p>
          <div className="mt-9 flex max-w-xl flex-col gap-3">
            <Button asChild size="lg" className="h-12 w-fit gap-2 px-6 text-base">
              <a href={APK_URL}>
                <Download />
                {t.apk}
              </a>
            </Button>
            <CommandBlock />
          </div>
        </div>
        <div className="mx-auto flex w-full max-w-md items-start" aria-label={t.heroAlt} role="group">
          <Phone shot="l_home" dark="d_home" alt={t.screens[0].alt} priority className="w-[56%]" />
          <Phone shot="l_shield" dark="d_shield" alt={t.screens[2].alt} priority className="mt-[16%] -ml-[10%] w-[54%]" />
        </div>
      </div>
    </section>
  )
}

function Gallery() {
  const { t } = usePrefs()
  return (
    <section aria-labelledby="see" className={section}>
      <div className={wrap}>
        <Reveal>
          <h2 id="see" className={h2}>
            {t.seeTitle}
          </h2>
        </Reveal>
      </div>
      <div
        role="region"
        aria-label={t.seeRegion}
        tabIndex={0}
        className="mx-auto mt-12 w-fit max-w-full snap-x snap-mandatory overflow-x-auto px-5 pb-10"
      >
        <ul className="flex gap-6">
          {t.screens.map((s, i) => (
            <li key={s.key} className={`w-[62vw] max-w-60 shrink-0 snap-center md:w-60 ${i % 2 ? 'md:mt-12' : ''}`}>
              <Reveal delay={i * 0.06}>
                <figure>
                  <Phone
                    shot={shots[s.key as keyof typeof shots][0]}
                    dark={shots[s.key as keyof typeof shots][1]}
                    alt={s.alt}
                    className="w-full"
                  />
                  <figcaption className="mt-5 text-lg font-medium">{s.label}</figcaption>
                </figure>
              </Reveal>
            </li>
          ))}
        </ul>
      </div>
    </section>
  )
}

function WorksWith() {
  const { t } = usePrefs()
  const logos = [
    <DokployLogo key="d" className="h-14 w-auto" />,
    <CoolifyLogo key="c" className="h-14 w-14" />,
    <Server key="s" className="size-14" strokeWidth={1.25} />,
  ]
  return (
    <section aria-labelledby="works" className={`${wrap} pb-20 md:pb-28`}>
      <Reveal>
        <h2 id="works" className={h2}>
          {t.worksTitle}
        </h2>
        <ul className="mt-12 grid divide-y overflow-hidden rounded-2xl border bg-card md:grid-cols-3 md:divide-x md:divide-y-0">
          {t.works.map((w, i) => (
            <li key={w.name} className="flex flex-col gap-8 p-8 md:p-10">
              <div className="flex h-14 items-end">{logos[i]}</div>
              <div>
                <p className="text-3xl font-semibold tracking-tight">{w.name}</p>
                <p className="mt-2 text-muted-foreground">{w.line}</p>
              </div>
            </li>
          ))}
        </ul>
      </Reveal>
    </section>
  )
}

function Shield() {
  const { t } = usePrefs()
  const icons = [Radar, ShieldCheck, Globe]
  return (
    <section aria-labelledby="shield" className="overflow-hidden bg-primary text-primary-foreground">
      <div className={`${wrap} grid grid-cols-1 items-center gap-12 pt-20 md:grid-cols-2 md:pt-28`}>
        <div className="md:order-2">
          <Reveal>
            <h2 id="shield" className="text-[clamp(3rem,8vw,6rem)] leading-none font-semibold tracking-[-0.04em]">
              {t.shieldTitle}
            </h2>
            <ul className="mt-10">
              {t.shield.map((line, i) => {
                const Icon = icons[i]
                return (
                  <li
                    key={line}
                    className="flex items-start gap-4 border-t border-primary-foreground/30 py-5 text-xl font-medium md:text-2xl"
                  >
                    <Icon className="mt-1 size-6 shrink-0" aria-hidden="true" />
                    {line}
                  </li>
                )
              })}
            </ul>
          </Reveal>
        </div>
        <Reveal className="mx-auto -mb-28 w-full max-w-sm self-end md:order-1">
          <img
            src={`${import.meta.env.BASE_URL}img/l_map.png`}
            alt={t.shieldAlt}
            width={520}
            height={1204}
            loading="lazy"
            decoding="async"
            className="phone h-auto w-full"
          />
        </Reveal>
      </div>
    </section>
  )
}

function Install() {
  const { t } = usePrefs()
  return (
    <section aria-labelledby="install" className={`${wrap} ${section}`}>
      <div className="grid grid-cols-1 gap-14 md:grid-cols-2">
        <Reveal>
          <h2 id="install" className={h2}>
            {t.installTitle}
          </h2>
          <CommandBlock className="mt-10" />
        </Reveal>
        <Reveal delay={0.1}>
          <ol>
            {t.steps.map((s, i) => (
              <li key={s.title} className="group relative flex gap-5 pb-10 last:pb-0">
                <span className="relative z-10 grid size-10 shrink-0 place-items-center rounded-full bg-primary font-semibold text-primary-foreground">
                  {i + 1}
                </span>
                <span className="absolute top-10 left-5 h-[calc(100%-2.5rem)] w-px bg-border group-last:hidden" aria-hidden="true" />
                <div className="pt-1.5">
                  <p className="text-2xl font-semibold tracking-tight">{s.title}</p>
                  <p className="mt-1 text-muted-foreground">{s.line}</p>
                </div>
              </li>
            ))}
          </ol>
        </Reveal>
      </div>
    </section>
  )
}

function Safe() {
  const { t } = usePrefs()
  return (
    <section aria-labelledby="safe" className={`${wrap} pb-20 md:pb-28`}>
      <div className="grid grid-cols-1 gap-12 md:grid-cols-[minmax(0,1fr)_minmax(0,1.6fr)]">
        <Reveal>
          <h2 id="safe" className={h2}>
            {t.safeTitle}
          </h2>
        </Reveal>
        <Reveal delay={0.1} className="overflow-hidden rounded-2xl border bg-card">
          <table className="w-full text-left">
            <caption className="px-6 pt-5 pb-2 text-left text-sm font-medium text-muted-foreground">{t.safeOffHead}</caption>
            <tbody className="divide-y">
              {t.safeOff.map((r) => (
                <tr key={r.name}>
                  <th scope="row" className="px-6 py-4 align-top font-medium">
                    {r.name}
                    <span className="mt-0.5 block text-sm font-normal text-muted-foreground">{r.line}</span>
                  </th>
                  <td className="py-4 pr-6 text-right align-top">
                    <span className="inline-block rounded-full bg-secondary px-3 py-1 text-sm font-medium">{r.state}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="border-t px-6 py-5">
            <h3 className="text-sm font-medium text-muted-foreground">{t.safeOnHead}</h3>
            <ul className="mt-3 space-y-2">
              {t.safeOn.map((line) => (
                <li key={line} className="flex items-start gap-3 font-medium text-ok">
                  <Check className="mt-0.5 size-5 shrink-0" aria-hidden="true" />
                  {line}
                </li>
              ))}
            </ul>
          </div>
        </Reveal>
      </div>
    </section>
  )
}

function Faq() {
  const { t } = usePrefs()
  return (
    <section aria-labelledby="faq" className={`${wrap} pb-20 md:pb-28`}>
      <div className="grid grid-cols-1 gap-12 md:grid-cols-[minmax(0,1fr)_minmax(0,1.6fr)]">
        <Reveal>
          <h2 id="faq" className={h2}>
            {t.faqTitle}
          </h2>
        </Reveal>
        <Reveal delay={0.1}>
          <Accordion type="single" collapsible className="border-y">
            {t.faq.map((f) => (
              <AccordionItem key={f.q} value={f.q}>
                <AccordionTrigger className="py-5 text-lg">{f.q}</AccordionTrigger>
                <AccordionContent className="max-w-prose text-base text-muted-foreground">{f.a}</AccordionContent>
              </AccordionItem>
            ))}
          </Accordion>
        </Reveal>
      </div>
    </section>
  )
}

function Footer() {
  const { t, lang } = usePrefs()
  return (
    <footer className="border-t">
      <div className={`${wrap} flex flex-col gap-4 py-10 text-sm text-muted-foreground sm:flex-row sm:items-center sm:justify-between`}>
        <p>
          {t.license}{' '}
          <a href={LICENSE.url} className="font-medium text-foreground underline underline-offset-4">
            {LICENSE.label[lang]}
          </a>
        </p>
        <p>{t.marks}</p>
        <a href={REPO_URL} className="inline-flex items-center gap-1 font-medium text-foreground underline underline-offset-4">
          {t.github}
          <ArrowUpRight className="size-4" aria-hidden="true" />
        </a>
      </div>
    </footer>
  )
}

function Page() {
  const { t } = usePrefs()
  return (
    <>
      <a
        href="#main"
        className="sr-only z-30 rounded-lg bg-primary px-4 py-2 text-primary-foreground focus:not-sr-only focus:fixed focus:top-3 focus:left-3"
      >
        {t.skip}
      </a>
      <Header />
      <main id="main">
        <Hero />
        <Gallery />
        <WorksWith />
        <Shield />
        <Install />
        <Safe />
        <Faq />
      </main>
      <Footer />
    </>
  )
}

export default function App({ lang }: { lang: Lang }) {
  return (
    <PrefsProvider lang={lang}>
      <MotionConfig reducedMotion="user">
        <Page />
      </MotionConfig>
    </PrefsProvider>
  )
}
