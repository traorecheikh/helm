#!/bin/sh
# Helm agent installer for Debian and Ubuntu. See install/README.md.
set -eu

: "${HELM_VERSION:=2.0}"
: "${HELM_URL:=https://github.com/traorecheikh/helm/releases/download/v$HELM_VERSION/helm-agent-$HELM_VERSION.tar.gz}"
: "${HELM_SHA256:=805be673a8286f542b7285ea8557ab3db6573a32c9273a69e24f870037fedf2c}"
: "${APK_URL:=https://github.com/traorecheikh/helm/releases/latest/download/helm.apk}"
: "${TRIVY_VERSION:=}"
# Trivy repo key rotated after the March 2026 incident, see github.com/aquasecurity/trivy/discussions/10549.
TRIVY_KEY=825AD9036F7C850E6A6FED4935B8ACA44FD9CA9F

CONF=/etc/helm-agent
DATA=/var/lib/helm-agent
BIN=/usr/local/bin/helm-agent
UNIT=/etc/systemd/system/helm-agent.service
MARK=$CONF/install.state
JAIL=/etc/fail2ban/jail.local
PORT=9311

DRY=0
YES=0
UNINSTALL=0
DOMAIN=
F2B=1
CS=ask
TRIVY=1
CHANGED=0
TMP=
PROXY=none
ROUTE_DIR=
ENTRY=
STATIC=
UPSTREAM=
DOCKER=0
export DEBIAN_FRONTEND=noninteractive

say() { printf '%s\n' "$*"; }
warn() { printf 'warn: %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }
act() { if [ "$DRY" = 1 ]; then say "would: $*"; else "$@"; fi; }
need() { if [ "$DRY" = 1 ]; then warn "$1 (a real run stops here)"; else die "$1"; fi; }
listening() { have ss && ss -Htln | awk '{print $4}' | grep -Eq "[:.]$1\$"; }
running() { [ "$DOCKER" = 1 ] && docker ps --format '{{.Names}}' | grep -qx "$1"; }

ask() {
  if [ "$YES" = 1 ]; then return 0; fi
  if [ "$DRY" = 1 ]; then say "would ask: $1"; return 0; fi
  ( : </dev/tty ) 2>/dev/null || die "confirmation needed: $1 (re-run with --yes)"
  printf '%s [y/N] ' "$1" >/dev/tty
  read -r reply </dev/tty
  case $reply in y | Y | yes) return 0 ;; *) return 1 ;; esac
}

put() {
  if [ "$DRY" = 1 ]; then cat >/dev/null; say "would write $2"; return 0; fi
  tmp=$(mktemp "$2.XXXXXX")
  cat >"$tmp"
  chmod "$1" "$tmp"
  mv "$tmp" "$2"
}

line_in() {
  if grep -qsx "$2" "$1"; then return 0; fi
  if [ "$DRY" = 1 ]; then say "would add to $1: $2"; else printf '%s\n' "$2" >>"$1"; fi
}

mark() { line_in "$MARK" "$1"; }

addkey() {
  if grep -qs "^$1=" "$CONF/env"; then return 0; fi
  if [ "$DRY" = 1 ]; then say "would add $1 to $CONF/env"; return 0; fi
  if [ -s "$CONF/env" ] && [ -n "$(tail -c1 "$CONF/env")" ]; then echo >>"$CONF/env"; fi
  printf '%s=%s\n' "$1" "$2" >>"$CONF/env"
}

install_file() {
  if [ -f "$3" ] && cmp -s "$2" "$3"; then say "unchanged: $3"; return 0; fi
  if [ -f "$3" ]; then
    ask "Replace $3 (old copy kept as $3.bak)?" || return 0
    cp -p "$3" "$3.bak"
  fi
  install -m "$1" "$2" "$3"
  CHANGED=1
}

