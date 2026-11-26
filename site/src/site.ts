import { LICENSE } from './license'

export const SITE_URL = 'https://traorecheikh.github.io/helm/'
export const REPO_URL = 'https://github.com/traorecheikh/helm'
export const APK_URL = `${REPO_URL}/releases/latest/download/helm.apk`
export const INSTALL_CMD = 'curl -fsSL https://traorecheikh.github.io/helm/install.sh | sudo sh'
export const LICENSE_URL = LICENSE.url
export const AUTHOR = { name: 'traorecheikh', url: 'https://github.com/traorecheikh' }

export const repoFile = (path: string) => `${REPO_URL}/blob/main/${path}`

// Read by scripts/prerender.ts for llms-full.txt; from README.md and install/README.md.
export const facts = {
  tabs: [
    ['Home', 'One card tells you if everything is fine: orange when something needs a look, red when it is down.'],
    ['Apps', 'Projects, services and deploys. Redeploy, restart, stop, read logs and env.'],
    ['Shield', 'Live probes sorted by what they try, ban or unban in one gesture, attack map by country, lockdown.'],
    ['Server', 'CPU, RAM, disk, containers, uptime, certificates, DNS, mail, backups and a vulnerability audit.'],
  ],
  requirements: [
    'Phone: Android 10 or newer.',
    'Server: Debian or Ubuntu (apt and systemd). Any other system is refused by the installer.',
    'Run the installer on the server, as root, over SSH. Never on your own computer.',
    'The app needs https, so add --domain your.host (its A record must point to the server) to use it from a phone.',
  ],
  pairing:
    'The installer prints a pairing code (valid 30 minutes). In the app, enter the server address and the code. The first phone becomes the owner; later phones ask and the owner approves.',
  license:
    'The Android app (android/) is source-available under FSL-1.1-ALv2 (Functional Source License, https://fsl.software): each release converts to Apache-2.0 two years after publication. The agent (agent/), the installer (install/) and this website (site/) are open source under Apache-2.0.',
  status:
    'Helm is young. Coolify support was written against its API documentation and has not been tried on a live Coolify. The installer is tested in dry-run and shell checks, not yet on a fleet of fresh servers.',
}
