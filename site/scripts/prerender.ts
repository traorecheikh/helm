// Runs after `vite build` and `vite build --ssr`: writes the prerendered pages, sitemap, robots and llms files into dist/.
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import { dict, type Lang } from '../src/i18n.ts'
import { APK_URL, AUTHOR, INSTALL_CMD, LICENSE_URL, REPO_URL, SITE_URL, facts, repoFile } from '../src/site.ts'

const root = join(import.meta.dirname, '..')
const dist = join(root, 'dist')
const ssrDir = join(root, 'dist-ssr')
const { render } = (await import(pathToFileURL(join(ssrDir, 'entry-server.js')).href)) as { render: (lang: Lang) => string }

const langs = Object.keys(dict) as Lang[]
const pageUrl = (lang: Lang) => (lang === 'en' ? SITE_URL : `${SITE_URL}${lang}/`)
const HERO_URL = `${SITE_URL}img/hero.png`
const lastmod = new Date().toISOString().slice(0, 10)

const hero = readFileSync(join(root, 'public/img/hero.png'))
const [heroW, heroH] = [hero.readUInt32BE(16), hero.readUInt32BE(20)]

const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
const jsonLd = (data: object) => `<script type="application/ld+json">${JSON.stringify(data).replace(/</g, '\\u003c')}</script>`
const meta = (attr: 'name' | 'property', key: string, value: string | number) => `<meta ${attr}="${key}" content="${esc(String(value))}" />`

const authorId = `${SITE_URL}#author`
const appId = `${SITE_URL}#app`

function head(lang: Lang) {
  const t = dict[lang]
  const other = langs.filter((l) => l !== lang)
  const software = {
    '@context': 'https://schema.org',
    '@graph': [
      {
        '@type': 'SoftwareApplication',
        '@id': appId,
        name: 'Helm',
        description: t.description,
        url: pageUrl(lang),
        image: HERO_URL,
        applicationCategory: 'DeveloperApplication',
        operatingSystem: 'Android 10+',
        downloadUrl: APK_URL,
        softwareHelp: { '@type': 'WebPage', url: repoFile('install/README.md') },
        offers: { '@type': 'Offer', price: '0', priceCurrency: 'USD' },
        isAccessibleForFree: true,
        license: LICENSE_URL,
        author: { '@id': authorId },
      },
      {
        '@type': 'SoftwareSourceCode',
        name: 'Helm',
        codeRepository: REPO_URL,
        programmingLanguage: ['Kotlin', 'Python'],
        license: LICENSE_URL,
        author: { '@id': authorId },
        targetProduct: { '@id': appId },
      },
      { '@type': 'Person', '@id': authorId, name: AUTHOR.name, url: AUTHOR.url, sameAs: [AUTHOR.url] },
    ],
  }
  const faq = {
    '@context': 'https://schema.org',
    '@type': 'FAQPage',
    inLanguage: lang,
    mainEntity: t.faq.map((f) => ({ '@type': 'Question', name: f.q, acceptedAnswer: { '@type': 'Answer', text: f.a } })),
  }
  return [
    `<title>${esc(t.title)}</title>`,
    meta('name', 'description', t.description),
    `<link rel="canonical" href="${pageUrl(lang)}" />`,
    ...langs.map((l) => `<link rel="alternate" hreflang="${l}" href="${pageUrl(l)}" />`),
    `<link rel="alternate" hreflang="x-default" href="${SITE_URL}" />`,
    meta('property', 'og:type', 'website'),
    meta('property', 'og:site_name', 'Helm'),
    meta('property', 'og:title', t.title),
    meta('property', 'og:description', t.description),
    meta('property', 'og:url', pageUrl(lang)),
    meta('property', 'og:image', HERO_URL),
    meta('property', 'og:image:width', heroW),
    meta('property', 'og:image:height', heroH),
    meta('property', 'og:image:alt', t.heroAlt),
    meta('property', 'og:locale', t.ogLocale),
    ...other.map((l) => meta('property', 'og:locale:alternate', dict[l].ogLocale)),
    meta('name', 'twitter:card', 'summary_large_image'),
    meta('name', 'twitter:title', t.title),
    meta('name', 'twitter:description', t.description),
    meta('name', 'twitter:image', HERO_URL),
    meta('name', 'twitter:image:alt', t.heroAlt),
    jsonLd(software),
    jsonLd(faq),
  ].join('\n    ')
}