preflight() {
  [ "$(id -u)" = 0 ] || need "run as root (use sudo)"
  osr=${OS_RELEASE:-/etc/os-release}
  ID=$(sed -n 's/^ID=//p' "$osr" 2>/dev/null | tr -d '"')
  PRETTY_NAME=$(sed -n 's/^PRETTY_NAME=//p' "$osr" 2>/dev/null | tr -d '"')
  case $ID in
    debian | ubuntu) say "os: $PRETTY_NAME" ;;
    *) die "unsupported OS: ${PRETTY_NAME:-unknown}. This installer supports Debian and Ubuntu (apt) only." ;;
  esac
  [ -d /run/systemd/system ] || need "systemd is not running"
}

install_packages() {
  set --
  for p in python3 python3-cryptography nftables curl ca-certificates iproute2 procps; do
    dpkg -s "$p" >/dev/null 2>&1 || set -- "$@" "$p"
  done
  if [ "$F2B" = 1 ] && ! dpkg -s fail2ban >/dev/null 2>&1; then set -- "$@" fail2ban; fi
  if [ $# -eq 0 ]; then
    say "packages: all present"
  else
    act apt-get update -qq
    act apt-get install -y "$@"
  fi
  if [ ! -x /usr/lib/update-notifier/apt-check ]; then
    act apt-get install -y update-notifier-common || warn "no apt-check: the app's pending-updates count will fail"
  fi
}

detect() {
  if have docker && docker info >/dev/null 2>&1; then DOCKER=1; fi
  say "docker: $([ "$DOCKER" = 1 ] && echo yes || echo 'no (container screens stay empty)')"
  if [ -d /etc/dokploy/traefik/dynamic ]; then
    PROXY=traefik ROUTE_DIR=/etc/dokploy/traefik/dynamic ENTRY=websecure STATIC=/etc/dokploy/traefik/traefik.yml
    UPSTREAM=$(docker network inspect docker_gwbridge -f '{{(index .IPAM.Config 0).Gateway}}' 2>/dev/null || true)
    [ -n "$UPSTREAM" ] || UPSTREAM=172.17.0.1
    say "dokploy: yes (Traefik route, upstream $UPSTREAM)"
  elif [ -d /data/coolify/proxy/dynamic ] && running coolify-proxy \
    && docker inspect coolify-proxy -f '{{.Config.Image}}' | grep -q traefik; then
    PROXY=traefik ROUTE_DIR=/data/coolify/proxy/dynamic ENTRY=https STATIC=/data/coolify/proxy/docker-compose.yml UPSTREAM=host.docker.internal
    say "coolify: yes (Traefik route)"
  elif listening 80 || listening 443; then
    if systemctl is-active --quiet caddy 2>/dev/null; then PROXY=caddy; else PROXY=other; fi
    say "proxy: ports 80/443 in use ($PROXY)"
  else
    PROXY=caddy
    say "proxy: none, Caddy will be used with --domain"
  fi
}

write_env() {
  if [ -f "$CONF/env" ]; then say "kept: $CONF/env"; else act install -m 600 /dev/null "$CONF/env"; fi
  addkey HELM_HOOK_SECRET "$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
  # Traefik runs in a container: the agent must listen beyond loopback (guard.nft limits who reaches it).
  if [ "$PROXY" = traefik ]; then addkey HELM_BIND 0.0.0.0; fi
  if [ "$PROXY" = traefik ] && [ "$ROUTE_DIR" = /etc/dokploy/traefik/dynamic ]; then
    if [ -z "${HELM_DOKPLOY_KEY:-}" ] && [ "$YES" = 0 ] && [ "$DRY" = 0 ] && ( : </dev/tty ) 2>/dev/null; then
      printf 'Dokploy API key (Enter to skip, input hidden): ' >/dev/tty
      stty -echo </dev/tty
      read -r HELM_DOKPLOY_KEY </dev/tty || HELM_DOKPLOY_KEY=
      stty echo </dev/tty
      echo >/dev/tty
    fi
    if [ -n "${HELM_DOKPLOY_KEY:-}" ]; then
      if [ "$DRY" = 0 ] && ! printf 'header = "x-api-key: %s"\n' "$HELM_DOKPLOY_KEY" | curl -fsS -m 10 -o /dev/null -K - http://127.0.0.1:3000/api/project.all 2>/dev/null; then
        warn "Dokploy did not accept the API key at 127.0.0.1:3000 (stored anyway)"
      fi
      addkey HELM_DOKPLOY_KEY "$HELM_DOKPLOY_KEY"
    else
      warn "no HELM_DOKPLOY_KEY: Dokploy screens stay disabled. Add it to $CONF/env, then restart helm-agent."
    fi
  fi
}

setup_fail2ban() {
  if [ "$F2B" = 0 ]; then warn "fail2ban skipped: the app's bans and Shield screens will fail"; return 0; fi
  admins=$(ss -Htn state established '( sport = :22 )' 2>/dev/null | awk '{print $4}' \
    | sed -n -E 's/^\[?(::ffff:)?([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)\]?:[0-9]+$/\2/p' | sort -u | tr '\n' ' ' || true)
  if [ -f "$JAIL" ]; then
    say "kept: $JAIL"
    grep -q '^ignoreip *=' "$JAIL" || warn "$JAIL has no ignoreip line: the app cannot allow-list IPs"
  else
    mark jail_local=created
    put 644 "$JAIL" <<EOF
[DEFAULT]
ignoreip = 127.0.0.1/8 ::1 $admins
banaction = nftables
banaction_allports = nftables[type=allports]

[sshd]
enabled = true

[recidive]
enabled = true
bantime = -1
EOF
    [ -z "$admins" ] || say "ssh peers added to ignoreip: $admins"
  fi
  act systemctl enable fail2ban
  act systemctl restart fail2ban
}

setup_patching() {
  set --
  for p in unattended-upgrades needrestart; do dpkg -s "$p" >/dev/null 2>&1 || set -- "$@" "$p"; done
  [ $# -eq 0 ] || act apt-get install -y "$@"
  case $ID in
    debian) origins='"origin=Debian,codename=${distro_codename}-security,label=Debian-Security";' ;;
    *) origins='"origin=Ubuntu,archive=${distro_codename}-security"; "origin=UbuntuESMApps,archive=${distro_codename}-apps-security"; "origin=UbuntuESM,archive=${distro_codename}-infra-security";' ;;
  esac
  mark patching=1
  # #clear drops the distro's own origin list (Debian's includes stable point releases): security pockets only.
  put 644 /etc/apt/apt.conf.d/52helm-unattended <<EOF
#clear Unattended-Upgrade::Allowed-Origins;
#clear Unattended-Upgrade::Origins-Pattern;
Unattended-Upgrade::Origins-Pattern { $origins };
Unattended-Upgrade::Package-Blacklist { "^docker.io\$"; "^containerd\$"; "^runc\$"; };
Unattended-Upgrade::Automatic-Reboot "false";
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF
  act install -d -m 755 /etc/needrestart/conf.d
  printf "\$nrconf{restart} = 'a';\n" | put 644 /etc/needrestart/conf.d/50-helm.conf
  [ "$DRY" = 1 ] || unattended-upgrade --dry-run >/dev/null 2>&1 || warn "unattended-upgrade --dry-run failed: check /etc/apt/apt.conf.d/52helm-unattended"
  say "patching: security updates install daily, services restart, the server never reboots on its own"
}

