# Helm agent installer

Debian and Ubuntu (apt, systemd) only. Other systems are refused.

    curl -fsSL <url>/install.sh | sudo sh -s -- --domain helm.example.com

Without `--domain` the agent listens on 127.0.0.1 only. The app needs https,
so a domain is required to use it from a phone.

## Flags

    --dry-run       print every step, change nothing
    --uninstall     remove the agent (see below)
    --domain NAME   public name for https://NAME/helm (A record must point here)
    --no-fail2ban   do not install or configure fail2ban
    --no-crowdsec   do not offer CrowdSec
    --no-trivy      do not install Trivy (the vulnerability audit stays off)
    --yes           answer yes to confirmations (never opts in to CrowdSec)

`HELM_DOKPLOY_KEY=<key>` (env, never printed) sets the Dokploy API key:
`sudo HELM_DOKPLOY_KEY=... sh -s -- ...`. On a Dokploy server it is asked
silently if missing and the terminal is interactive.

## Agent settings

All optional, in `/etc/helm-agent/env` (mode 0600), then
`systemctl restart helm-agent`. Unset means off.

| Variable | Default | Effect |
| --- | --- | --- |
| `HELM_BIND` | `127.0.0.1` | Listen address. The installer writes `0.0.0.0` when it adds a Traefik route. |
| `HELM_DOKPLOY_KEY` | unset | Dokploy API key. Unset: Dokploy calls answer 503 "Dokploy is not configured". |
| `HELM_COOLIFY_TOKEN` | unset | Coolify API token (abilities `read` and `deploy`; logs also need `read:sensitive`, which shows secrets to every paired phone). |
| `HELM_COOLIFY_BASE` | `http://127.0.0.1:8000/api/v1/` | Coolify API address. |
| `HELM_SSH_ALLOW_COUNTRIES` | unset | Comma list (`SN`, `SN,FR`). Port 22 then only accepts these countries, `ignoreip`, paired phones and private ranges. These countries can never be geo-blocked or banned. |
| `HELM_NO_EXTERNAL_LOOKUPS` | unset | `1`: no ipinfo.io lookups, no ipdeny.com country lists (so no country lock or geo-blocking). |
| `HELM_STALWART_ENV` | unset | Stalwart compose `.env` holding `*_STALWART_ADMIN_KEY`. Unset: mail features stay empty. |
| `HELM_PUBLIC_IP` | unset | Public IP when `ip route` cannot tell (DNS and mail checks). |
| `HELM_SERVER_IPS` | unset | Extra addresses of this server, never counted as visitors. |
| `HELM_OWN_HOSTS` | unset | Your own hostnames, never counted as probes. |
| `HELM_NO_AUTOFIX` | unset | `1`: kill switch, no automatic redeploy for any service, whatever the app says. |

`TRIVY_VERSION=<x.y.z>` (installer env) pins the Trivy package version.

By default the agent asks ipinfo.io for the country of unknown IPs and
downloads country lists from ipdeny.com once a day.

## How https is exposed

1. Dokploy found (`/etc/dokploy/traefik/dynamic`): writes
   `helm-agent.yml` there (strip `/helm`, forward to the agent).
2. Coolify with Traefik found: same, in `/data/coolify/proxy/dynamic`.
3. Ports 80/443 free: installs the distro `caddy` package, writes
   `/etc/caddy/helm-agent.caddy` and one `import` line in the Caddyfile.
   Certificates come from Let's Encrypt automatically.
4. Another service owns 80/443: prints what to route by hand.

## What it changes

- Packages: python3, python3-cryptography, nftables, curl, ca-certificates,
  iproute2, procps, fail2ban, update-notifier-common (if available).
  Caddy and CrowdSec only when needed or accepted. unattended-upgrades,
  needrestart; Trivy and gnupg unless `--no-trivy`.
- `/etc/apt/apt.conf.d/52helm-unattended`: daily install of security
  updates only (Debian-Security, Ubuntu -security and ESM pockets; docker.io,
  containerd and runc excluded), never reboots.
  `/etc/needrestart/conf.d/50-helm.conf`: services restart after updates
  (needrestart never restarts Docker). Reboots stay with the app.
- Trivy: apt repo `get.trivy.dev` only if its key is
  `825AD9036F7C850E6A6FED4935B8ACA44FD9CA9F`; the package is held, upgrade it
  by hand. Scans run daily at the quiet hour, results in
  `/var/lib/helm-agent/audit.json`, cache in `/var/lib/helm-agent/trivy`.
- `/usr/local/bin/helm-agent`, `/etc/systemd/system/helm-agent.service`.
- `/etc/helm-agent/` (env with fresh secrets, mode 0600; guard.nft;
  install.state) and `/var/lib/helm-agent/` (state, auto-reboot off).
- `/etc/fail2ban/jail.local` only if absent: sshd and recidive jails
  (recidive bans are permanent, `bantime = -1`), nftables ban action,
  ignoreip with the SSH clients connected now. Permanent bans from the app
  are refused when recidive is not `bantime = -1`.
- nftables table `helm-guard`: port 9311 is reachable from localhost and
  Docker networks only. Kept even with `HELM_BIND=0.0.0.0`: on Coolify or
  before Dokploy's Traefik is up, it is the only rule closing the port.
- Existing files are never overwritten without confirmation. The env file
  only gets missing keys.

The SSH country lock is off unless `HELM_SSH_ALLOW_COUNTRIES` is set. Before
setting it, put every IP you use in `ignoreip` in `/etc/fail2ban/jail.local`.
Removing the variable removes the lock at the next restart.

## Release tarball

The script downloads `HELM_URL`, checks `HELM_SHA256`, and extracts
`helm_agent.py` and `helm-agent.service`. Set the variables at the top of
`install.sh` once published (APK_URL is printed at the end):

    tar -czf helm-agent-X.tar.gz -C agent helm_agent.py -C ../install helm-agent.service
    shasum -a 256 helm-agent-X.tar.gz

## Uninstall

    sudo sh install.sh --uninstall

Stops the service, backs up `/var/lib/helm-agent` and `/etc/helm-agent` to
`/var/backups/helm-agent-<timestamp>` (root only, contains secrets), removes
the nftables tables the agent created, the route or Caddy file it added,
`jail.local` if this installer created it, and the agent files. Caddy and
CrowdSec are purged only if this installer added them and you confirm.
Other packages stay.

## Tests

    sh install/test.sh

Runs shellcheck and `--dry-run` in `ubuntu:24.04` and `debian:12` when
shellcheck and Docker are available.