const template = readFileSync(join(dist, 'index.html'), 'utf8')
for (const marker of ['<!--head-->', '<!--app-->', '<html lang="en">']) {
  if (template.split(marker).length !== 2) throw new Error(`dist/index.html must contain ${marker} exactly once`)
}

for (const lang of langs) {
  const html = template
    .replace('<html lang="en">', `<html lang="${lang}">`)
    .replace('<!--head-->', () => head(lang))
    .replace('<!--app-->', () => render(lang))
  const dir = lang === 'en' ? dist : join(dist, lang)
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, 'index.html'), html)
}

const alternates = [...langs.map((l) => [l, pageUrl(l)]), ['x-default', SITE_URL]]
  .map(([hreflang, href]) => `    <xhtml:link rel="alternate" hreflang="${hreflang}" href="${href}"/>`)
  .join('\n')
writeFileSync(
  join(dist, 'sitemap.xml'),
  `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">
${langs.map((l) => `  <url>\n    <loc>${pageUrl(l)}</loc>\n    <lastmod>${lastmod}</lastmod>\n${alternates}\n  </url>`).join('\n')}
</urlset>
`,
)
writeFileSync(join(dist, 'robots.txt'), `User-agent: *\nAllow: /\n\nSitemap: ${SITE_URL}sitemap.xml\n`)

const en = dict.en
const link = (label: string, url: string, note?: string) => `- [${label}](${url})${note ? `: ${note}` : ''}`

writeFileSync(
  join(dist, 'llms.txt'),
  `# Helm

> ${en.description}

${en.faq[0].a} Install the agent on the server (as root, over SSH, never on your own computer), then pair the Android app with the code it prints.

## Docs

${link('README', repoFile('README.md'), 'what Helm does, install, safe defaults, on-device intelligence')}
${link('Install guide', repoFile('install/README.md'), 'installer flags, agent settings, what it changes, uninstall')}
${link('Full text of this site', `${SITE_URL}llms-full.txt`, 'features, requirements, install, safe defaults, FAQ')}

## Install

${link('Download the APK', APK_URL, 'Android 10 or newer')}
${link('Install script', `${SITE_URL}install.sh`, 'run on a Debian or Ubuntu server as root; read it before running')}

## Source

${link('Repository', REPO_URL, 'app: FSL-1.1-ALv2 (source-available); agent, installer, site: Apache-2.0')}
${link('Agent source', repoFile('agent/helm_agent.py'), 'the single-file Python server agent, Apache-2.0')}
${link('Releases', `${REPO_URL}/releases`)}

## Optional

${link('Website (English)', SITE_URL)}
${link('Website (French)', pageUrl('fr'))}
`,
)

writeFileSync(
  join(dist, 'llms-full.txt'),
  `# Helm

> ${en.description}

## What is Helm?

${en.faq[0].a}

## Features

${facts.tabs.map(([name, line]) => `- **${name}**: ${line}`).join('\n')}

Light and dark themes follow the system. French and English.

## Works with

${en.works.map((w) => `- **${w.name}**: ${w.line}`).join('\n')}

## Requirements

${facts.requirements.map((r) => `- ${r}`).join('\n')}

## Install

${en.steps.map((s, i) => `${i + 1}. **${s.title}**: ${s.line}`).join('\n')}

${en.runOn}, ${en.notComputer}:

\`\`\`sh
${INSTALL_CMD}
\`\`\`

${facts.pairing}

## ${en.safeTitle}

${en.safeOffHead}:

${en.safeOff.map((r) => `- ${r.name} (${r.state}): ${r.line}`).join('\n')}

${en.safeOnHead}:

${en.safeOn.map((l) => `- ${l}`).join('\n')}

## FAQ

${en.faq.slice(1).map((f) => `### ${f.q}\n\n${f.a}`).join('\n\n')}

## License

${facts.license} Full text: ${LICENSE_URL}

## Status

${facts.status}

## Links

${link('Website', SITE_URL)}
${link('README', repoFile('README.md'))}
${link('Install guide', repoFile('install/README.md'))}
${link('Agent source', repoFile('agent/helm_agent.py'))}
${link('Releases', `${REPO_URL}/releases`)}
${link('Download the APK', APK_URL)}
${link('License', LICENSE_URL)}
`,
)

rmSync(ssrDir, { recursive: true, force: true })