setup_trivy() {
  if [ "$TRIVY" = 0 ]; then say "trivy: skipped (--no-trivy), the app's vulnerability audit stays off"; return 0; fi
  if have trivy; then say "trivy: already installed, left as is"; return 0; fi
  dpkg -s gnupg >/dev/null 2>&1 || act apt-get install -y gnupg
  if [ "$DRY" = 1 ]; then say "would add the Trivy apt repo after checking key $TRIVY_KEY, install and hold trivy"; return 0; fi
  TMP=${TMP:-$(mktemp -d)}
  curl -fsSL --proto '=https' --tlsv1.2 -o "$TMP/trivy.asc" https://get.trivy.dev/deb/public.key || { warn "trivy: key download failed, audit stays off"; return 0; }
  keys=$(gpg --show-keys --with-colons "$TMP/trivy.asc" 2>/dev/null | awk -F: '$1 == "pub" {n++} $1 == "fpr" && !f {f = $10} END {print n " " f}')
  if [ "$keys" != "1 $TRIVY_KEY" ]; then warn "trivy: signing key is not $TRIVY_KEY, nothing installed"; return 0; fi
  gpg --dearmor <"$TMP/trivy.asc" | put 644 /usr/share/keyrings/trivy.gpg
  printf 'deb [signed-by=/usr/share/keyrings/trivy.gpg] https://get.trivy.dev/deb generic main\n' | put 644 /etc/apt/sources.list.d/trivy.list
  mark pkg_trivy=1
  act apt-get update -qq
  act apt-get install -y "trivy${TRIVY_VERSION:+=$TRIVY_VERSION}"
  act apt-mark hold trivy
  say "trivy: $(trivy --version 2>/dev/null | sed -n 's/^Version: //p'), held (upgrade it deliberately: apt-mark unhold trivy)"
}

