import { useId, useRef, useState, type ReactNode } from 'react'
import { motion } from 'motion/react'
import { Check, Copy, Server } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { usePrefs } from './prefs'
import { INSTALL_CMD } from './site'

const imgUrl = (name: string) => `${import.meta.env.BASE_URL}img/${name}.png`

export function Reveal({ children, className, delay = 0 }: { children: ReactNode; className?: string; delay?: number }) {
  return (
    <motion.div
      className={className}
      initial={{ opacity: 0, y: 16 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, margin: '-60px' }}
      transition={{ duration: 0.5, delay, ease: [0.2, 0.7, 0.2, 1] }}
    >
      {children}
    </motion.div>
  )
}

type PhoneProps = { shot: string; dark: string; alt: string; className?: string; priority?: boolean }

export function Phone({ shot, dark, alt, className, priority }: PhoneProps) {
  const { theme } = usePrefs()
  return (
    <img
      src={imgUrl(theme === 'dark' ? dark : shot)}
      alt={alt}
      width={520}
      height={1204}
      loading={priority ? 'eager' : 'lazy'}
      fetchPriority={priority ? 'high' : 'auto'}
      decoding="async"
      className={cn('phone h-auto', className)}
    />
  )
}

export function CommandBlock({ className }: { className?: string }) {
  const { t } = usePrefs()
  const code = useRef<HTMLElement>(null)
  const labelId = useId()
  const [copied, setCopied] = useState(false)

  async function copy() {
    try {
      await navigator.clipboard.writeText(INSTALL_CMD)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      const sel = getSelection()
      if (code.current && sel) sel.selectAllChildren(code.current)
    }
  }

  return (
    <div className={className}>
      <p id={labelId} className="mb-2 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm font-medium">
        <Server className="size-4" aria-hidden="true" />
        {t.runOn}
        <span className="rounded-full bg-secondary px-2 py-0.5 text-xs font-normal text-muted-foreground">{t.notComputer}</span>
      </p>
      <div className="flex items-center gap-1 rounded-xl border bg-card py-1.5 pr-1.5 pl-4">
        <pre
          tabIndex={0}
          aria-label={t.command}
          aria-describedby={labelId}
          className="min-w-0 flex-1 overflow-x-auto py-1.5 font-mono text-[13px] break-all whitespace-pre-wrap"
        >
          <code ref={code}>
            {INSTALL_CMD.split(' | ')[0]} <span className="whitespace-nowrap">| {INSTALL_CMD.split(' | ')[1]}</span>
          </code>
        </pre>
        <Button variant="secondary" size="icon-lg" onClick={copy} aria-label={t.copy} className="shrink-0">
          {copied ? <Check /> : <Copy />}
        </Button>
        <span role="status" className="sr-only">
          {copied ? t.copied : ''}
        </span>
      </div>
    </div>
  )
}