setup_crowdsec() {
  if have cscli; then say "crowdsec: already installed"; return 0; fi
  if [ "$CS" = 0 ] || [ "$YES" = 1 ]; then say "crowdsec: skipped (optional, asked only in interactive runs)"; return 0; fi
  ask "Install CrowdSec from the distro repository (optional)?" || return 0
  mark pkg_crowdsec=1
  act apt-get install -y crowdsec
  say "crowdsec: add a firewall bouncer to enforce its decisions"
}

check_dns() {
  mine=$(ip -4 route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p' || true)
  theirs=$(getent ahostsv4 "$DOMAIN" 2>/dev/null | awk '{print $1; exit}' || true)
  [ -n "$theirs" ] || warn "$DOMAIN does not resolve yet: point its A record to this server"
  [ -z "$theirs" ] || [ "$theirs" = "$mine" ] || warn "$DOMAIN resolves to $theirs, this server is ${mine:-unknown} (fine behind Cloudflare)"
}

expose_traefik() {
  f=$ROUTE_DIR/helm-agent.yml
  if [ -f "$f" ]; then say "kept: $f"; return 0; fi
  mark "route=$f"
  put 644 "$f" <<EOF
http:
  routers:
    helm-agent:
      rule: Host(\`$DOMAIN\`) && PathPrefix(\`/helm\`)
      entryPoints: [$ENTRY]
      middlewares: [helm-agent-strip]
      service: helm-agent
      tls:
        certResolver: letsencrypt
  middlewares:
    helm-agent-strip:
      stripPrefix:
        prefixes: [/helm]
  services:
    helm-agent:
      loadBalancer:
        servers:
          - url: http://$UPSTREAM:$PORT
EOF
  grep -qs letsencrypt "$STATIC" || warn "no letsencrypt resolver found in $STATIC: check certResolver in $f"
}

expose_caddy() {
  if ! dpkg -s caddy >/dev/null 2>&1; then
    mark pkg_caddy=1
    act apt-get install -y caddy
  fi
  mark caddy_conf=1
  put 644 /etc/caddy/helm-agent.caddy <<EOF
$DOMAIN {
	handle_path /helm/* {
		reverse_proxy 127.0.0.1:$PORT {
			header_up X-Real-Ip {remote_host}
		}
	}
}
EOF
  line_in /etc/caddy/Caddyfile "import /etc/caddy/helm-agent.caddy"
  act caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
  act systemctl enable caddy
  act systemctl reload-or-restart caddy
}

expose() {
  if [ -z "$DOMAIN" ]; then
    say "expose: agent listens on 127.0.0.1:$PORT only, the app needs https"
    say "  re-run with --domain <name> once its A record points here"
    return 0
  fi
  check_dns
  case $PROXY in
    traefik) expose_traefik ;;
    caddy) expose_caddy ;;
    *)
      warn "ports 80/443 belong to another service. Route https://$DOMAIN/helm to 127.0.0.1:$PORT,"
      warn "strip the /helm prefix and send the client address in X-Real-Ip."
      ;;
  esac
}

install_agent() {
  if [ "$DRY" = 1 ]; then
    say "would download $HELM_URL, verify SHA-256, install $BIN and $UNIT"
    printf %s "$HELM_SHA256" | grep -Eq '^[0-9a-f]{64}$' || warn "HELM_SHA256 not set: a real run stops until the release is published"
    return 0
  fi
  printf %s "$HELM_SHA256" | grep -Eq '^[0-9a-f]{64}$' || die "release not published: set HELM_URL and HELM_SHA256 at the top of install.sh"
  TMP=$(mktemp -d)
  curl -fsSL --proto '=https' --tlsv1.2 -o "$TMP/agent.tgz" "$HELM_URL"
  printf '%s  %s\n' "$HELM_SHA256" "$TMP/agent.tgz" | sha256sum -c - >/dev/null || die "SHA-256 mismatch, nothing installed"
  tar -xzf "$TMP/agent.tgz" --no-same-owner -C "$TMP" helm_agent.py helm-agent.service
  python3 -I -c 'import ast, sys; ast.parse(open(sys.argv[1]).read()); import cryptography' "$TMP/helm_agent.py" \
    || die "agent file is not valid Python or python3-cryptography is missing"
  install_file 755 "$TMP/helm_agent.py" "$BIN"
  install_file 644 "$TMP/helm-agent.service" "$UNIT"
}

start_service() {
  say "ssh: country lock off. HELM_SSH_ALLOW_COUNTRIES in $CONF/env turns it on (see README)."
  ask "Start the agent now?" || die "cancelled before start"
  if [ -f "$CONF/guard.nft" ]; then say "kept: $CONF/guard.nft"; else put 600 "$CONF/guard.nft" <<EOF
table inet helm-guard
delete table inet helm-guard
table inet helm-guard {
  chain input {
    type filter hook input priority filter - 4; policy accept;
    tcp dport $PORT iifname "lo" accept
    tcp dport $PORT iifname "docker*" accept
    tcp dport $PORT iifname "br-*" accept
    tcp dport $PORT drop
  }
}
EOF
  fi
  [ "$DRY" = 1 ] || nft -c -f "$CONF/guard.nft" || die "guard.nft rejected by nft"
  act systemctl daemon-reload
  act systemctl enable helm-agent
  if [ "$CHANGED" = 1 ]; then act systemctl restart helm-agent; else act systemctl start helm-agent; fi
}

report() {
  if [ "$DRY" = 1 ]; then say "would wait for /health, then print the pairing box"; return 0; fi
  health=
  for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
    health=$(curl -fsS -m 2 "http://127.0.0.1:$PORT/health" 2>/dev/null) && break
    health=
    sleep 1
  done
  [ -n "$health" ] || die "agent did not answer on 127.0.0.1:$PORT. See: journalctl -u helm-agent -n 50"
  code=$(cat "$CONF/pair-code" 2>/dev/null || true)
  case $health in *'"claimed": true'*) code= ;; esac
  addr="none yet (re-run with --domain)"
  if [ -n "$DOMAIN" ]; then
    addr=https://$DOMAIN/helm
    for _ in 1 2 3 4 5 6; do curl -fsS -m 5 -o /dev/null "$addr/health" 2>/dev/null && break; sleep 5; done
    curl -fsS -m 5 -o /dev/null "$addr/health" 2>/dev/null || warn "$addr not reachable yet (certificate may still be issuing)"
  fi
  say "========================================"
  say " Helm agent $HELM_VERSION is running"
  say " App address : $addr"
  say " Pairing code: ${code:-none, already claimed (approve phones from the owner phone)}"
  [ -z "$code" ] || say "               valid 30 minutes, then: sudo cat $CONF/pair-code"
  say " App (APK)   : $APK_URL"
  say "========================================"
}

uninstall() {
  ask "Remove the Helm agent and its files from this server?" || die "cancelled"
  act systemctl disable --now helm-agent || true
  act systemctl stop helm-quiet-reboot.timer helm-quiet-reboot.service || true
  bk=/var/backups/helm-agent-$(date +%Y%m%d-%H%M%S)
  if [ -d "$DATA" ] || [ -d "$CONF" ]; then
    act mkdir -m 700 -p "$bk"
    [ ! -d "$DATA" ] || act cp -a "$DATA" "$bk/state"
    [ ! -d "$CONF" ] || act cp -a "$CONF" "$bk/etc"
    say "backup (contains secrets, root only): $bk"
  fi
  if have nft; then
    for t in helm-agent helm-guard helm-geo helm-ssh helm-panic; do act nft delete table inet "$t" 2>/dev/null || true; done
  fi
  route=$(sed -n 's/^route=//p' "$MARK" 2>/dev/null || true)
  [ -z "$route" ] || ! ask "Remove Traefik route $route?" || act rm -f "$route"
  if grep -qsx caddy_conf=1 "$MARK"; then
    act sed -i '\#^import /etc/caddy/helm-agent.caddy$#d' /etc/caddy/Caddyfile
    act rm -f /etc/caddy/helm-agent.caddy
    act systemctl reload-or-restart caddy || true
  fi
  if grep -qsx jail_local=created "$MARK" && ask "Remove $JAIL created by this installer (copy kept in backup)?"; then
    [ ! -f "$JAIL" ] || act cp -a "$JAIL" "$bk/jail.local"
    act rm -f "$JAIL"
    act systemctl restart fail2ban || true
  fi
  if grep -qsx patching=1 "$MARK"; then act rm -f /etc/apt/apt.conf.d/52helm-unattended /etc/needrestart/conf.d/50-helm.conf; fi
  if grep -qsx pkg_trivy=1 "$MARK"; then
    act apt-mark unhold trivy || true
    act rm -f /etc/apt/sources.list.d/trivy.list /usr/share/keyrings/trivy.gpg
  fi
  for p in caddy crowdsec trivy; do
    if grep -qsx "pkg_$p=1" "$MARK" && ask "Purge package $p installed by this installer?"; then act apt-get purge -y "$p"; fi
  done
  act rm -f "$UNIT" "$BIN" "$BIN.bak" "$UNIT.bak"
  act rm -rf "$CONF" "$DATA"
  act systemctl daemon-reload
  say "removed. Other packages (python3, fail2ban, nftables...) were left in place."
}

while [ $# -gt 0 ]; do
  case $1 in
    --dry-run) DRY=1 ;;
    --uninstall) UNINSTALL=1 ;;
    --yes) YES=1 ;;
    --no-fail2ban) F2B=0 ;;
    --no-crowdsec) CS=0 ;;
    --no-trivy) TRIVY=0 ;;
    --domain)
      [ $# -ge 2 ] || die "--domain needs a value"
      DOMAIN=$(printf %s "$2" | tr '[:upper:]' '[:lower:]')
      shift
      ;;
    *) die "unknown option: $1" ;;
  esac
  shift
done
[ -z "$DOMAIN" ] || printf %s "$DOMAIN" | grep -Eq '^([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}$' || die "invalid domain: $DOMAIN"
trap 'rm -rf "$TMP"' EXIT

preflight
if [ "$UNINSTALL" = 1 ]; then uninstall; exit 0; fi
install_packages
detect
act install -d -m 700 "$CONF" "$DATA"
install_agent
write_env
[ -f "$DATA/reboot.json" ] || printf '{"autoReboot": false}\n' | put 600 "$DATA/reboot.json"
setup_fail2ban
setup_crowdsec
setup_patching
setup_trivy
expose
start_service
report
