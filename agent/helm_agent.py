#!/usr/bin/env python3
import base64, bisect, difflib, fcntl, hashlib, hmac, ipaddress, json, math, os, queue, re, secrets, shutil, socket, sqlite3, ssl, subprocess, sys, threading, time, urllib.request
from collections import deque
from datetime import date, datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlencode, urlparse
from zoneinfo import ZoneInfo

from cryptography import x509
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

def env_list(name):
    return os.environ.get(name, "").replace(",", " ").split()


VERSION = "3"
BIND = os.environ.get("HELM_BIND") or "127.0.0.1"
NO_LOOKUPS = os.environ.get("HELM_NO_EXTERNAL_LOOKUPS") == "1"
COOLIFY = os.environ.get("HELM_COOLIFY_BASE") or "http://127.0.0.1:8000/api/v1/"
STATE = "/var/lib/helm-agent"
CODE_FILE = "/etc/helm-agent/pair-code"
F2B_DB = "/var/lib/fail2ban/fail2ban.sqlite3"
JAIL_LOCAL = "/etc/fail2ban/jail.local"
DEPLOY_LOGS = "/etc/dokploy/logs/"
TRAEFIK_DYNAMIC = "/etc/dokploy/traefik/dynamic"
STALWART_ENV = os.environ.get("HELM_STALWART_ENV", "")
STALWART_BAN_SYNC = "/usr/local/sbin/stalwart-ban-sync"
DOKPLOY = "http://127.0.0.1:3000/api/"
PERIMETER = "/etc/perimeter"
PERIMETER_BAN = "/usr/local/sbin/perimeter-ban"
PERIMETER_UNBAN = "/usr/local/sbin/perimeter-unban"
BAN_FALLBACK = {"24h": ("sshd", "24h"), "permanent": ("recidive", "876000h")}
JAIL_SOURCE = {"sshd": "ssh", "traefik-scan": "web", "identity-abuse": "login", "http-404-flood": "web", "recidive": "repeat"}
CONTAINER_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")
CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
CLOCK_SKEW = 60
CODE_TTL = 1800
MAX_CODE_FAILURES = 5
PROXY_GET = {"project.all", "project.one", "application.one", "compose.one", "postgres.one", "redis.one", "deployment.all",
             "deployment.allByCompose", "deployment.allCentralized", "domain.byApplicationId", "domain.byComposeId",
             "docker.getServerHealth", "docker.getContainers", "destination.all", "backup.listBackupFiles",
             "settings.getDokployVersion", "notification.all", "schedule.list", "schedule.one"}
PROXY_POST = {"application.redeploy", "application.start", "application.stop", "application.reload", "compose.redeploy",
              "compose.start", "compose.stop", "postgres.start", "postgres.stop", "postgres.reload", "docker.restartContainer",
              "application.saveEnvironment", "compose.saveEnvironment", "backup.manualBackupPostgres", "settings.cleanAll",
              "settings.reloadTraefik", "application.deploy", "compose.deploy", "application.update", "compose.update",
              "destination.create", "destination.testConnection", "destination.update", "backup.create", "backup.update",
              "backup.remove", "backup.manualBackupCompose", "rollback.rollback", "rollback.delete", "schedule.runManually",
              "application.cancelDeployment", "compose.cancelDeployment"}
PROXY_OWNER = {"application.update", "compose.update", "destination.create", "destination.testConnection", "destination.update",
               "backup.create", "backup.update", "backup.remove", "settings.cleanAll", "application.saveEnvironment", "compose.saveEnvironment",
               "rollback.rollback", "rollback.delete", "schedule.runManually"}
PROXY_FIELDS = {"application.update": {"applicationId", "branch", "replicas", "memoryLimit", "memoryReservation", "cpuLimit", "cpuReservation"},
                "compose.update": {"composeId", "branch"}}
READ_ONLY_SQL = re.compile(r"^\s*(select|with|show|explain|table|values)\b", re.I)
ACCESS_LOGS = ("/var/log/traefik/access.log", "/etc/dokploy/traefik/dynamic/access.log")
AGENT_FIREWALL = "/etc/helm-agent/firewall.nft"
CLOUDFLARE = ("173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20",
              "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
              "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32", "2405:8100::/32", "2a06:98c0::/29", "2c0f:f248::/32")
PRIVATE4 = ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10"]

lock = threading.RLock()
slots = threading.BoundedSemaphore(64)
subscribers: list = []
history: deque = deque(maxlen=300)
nonces: dict = {}
buckets: dict = {}
cache: dict = {}
code_failures = 0
cpu_percent = 0.0
net_rate = {"rx": 0.0, "tx": 0.0}
mem_history: deque = deque(maxlen=96)
disk_history: deque = deque(maxlen=720)
countries: dict = {}


def db():
    con = sqlite3.connect(f"{STATE}/helm.db", timeout=10, isolation_level=None)
    con.row_factory = sqlite3.Row
    return con


def init_db():
    os.makedirs(STATE, mode=0o700, exist_ok=True)
    with db() as con:
        con.executescript("""
        PRAGMA journal_mode=WAL;
        CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, name TEXT, role TEXT, pubkey TEXT UNIQUE, added_at INT, last_seen_at INT, last_ip TEXT, revoked INT DEFAULT 0);
        CREATE TABLE IF NOT EXISTS pair_requests(id TEXT PRIMARY KEY, pubkey TEXT, name TEXT, ip TEXT, requested_at INT, status TEXT, device_id TEXT);
        CREATE TABLE IF NOT EXISTS audit(at INT, device TEXT, action TEXT, detail TEXT, ip TEXT);
        CREATE TABLE IF NOT EXISTS uptime_days(day TEXT, service TEXT, up INT, total INT, PRIMARY KEY(day, service));
        CREATE TABLE IF NOT EXISTS incidents(service TEXT, start INT, end INT, reason TEXT);
        CREATE TABLE IF NOT EXISTS services(name TEXT PRIMARY KEY, kind TEXT, target TEXT, state TEXT, latency INT, cert_expires INT);
        CREATE TABLE IF NOT EXISTS notices(key TEXT PRIMARY KEY, at INT);
        CREATE TABLE IF NOT EXISTS config_versions(path TEXT, at INT, sha256 TEXT, content TEXT);
        CREATE INDEX IF NOT EXISTS config_versions_path ON config_versions(path);
        CREATE TABLE IF NOT EXISTS container_samples(at INT, name TEXT, cpu REAL, mem INT);
        CREATE INDEX IF NOT EXISTS container_samples_at ON container_samples(at);
        CREATE TABLE IF NOT EXISTS env_versions(service_id TEXT, kind TEXT, at INT, sha TEXT, content TEXT);
        CREATE INDEX IF NOT EXISTS env_versions_service ON env_versions(service_id, kind);
        CREATE TABLE IF NOT EXISTS notes(service_id TEXT PRIMARY KEY, text TEXT, author TEXT, at INT);
        CREATE TABLE IF NOT EXISTS secret_findings(container TEXT, pattern TEXT, sample TEXT, first_seen INT, last_seen INT, count INT, PRIMARY KEY(container, pattern));
        CREATE TABLE IF NOT EXISTS traffic_slots(day TEXT, slot INT, real INT, total INT, PRIMARY KEY(day, slot));
        CREATE TABLE IF NOT EXISTS probe_hits(day TEXT, category TEXT, path TEXT, status INT, country TEXT, count INT, PRIMARY KEY(day, category, path, status, country));
        CREATE TABLE IF NOT EXISTS probe_ips(day TEXT, category TEXT, ip TEXT, PRIMARY KEY(day, category, ip));
        CREATE TABLE IF NOT EXISTS probe_exposed(host TEXT, path TEXT, category TEXT, status INT, bytes INT, at INT, PRIMARY KEY(host, path));
        """)


def audit(device, action, detail, ip):
    with db() as con:
        con.execute("INSERT INTO audit VALUES(?,?,?,?,?)", (int(time.time()), device, action, str(detail)[:300], ip))


def load_json(name, default):
    try:
        with open(f"{STATE}/{name}") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def save_json(name, data):
    tmp = f"{STATE}/{name}.tmp"
    with open(tmp, "w") as f:
        json.dump(data, f)
    os.replace(tmp, f"{STATE}/{name}")


def publish(event_type, data, owners_only=False):
    data = {"type": event_type, "id": data.get("id") or f"{event_type}-{time.time_ns()}", "at": int(time.time()), **data}
    with lock:
        if not owners_only:
            history.append(data)
            save_json("events.json", list(history))
        for q, role in subscribers:
            if role == "owner" or not owners_only:
                q.put(data)


def notice_once(key, every, event_type, data):
    now = int(time.time())
    with db() as con:
        row = con.execute("SELECT at FROM notices WHERE key=?", (key,)).fetchone()
        if row and now - row["at"] < every:
            return
        con.execute("INSERT OR REPLACE INTO notices VALUES(?,?)", (key, now))
    publish(event_type, data)


def run(*cmd, timeout=20):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout).stdout
    except FileNotFoundError:
        return ""


refreshing: set = set()


def cached(key, ttl, fn):
    hit = cache.get(key)
    if hit is None:
        value = fn()
        cache[key] = (time.time(), value)
        return value
    if time.time() - hit[0] >= ttl:
        with lock:
            start = key not in refreshing
            refreshing.add(key)
        if start:
            def refresh():
                try:
                    cache[key] = (time.time(), fn())
                except Exception as e:
                    print(f"refresh {key}: {e}", file=sys.stderr)
                finally:
                    refreshing.discard(key)
            threading.Thread(target=refresh, daemon=True).start()
    return hit[1]


def warmer():
    while True:
        for key, ttl, fn in (("shield", 10, shield), ("containers", 10, containers), ("host", 30, host), ("logins", 30, logins), ("geo", 30, geo_overview),
                             ("proxy:project.all:", 10, projects_with_app_names), ("web", 120, web_stats), ("updates_center", 600, updates_center),
                             ("certs", 3600, certs), ("mail", 300, mail_health), ("dns", 300, dns_check),
                             ("networks", 60, networks), ("probes:7", 300, lambda: probes(7))):
            try:
                cached(key, ttl, fn)
            except Exception as e:
                print(f"warm {key}: {e}", file=sys.stderr)
        time.sleep(8)


def is_claimed():
    with db() as con:
        return con.execute("SELECT 1 FROM devices WHERE role='owner' AND revoked=0").fetchone() is not None


def rotate_code():
    global code_failures
    code_failures = 0
    code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(10))
    fd = os.open(CODE_FILE + ".tmp", os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(f"{code}\n")
    os.replace(CODE_FILE + ".tmp", CODE_FILE)


def current_code():
    try:
        if time.time() - os.path.getmtime(CODE_FILE) > CODE_TTL:
            rotate_code()
        with open(CODE_FILE) as f:
            return f.read().strip()
    except OSError:
        rotate_code()
        return current_code()


def parse_pubkey(b64):
    der = base64.b64decode(b64, validate=True)
    key = serialization.load_der_public_key(der)
    if not isinstance(key, ec.EllipticCurvePublicKey) or key.curve.name != "secp256r1":
        raise ValueError("Only P-256 device keys are accepted")
    return base64.b64encode(der).decode()


def fingerprint(pubkey_b64):
    digest = hashlib.sha256(base64.b64decode(pubkey_b64)).hexdigest()[:10].upper()
    return f"{digest[:5]}-{digest[5:]}"


def rate_ok(key, per_minute):
    now = time.time()
    with lock:
        tokens, last = buckets.get(key, (per_minute, now))
        tokens = min(per_minute, tokens + (now - last) * per_minute / 60)
        if tokens < 1:
            buckets[key] = (tokens, now)
            return False
        buckets[key] = (tokens - 1, now)
        return True


def meminfo():
    info = {}
    with open("/proc/meminfo") as f:
        for line in f:
            key, value = line.split(":", 1)
            info[key] = int(value.split()[0]) * 1024
    return info["MemTotal"], info["MemTotal"] - info["MemAvailable"]


def net_bytes():
    rx = tx = 0
    with open("/proc/net/dev") as f:
        for line in f.readlines()[2:]:
            name, data = line.split(":", 1)
            if name.strip() == "lo" or name.strip().startswith(("veth", "docker", "br-")):
                continue
            fields = data.split()
            rx, tx = rx + int(fields[0]), tx + int(fields[8])
    return rx, tx


def sampler():
    global cpu_percent
    def cpu():
        with open("/proc/stat") as f:
            values = list(map(int, f.readline().split()[1:]))
        return sum(values), values[3] + values[4]
    last_total, last_idle = cpu()
    last_rx, last_tx = net_bytes()
    last_mem_point = 0
    while True:
        time.sleep(5)
        total, idle = cpu()
        if total > last_total:
            cpu_percent = round(100 * (1 - (idle - last_idle) / (total - last_total)), 1)
        last_total, last_idle = total, idle
        rx, tx = net_bytes()
        net_rate.update(rx=(rx - last_rx) / 5, tx=(tx - last_tx) / 5)
        last_rx, last_tx = rx, tx
        if time.time() - last_mem_point >= 900:
            last_mem_point = time.time()
            mem_total, mem_used = meminfo()
            with lock:
                mem_history.append({"t": int(last_mem_point), "usedPercent": round(100 * mem_used / mem_total, 1)})
                save_json("mem.json", list(mem_history))
            disk = os.statvfs("/")
            used = 100 * (1 - disk.f_bavail / disk.f_blocks)
            if not disk_history or last_mem_point - disk_history[-1]["t"] >= 3600:
                with lock:
                    disk_history.append({"t": int(last_mem_point), "usedBytes": (disk.f_blocks - disk.f_bavail) * disk.f_frsize})
                    save_json("disk.json", list(disk_history))
            if used > 90:
                notice_once("disk_high", 86400, "system", {**fr(f"Disque plein à {used:.0f}\u202f%", "Nettoyez Docker depuis Maintenance ou agrandissez le disque."), "kind": "disk_high", "title": f"Disk {used:.0f}% full", "message": "Clean Docker from Maintenance or grow the disk."})


def disk_full_in_days(total_bytes):
    points = [p for p in disk_history if p["t"] > time.time() - 7 * 86400]
    if len(points) < 12 or points[-1]["t"] - points[0]["t"] < 86400:
        return None
    n = len(points)
    mean_t = sum(p["t"] for p in points) / n
    mean_u = sum(p["usedBytes"] for p in points) / n
    slope = sum((p["t"] - mean_t) * (p["usedBytes"] - mean_u) for p in points) / sum((p["t"] - mean_t) ** 2 for p in points)
    if slope <= 0:
        return None
    return round((total_bytes - points[-1]["usedBytes"]) / slope / 86400, 1)


def stats():
    mem_total, mem_used = meminfo()
    disk = os.statvfs("/")
    with open("/proc/uptime") as f:
        uptime = int(float(f.read().split()[0]))
    return {"cpuPercent": cpu_percent, "cpuCount": os.cpu_count(), "memUsedBytes": mem_used, "memTotalBytes": mem_total,
            "diskUsedBytes": (disk.f_blocks - disk.f_bfree) * disk.f_frsize, "diskTotalBytes": disk.f_blocks * disk.f_frsize,
            "uptimeSeconds": uptime, "hostname": os.uname().nodename, "memHistory": list(mem_history),
            "diskHistory": list(disk_history)[-168:], "diskFullInDays": disk_full_in_days(disk.f_blocks * disk.f_frsize)}


def size_bytes(text):
    m = re.match(r"([\d.]+)\s*([kKMGT]?)(i?)B", text.strip())
    if not m:
        return 0
    power = " KMGT".index(m.group(2).upper() or " ")
    return int(float(m.group(1)) * (1024 if m.group(3) else 1000) ** power)


def inspect_all():
    ids = run("docker", "ps", "-aq").split()
    return json.loads(run("docker", "inspect", *ids, timeout=60)) if ids else []


def containers():
    by_name = {c["Name"].lstrip("/"): c for c in inspect_all()}
    recent = memory_history()
    rows = []
    for line in run("docker", "stats", "--no-stream", "--format", "{{json .}}", timeout=40).splitlines():
        s = json.loads(line)
        c = by_name.get(s["Name"], {})
        labels = c.get("Config", {}).get("Labels") or {}
        mem_used, _, mem_limit = s["MemUsage"].partition("/")
        net_rx, _, net_tx = s["NetIO"].partition("/")
        rows.append({"name": s["Name"], "service": labels.get("com.docker.swarm.service.name") or labels.get("com.docker.compose.service") or s["Name"],
                     "image": c.get("Config", {}).get("Image", ""), "state": c.get("State", {}).get("Status", ""),
                     "health": (c.get("State", {}).get("Health") or {}).get("Status"), "cpuPercent": float(s["CPUPerc"].rstrip("%") or 0),
                     "memBytes": size_bytes(mem_used), "memLimitBytes": size_bytes(mem_limit), "netRxBytes": size_bytes(net_rx),
                     "netTxBytes": size_bytes(net_tx), "restarts": c.get("RestartCount", 0), "startedAt": c.get("State", {}).get("StartedAt"),
                     "appName": labels.get("com.docker.swarm.service.name") or labels.get("com.docker.compose.project"), "history": recent.get(task_name(s["Name"]), [])})
    return sorted(rows, key=lambda r: -r["memBytes"])


def top_consumers():
    names = {}
    for line in run("docker", "ps", "--no-trunc", "--format", "{{.ID}} {{.Names}}").splitlines():
        cid, _, name = line.partition(" ")
        names[cid] = task_name(name)
    groups = {}
    lines = run("top", "-b", "-n", "2", "-d", "0.5", "-w", "512").splitlines()
    second = max(i for i, l in enumerate(lines) if l.lstrip().startswith("PID"))
    for line in lines[second + 1:]:
        parts = line.split(None, 11)
        if len(parts) < 12 or parts[11].split()[0] in ("top", "ps", "docker", "python3"):
            continue
        pid, cpu, res = parts[0], float(parts[8].replace(",", ".")), parts[5]
        mem = int(float(res[:-1]) * {"m": 2**20, "g": 2**30, "t": 2**40}[res[-1]]) if res[-1] in "mgt" else int(res) * 1024
        owner = parts[11].split()[0]
        try:
            with open(f"/proc/{pid}/cgroup") as f:
                m = re.search(r"([0-9a-f]{64})", f.read())
            if m and m.group(1) in names:
                owner = names[m.group(1)]
            else:
                with open(f"/proc/{pid}/cmdline", "rb") as f:
                    argv = [x.decode(errors="replace") for x in f.read().split(b"\0") if x]
                if argv:
                    script = next((x for x in argv[1:] if not x.startswith("-") and "/" in x), "")
                    owner = " ".join(filter(None, [os.path.basename(argv[0]), os.path.basename(script)]))[:40]
        except OSError:
            continue
        g = groups.setdefault(owner, {"name": owner, "cpuPercent": 0.0, "memBytes": 0})
        g["cpuPercent"] += cpu / (os.cpu_count() or 1)
        g["memBytes"] += mem
    rows = sorted(groups.values(), key=lambda g: (-g["cpuPercent"], -g["memBytes"]))[:8]
    for r in rows:
        r["cpuPercent"] = round(r["cpuPercent"], 1)
    return rows


def host():
    with open("/proc/loadavg") as f:
        load = [float(x) for x in f.read().split()[:3]]
    top = cached("top", 20, top_consumers)
    def updates():
        if not os.path.exists("/usr/lib/update-notifier/apt-check"):
            pending = [p for p in apt_pending()[0] if p["group"] != "held"]
            return len(pending), sum(1 for p in pending if p["group"] == "security")
        out = subprocess.run(["/usr/lib/update-notifier/apt-check"], capture_output=True, text=True, timeout=60).stderr
        total, _, security = out.strip().partition(";")
        return int(total or 0), int(security or 0)
    pending, security = cached("updates", 3600, updates)
    failed_ssh = cached("ssh24", 300, lambda: sum(1 for l in run("journalctl", "-u", "ssh", "--since", "-24h", "--no-pager", "-q", timeout=60).splitlines()
                                                  if re.search(r"Invalid user|Failed password|authentication failure", l)))
    def docker_disk():
        out = {}
        for line in run("docker", "system", "df", "--format", "{{json .}}", timeout=120).splitlines():
            row = json.loads(line)
            out[{"Images": "imagesBytes", "Containers": "containersBytes", "Local Volumes": "volumesBytes", "Build Cache": "buildCacheBytes"}.get(row["Type"], row["Type"])] = size_bytes(row["Size"])
        return out
    return {"load": load, "netRxBytesPerSec": int(net_rate["rx"]), "netTxBytesPerSec": int(net_rate["tx"]), "topProcesses": top,
            "pendingUpdates": pending, "securityUpdates": security, "rebootRequired": os.path.exists("/var/run/reboot-required"),
            "failedSshLogins24h": failed_ssh, "dockerDisk": cached("dockerdf", 600, docker_disk)}


GEO_RANGES: list = []
GEO_STARTS: list = []
CONTINENTS = ("Africa", "Asia", "Europe", "North America", "South America", "Oceania")
NEVER_BLOCK = {c.upper() for c in env_list("HELM_SSH_ALLOW_COUNTRIES") if re.fullmatch(r"[A-Za-z]{2}", c)}


def countries_meta():
    return cached("countries_meta", 86400, lambda: {c["code"]: c for c in load_json("countries.json", [])})


def zone_path(cc):
    return f"{STATE}/zones/{cc.lower()}.zone"


def geo_refresh():
    import bisect, tarfile, io
    if NO_LOOKUPS:
        return
    raw =urllib.request.urlopen("https://www.ipdeny.com/ipblocks/data/countries/all-zones.tar.gz", timeout=120).read()
    os.makedirs(f"{STATE}/zones", exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(raw)) as tar:
        for member in tar.getmembers():
            name = os.path.basename(member.name)
            if member.isfile() and re.fullmatch(r"[a-z]{2}\.zone", name):
                with open(zone_path(name[:2]), "wb") as out:
                    out.write(tar.extractfile(member).read())
    load_geo()


def load_geo():
    import bisect
    ranges = []
    try:
        names = os.listdir(f"{STATE}/zones")
    except OSError:
        return
    for name in names:
        cc = name[:2].upper()
        with open(f"{STATE}/zones/{name}") as f:
            for line in f:
                try:
                    net = ipaddress.ip_network(line.strip())
                except ValueError:
                    continue
                ranges.append((int(net.network_address), int(net.broadcast_address), cc))
    ranges.sort()
    GEO_RANGES[:] = ranges
    GEO_STARTS[:] = [r[0] for r in ranges]


def local_country(ip):
    import bisect
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return ""
    if addr.version != 4 or not GEO_STARTS:
        return ""
    i = bisect.bisect_right(GEO_STARTS, int(addr)) - 1
    if i >= 0 and GEO_RANGES[i][1] >= int(addr):
        return GEO_RANGES[i][2]
    return ""


def country(ip):
    found = local_country(ip)
    if found or NO_LOOKUPS:
        return found
    if ip not in countries:
        try:
            with urllib.request.urlopen(f"https://ipinfo.io/{ip}/country", timeout=4) as r:
                countries[ip] = r.read().decode().strip()[:2]
        except OSError:
            return ""
    return countries[ip]


def f2b_bans(limit=None, ip=None):
    if not os.path.exists(F2B_DB):
        return []
    con =sqlite3.connect(f"file:{F2B_DB}?mode=ro", uri=True, timeout=5)
    sql, args = "SELECT jail, ip, timeofban, data FROM bans", ()
    if ip:
        sql, args = sql + " WHERE ip = ?", (ip,)
    sql += " ORDER BY timeofban DESC" + (f" LIMIT {int(limit)}" if limit else "")
    rows = con.execute(sql, args).fetchall()
    con.close()
    bans = []
    for jail, row_ip, at, data in rows:
        try:
            details = json.loads(data or "{}")
        except ValueError:
            details = {}
        source = JAIL_SOURCE.get(jail.removesuffix("-sn"), jail)
        reason = f"{details.get('failures', 1)} SSH attempts" if source == "ssh" else "Probed a known attack path"
        reason_fr = f"{details.get('failures', 1)} tentatives SSH" if source == "ssh" else "A sondé un chemin d’attaque connu"
        bans.append({"ip": row_ip, "jail": jail, "source": source, "reason": reason, "reasonFr": reason_fr, "bannedAt": int(at),
                     "evidence": [" ".join(m) if isinstance(m, list) else str(m) for m in details.get("matches", [])][-10:]})
    return bans


def stalwart_call(method, args):
    with open(STALWART_ENV) as f:
        key = re.search(r'^\w*STALWART_ADMIN_KEY="?([^"\n]+)', f.read(), re.M).group(1)
    body = json.dumps({"using": ["urn:ietf:params:jmap:core", "urn:stalwart:jmap"], "methodCalls": [[method, {"accountId": "c", **args}, "0"]]}).encode()
    req = urllib.request.Request("http://127.0.0.1:18080/jmap/", body, {"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.load(r)["methodResponses"][0][1]


def stalwart_bans():
    try:
        rows = stalwart_call("x:BlockedIp/get", {"ids": None}).get("list", [])
    except (OSError, ValueError, AttributeError):
        return []
    bans = []
    for b in rows:
        try:
            if not ipaddress.ip_network(b["address"], strict=False).is_global:
                continue
            at = datetime.strptime(b["createdAt"], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc).timestamp()
        except (ValueError, KeyError):
            continue
        reason = {"portScanning": "Port scan on mail"}.get(b.get("reason"), "Failed mail logins")
        reason_fr = {"portScanning": "Scan de ports sur le mail"}.get(b.get("reason"), "Connexions mail échouées")
        bans.append({"ip": b["address"], "source": "mail", "reason": reason, "reasonFr": reason_fr, "bannedAt": int(at), "evidence": [], "stalwartId": b["id"]})
    return bans


def nft_count(table, *sets):
    total = 0
    for s in sets:
        try:
            total += sum(len(x["set"].get("elem", [])) for x in json.loads(run("nft", "-j", "list", "set", "inet", table, s))["nftables"] if "set" in x)
        except (ValueError, KeyError):
            pass
    return total


def ignore_list():
    try:
        with open(JAIL_LOCAL) as f:
            m = re.search(r"^ignoreip\s*=(.*)$", f.read(), re.M)
    except FileNotFoundError:
        return []
    return m.group(1).split() if m else []


def zone_size(cc):
    try:
        with open(zone_path(cc)) as f:
            return sum(1 for l in f if l.strip())
    except OSError:
        return 0


def shield():
    f2b = [b for b in f2b_bans() if ipaddress.ip_address(b["ip"]).is_global]
    mail = stalwart_bans()
    by_source = {"ssh": 0, "web": 0, "mail": len(mail)}
    for b in f2b:
        by_source[b["source"]] = by_source.get(b["source"], 0) + 1
    recent = sorted(f2b + mail, key=lambda b: -b["bannedAt"])[:30]
    for b in recent:
        b["country"] = country(b["ip"])
        b.pop("evidence", None)
        b.pop("stalwartId", None)
    custom = [x for x in ignore_list() if "/" not in x and not x.startswith(("127.", "::1"))]
    meta = countries_meta()
    allowed = [{"label": meta.get(cc, {}).get("name", cc), "detail": f"{zone_size(cc)} ranges"} for cc in sorted(NEVER_BLOCK)]
    allowed += [{"label": "Cloudflare", "detail": "Proxies your sites"}, {"label": "Docker networks", "detail": "Internal traffic"}]
    allowed += [{"label": "Allowed IP", "detail": ip} for ip in custom]
    return {"total": sum(by_source.values()), "bySource": by_source, "blocklistRanges": nft_count("blocklist", "v4", "v6"), "recent": recent, "allowed": allowed}


def ip_detail(ip):
    rows = f2b_bans(ip=ip) + [b for b in stalwart_bans() if b["ip"] == ip]
    family = "v6" if ":" in ip else "v4"
    on_blocklist = subprocess.run(["nft", "get", "element", "inet", "blocklist", family, "{", ip, "}"], capture_output=True, timeout=10).returncode == 0
    web = []
    try:
        log = access_log()
        with open(log, "rb") as f:
            f.seek(max(0, os.path.getsize(log) - 20_000_000))
            needle = f'"{ip}"'.encode()
            for line in f:
                if needle in line:
                    try:
                        e = json.loads(line)
                        if real_ip(e) != ip:
                            continue
                        host = e.get("RequestHost", "")
                        host = "" if re.fullmatch(r"[\d.:]+", host) else host
                        web.append(f'{e.get("time", "")[5:16].replace("T", " ")}  {e.get("RequestMethod", "")} {host}{e.get("RequestPath", "")[:60]}  {e.get("DownstreamStatus", "")}')
                    except ValueError:
                        pass
    except OSError:
        pass
    ssh = [l for l in run("journalctl", "-u", "ssh", "--since", "-7d", "--no-pager", "-q", "-o", "short-iso", "--grep", re.escape(ip), timeout=60).splitlines()][-15:]
    out = {"ip": ip, "country": country(ip), "banned": bool(rows), "onBlocklist": on_blocklist, "allowed": ip in ignore_list(), "probablyYou": near_owner(ip),
           "webRequests": web[-15:], "webRequestCount": len(web), "sshLines": ssh, "evidence": []}
    if rows:
        b = rows[0]
        out.update(source=b["source"], reason=b["reason"], reasonFr=b["reasonFr"], bannedAt=b["bannedAt"], evidence=b["evidence"])
    return out


def postgres_container(name):
    if not CONTAINER_NAME.match(name):
        raise ValueError("Bad container name")
    info = json.loads(run("docker", "inspect", name) or "[]")
    if not info or not info[0]["Config"]["Image"].startswith("postgres"):
        raise ValueError("That container is not a Postgres database")
    if info[0]["Name"].lstrip("/").startswith("dokploy-postgres"):
        raise ValueError("Dokploy's own database holds its secrets and is not available from the app")
    env = dict(e.split("=", 1) for e in info[0]["Config"]["Env"] if "=" in e)
    return info[0]["Name"].lstrip("/"), env.get("POSTGRES_USER", "postgres"), env.get("POSTGRES_DB") or env.get("POSTGRES_USER", "postgres")


def psql(name, user, database, statements, timeout=15):
    cmd = ["docker", "exec", "-e", "PGOPTIONS=-c statement_timeout=5000 -c default_transaction_read_only=on", name,
           "psql", "-U", user, "-d", database, "-X", "-A", "-F", "\t", "-v", "ON_ERROR_STOP=1"]
    for statement in statements:
        cmd += ["-c", statement]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if proc.returncode != 0:
        raise ValueError(proc.stderr.strip().splitlines()[-1] if proc.stderr.strip() else "The query failed")
    return proc.stdout


def db_info(container, database=None):
    name, user, default = postgres_container(container)
    names = [l.split("\t") for l in psql(name, user, default, ["SELECT datname, pg_database_size(datname) FROM pg_database WHERE NOT datistemplate ORDER BY 2 DESC"]).strip().splitlines()[1:-1]]
    databases = [{"name": n, "sizeBytes": int(b)} for n, b in names]
    database = database or (databases[0]["name"] if databases else default)
    if database not in {d["name"] for d in databases}:
        raise ValueError("Unknown database")
    out = psql(name, user, database, ["""SELECT pg_database_size(current_database()),
        (SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()),
        current_setting('max_connections'), split_part(version(), ' ', 2)"""]).strip().splitlines()
    size, active, max_conn, version = out[-1].split("\t") if "\t" in out[-1] else out[-2].split("\t")
    tables = []
    for line in psql(name, user, database, ["""SELECT relname, pg_total_relation_size(relid), n_live_tup FROM pg_stat_user_tables
        ORDER BY pg_total_relation_size(relid) DESC LIMIT 10"""]).strip().splitlines()[1:-1]:
        t, b, rows = line.split("\t")
        tables.append({"name": t, "bytes": int(b), "rows": int(rows)})
    return {"container": name, "database": database, "databases": databases, "sizeBytes": int(size), "activeConnections": int(active),
            "maxConnections": int(max_conn), "version": version, "largestTables": tables}


def db_query(container, sql, database=None):
    sql = sql.strip().rstrip(";").strip()
    if not sql or len(sql) > 4000:
        raise ValueError("Write one query, under 4000 characters")
    if ";" in sql or not READ_ONLY_SQL.match(sql):
        raise ValueError("Only one read-only query: SELECT, WITH, SHOW, EXPLAIN, TABLE or VALUES")
    name, user, _ = postgres_container(container)
    database = db_info(container, database)["database"]
    lines = psql(name, user, database, ["BEGIN TRANSACTION READ ONLY", sql, "ROLLBACK"], timeout=20).splitlines()
    rows = [l.split("\t") for l in lines if l not in ("BEGIN", "ROLLBACK")]
    footer = rows.pop()[0] if rows and re.match(r"^\(\d+ rows?\)$", rows[-1][0]) else ""
    return {"columns": rows[0] if rows else [], "rows": rows[1:201], "truncated": len(rows) > 201, "summary": footer}


SSH_ACCEPTED = re.compile(r"Accepted (\S+) for (\S+) from ([0-9a-fA-F.:]+)")


def ssh_logins(since="-30d"):
    out = []
    for line in run("journalctl", "-u", "ssh", "--since", since, "--no-pager", "-q", "-o", "short-unix", "--grep", "Accepted", timeout=60).splitlines():
        m = SSH_ACCEPTED.search(line)
        if m:
            out.append({"at": int(float(line.split()[0])), "kind": "ssh", "user": m.group(2), "method": m.group(1), "ip": m.group(3)})
    return out


def logins():
    grouped = {}
    for l in ssh_logins():
        key = (l["kind"], l["user"], l["ip"])
        g = grouped.setdefault(key, {**l, "count": 0, "firstAt": l["at"]})
        g["count"] += 1
        g["at"] = max(g["at"], l["at"])
    rows = list(grouped.values())
    with db() as con:
        for d in con.execute("SELECT name, last_ip, last_seen_at FROM devices WHERE revoked=0 AND last_ip IS NOT NULL"):
            rows.append({"at": d["last_seen_at"], "kind": "app", "user": d["name"], "method": "device key", "ip": d["last_ip"], "count": 1, "firstAt": d["last_seen_at"]})
    for r in rows:
        r["country"] = country(r["ip"]) if ipaddress.ip_address(r["ip"]).is_global else "LAN"
    return sorted(rows, key=lambda r: -r["at"])[:100]


def login_watcher():
    seen = set(load_json("login_countries.json", []))
    if not seen:
        seen = {country(l["ip"]) for l in ssh_logins() if ipaddress.ip_address(l["ip"]).is_global}
        save_json("login_countries.json", sorted(seen))
    last = int(time.time())
    while True:
        time.sleep(30)
        try:
            for l in ssh_logins(since=f"@{last}"):
                last = max(last, l["at"] + 1)
                if not ipaddress.ip_address(l["ip"]).is_global:
                    continue
                c = country(l["ip"])
                if c and c not in seen:
                    seen.add(c)
                    save_json("login_countries.json", sorted(seen))
                    publish("system", {**fr(f"Connexion SSH depuis un nouveau pays : {c}", f"{l['user']} depuis {l['ip']} par {l['method']}. Si ce n’était pas vous, révoquez la clé maintenant."),
                                       "kind": "new_login_place", "title": f"SSH login from a new country: {c}",
                                       "message": f"{l['user']} from {l['ip']} by {l['method']}. If this was not you, revoke the key now."})
        except (OSError, ValueError, subprocess.SubprocessError):
            pass


def geo_config():
    return load_json("geo.json", {"countries": [], "continents": []})


def blocked_codes(config):
    meta = countries_meta()
    codes = set(config.get("countries", []))
    codes |= {c for c, m in meta.items() if m.get("continent") in set(config.get("continents", []))}
    return sorted(codes - NEVER_BLOCK)


def geo_apply(config, keep_open=()):
    if not blocked_codes(config):
        subprocess.run(["nft", "delete", "table", "inet", "helm-geo"], capture_output=True, timeout=10)
        return 0
    rules, count = geo_rules(config, keep_open)
    proc = subprocess.run(["nft", "-f", "-"], input=rules, capture_output=True, text=True, timeout=60)
    if proc.returncode != 0:
        raise ValueError(f"Firewall refused the rules: {proc.stderr.strip()[:200]}")
    return count


def geo_rules(config, keep_open=()):
    codes = blocked_codes(config)
    ranges = []
    for cc in codes:
        try:
            with open(zone_path(cc)) as f:
                ranges += [l.strip() for l in f if l.strip()]
        except OSError:
            pass
    if not ranges:
        raise ValueError("The country IP lists are not downloaded yet, nothing was blocked. Try again in a few minutes.")
    allow4 = trusted4(keep_open)
    rules = ["table inet helm-geo", "delete table inet helm-geo", "table inet helm-geo {",
             f" set allow4 {{ type ipv4_addr; flags interval; auto-merge; elements = {{ {', '.join(allow4)} }} }}",
             f" set blocked4 {{ type ipv4_addr; flags interval; auto-merge; elements = {{ {', '.join(ranges)} }} }}"]
    for hook in ("input", "forward"):
        rules.append(f" chain {hook} {{ type filter hook {hook} priority filter - 5; policy accept; ct state established,related accept; "
                     # Inbound SMTP stays open: Gmail, SES and most senders live in blocked countries.
                     f"iif lo accept; tcp dport 25 accept; ip saddr @allow4 accept; ip saddr @blocked4 drop; }}")
    rules.append("}")
    return "\n".join(rules), len(ranges)


def ipv4_net(text):
    try:
        net = ipaddress.ip_network(text, strict=False)
    except ValueError:
        return None
    return net if net.version == 4 else None


def phone_ips():
    with db() as con:
        return [r["last_ip"] for r in con.execute("SELECT last_ip FROM devices WHERE revoked=0 AND last_ip IS NOT NULL")]


def trusted4(keep_open=()):
    return [a for a in ignore_list() + cloudflare_ranges() + PRIVATE4 + list(keep_open) + phone_ips() if a and ipv4_net(a)]


def nft_elements(table, name):
    proc = subprocess.run(["nft", "-j", "list", "set", "inet", table, name], capture_output=True, text=True, timeout=30)
    if proc.returncode != 0:
        return None
    try:
        return sum(len(x["set"].get("elem", [])) for x in json.loads(proc.stdout)["nftables"] if "set" in x)
    except (ValueError, KeyError):
        return None


def geo_applied():
    ranges = nft_elements("helm-geo", "blocked4")
    return {"active": ranges is not None, "ranges": ranges or 0, "requested": len(blocked_codes(geo_config()))}


PERIMETER_TABLES = {
    "lowtrust": ("web-limit", "low_trust", f"{PERIMETER}/web-limit.nft", f"{PERIMETER}/web-limit.off", False),
    "originlock": ("origin-lock", "gate", f"{PERIMETER}/origin-lock.nft", f"{PERIMETER}/origin-lock.on", True),
}


def perimeter_state(name):
    table, chain, _, flag, flag_means_on = PERIMETER_TABLES[name]
    live = subprocess.run(["nft", "list", "chain", "inet", table, chain], capture_output=True, text=True, timeout=10)
    return {"wanted": os.path.exists(flag) == flag_means_on, "active": live.returncode == 0}


def perimeter_set(name, enabled):
    table, _, rules, flag, flag_means_on = PERIMETER_TABLES[name]
    if not os.path.exists(rules):
        raise ValueError(f"Missing on the server: {rules}")
    if enabled == flag_means_on:
        open(flag, "a").close()
    elif os.path.exists(flag):
        os.remove(flag)
    if enabled:
        must("nft", "-f", rules)
    else:
        subprocess.run(["nft", "delete", "table", "inet", table], capture_output=True, timeout=10)
    state = perimeter_state(name)
    if state["active"] != enabled:
        raise ValueError("The firewall did not take the change, check the agent log")
    return state


def unproxied_hosts():
    return sorted(d["host"] for d in cached("dns", 300, dns_check) if d["status"] != "proxied")


FAILED_SSH = re.compile(r"(?:Invalid user \S* from|Failed password for .* from|authenticating user \S+) ([0-9a-fA-F.:]+)")


def geo_overview():
    meta = countries_meta()
    config = geo_config()
    blocked = set(blocked_codes(config))
    stats = {}
    def bump(cc, key, n=1):
        if cc:
            stats.setdefault(cc, {"bans": 0, "attempts24h": 0})[key] += n
    bans = [b for b in f2b_bans() if ipaddress.ip_address(b["ip"]).is_global] + stalwart_bans()
    for b in bans:
        b["country"] = country(b["ip"])
        bump(b["country"], "bans")
    for line in run("journalctl", "-u", "ssh", "--since", "-24h", "--no-pager", "-q", "-o", "cat", timeout=60).splitlines():
        m = FAILED_SSH.search(line)
        if m:
            bump(local_country(m.group(1)), "attempts24h")
    rows = []
    for cc, m in meta.items():
        st = stats.get(cc, {"bans": 0, "attempts24h": 0})
        rows.append({"code": cc, "name": m["name"], "continent": m["continent"], "bans": st["bans"], "attempts24h": st["attempts24h"],
                     "blocked": cc in blocked, "protected": cc in NEVER_BLOCK})
    recent = sorted(bans, key=lambda b: -b["bannedAt"])[:25]
    return {"countries": rows,
            "continents": [{"name": c, "blocked": c in config.get("continents", []),
                            "countries": sum(1 for m in meta.values() if m["continent"] == c),
                            "bans": sum(r["bans"] for r in rows if r["continent"] == c)} for c in CONTINENTS],
            "recent": [{"ip": b["ip"], "country": b["country"], "source": b["source"], "at": b["bannedAt"]} for b in recent],
            "blockedCountries": sorted(blocked), "totalBans": len(bans),
            "zonesLoaded": len(GEO_RANGES) > 0}


def ssh_lock_apply():
    if not NEVER_BLOCK:
        run("nft", "delete", "table", "inet", "helm-ssh", timeout=10)
        return 0
    allow = []
    for cc in sorted(NEVER_BLOCK):
        try:
            with open(zone_path(cc)) as f:
                allow += [l.strip() for l in f if l.strip()]
        except OSError:
            return 0
    cloudflare = [n for n in cf_networks() if n.version == 4]
    extra = [a for a in ignore_list() + phone_ips() if ipv4_net(a) and not any(ipv4_net(a).overlaps(n) for n in cloudflare)]
    allow += extra + ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8"]
    rules = ["table inet helm-ssh", "delete table inet helm-ssh", "table inet helm-ssh {",
             f" set allow4 {{ type ipv4_addr; flags interval; auto-merge; elements = {{ {', '.join(allow)} }} }}",
             " chain input { type filter hook input priority filter - 6; policy accept; "
             "tcp dport 22 ip saddr @allow4 accept; tcp dport 22 ip6 saddr { ::1, fe80::/10 } accept; tcp dport 22 drop; }", "}"]
    proc = subprocess.run(["nft", "-f", "-"], input="\n".join(rules), capture_output=True, text=True, timeout=60)
    if proc.returncode != 0:
        print(f"ssh lock: {proc.stderr.strip()[:200]}", file=sys.stderr)
        return 0
    return len(allow)


def geo_watcher():
    load_geo()
    ssh_lock_apply()
    try:
        if blocked_codes(geo_config()):
            geo_apply(geo_config())
    except (OSError, ValueError) as e:
        print(f"geo restore: {e}", file=sys.stderr)
    last = 0
    while True:
        if time.time() - last > 86400 or not GEO_RANGES:
            try:
                geo_refresh()
                last = time.time()
                geo_apply(geo_config())
                ssh_lock_apply()
            except (OSError, ValueError) as e:
                print(f"geo refresh: {e}", file=sys.stderr)
        time.sleep(600)


def panic_state():
    state = load_json("panic.json", {})
    return {"active": state.get("until", 0) > time.time(), "until": state.get("until")}


def panic_apply(until, extra_ip=None):
    if until <= time.time():
        subprocess.run(["nft", "delete", "table", "inet", "helm-panic"], capture_output=True, timeout=10)
        save_json("panic.json", {})
        return
    proc = subprocess.run(["nft", "-f", "-"], input=panic_rules(extra_ip), capture_output=True, text=True, timeout=20)
    if proc.returncode != 0:
        raise ValueError(f"Firewall refused the rules: {proc.stderr.strip()[:200]}")
    save_json("panic.json", {"until": int(until)})


def panic_rules(extra_ip=None):
    allow = ignore_list() + cloudflare_ranges() + PRIVATE4 + ([extra_ip] if extra_ip else [])
    v4 = [a for a in allow if ":" not in a]
    v6 = [a for a in allow if ":" in a]
    rules = ["table inet helm-panic", "delete table inet helm-panic", "table inet helm-panic {",
             f" set allow4 {{ type ipv4_addr; flags interval; auto-merge; elements = {{ {', '.join(v4)} }} }}",
             f" set allow6 {{ type ipv6_addr; flags interval; auto-merge; elements = {{ {', '.join(v6) or '::1'} }} }}"]
    for hook in ("input", "forward"):
        rules.append(f" chain {hook} {{ type filter hook {hook} priority filter - 4; policy accept; ct state established,related accept; "
                     f"iif lo accept; ip saddr @allow4 accept; ip6 saddr @allow6 accept; ip6 saddr fe80::/10 accept; drop; }}")
    rules.append("}")
    return "\n".join(rules)


def panic_watcher():
    state = load_json("panic.json", {})
    if state.get("until", 0) > time.time():
        panic_apply(state["until"])
    while True:
        time.sleep(15)
        state = load_json("panic.json", {})
        if state.get("until") and state["until"] <= time.time():
            panic_apply(0)
            publish("system", {**fr("Verrouillage terminé", "Tous les pays peuvent de nouveau joindre le serveur."), "kind": "panic_off", "title": "Lockdown ended", "message": "All countries can reach the server again."})


def must(*cmd, timeout=30):
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.SubprocessError) as e:
        raise ValueError(f"Could not run: {cmd[0]}: {e}")
    if proc.returncode != 0:
        raise ValueError(f"Command failed: {cmd[0]}: {(proc.stderr or proc.stdout).strip()[:200]}")
    return proc.stdout


def cscli_cmd():
    if os.path.exists("/usr/bin/cscli") or os.path.exists("/usr/local/bin/cscli"):
        return ["cscli"]
    cid = run("docker", "ps", "-q", "--filter", "name=crowdsec").split()
    return ["docker", "exec", cid[0], "cscli"] if cid else None


def cscli_json(*args):
    out = must(*cscli_cmd(), *args, "-o", "json", timeout=30)
    return json.loads(out or "null")


def f2b_jails():
    names = re.search(r"Jail list:\s*(.*)", must("fail2ban-client", "status"))
    jails = []
    for name in [j.strip() for j in (names.group(1) if names else "").split(",") if j.strip()]:
        text = must("fail2ban-client", "status", name)
        num = lambda label: int((re.search(rf"{label}:\s*(\d+)", text) or [0, 0])[1])
        listed = re.search(r"Banned IP list:\s*(.*)", text)
        jails.append({"name": name, "banned": num("Currently banned"), "totalBanned": num("Total banned"), "failing": num("Currently failed"),
                      "ips": (listed.group(1).split() if listed else [])[:300]})
    return jails


def f2b_jails_of(ip):
    out = must("fail2ban-client", "banned", ip)
    return sorted(set(re.findall(r"'([^']+)'", out)))


def crowdsec_decisions(ip=None):
    alerts = cscli_json("decisions", "list", *(["--ip", ip] if ip else [])) or []
    out = []
    for a in alerts:
        for d in a.get("decisions") or []:
            out.append({"id": d.get("id"), "ip": d.get("value", ""), "type": d.get("type", ""), "origin": d.get("origin", ""),
                        "reason": d.get("scenario", ""), "duration": d.get("duration", ""), "country": (a.get("source") or {}).get("cn", "")})
    return out[:300]


def crowdsec_state():
    if cscli_cmd() is None:
        return {"installed": False}
    state = {"installed": True, "errors": []}
    for key, fn in (("decisions", crowdsec_decisions), ("bouncers", lambda: [
            {"name": b.get("name", ""), "type": b.get("type", ""), "lastPull": b.get("last_pull", ""), "revoked": bool(b.get("revoked"))}
            for b in cscli_json("bouncers", "list") or []]), ("sources", lambda: [
            {"source": k, "read": int(v.get("reads", 0)), "parsed": int(v.get("parsed", 0)), "unparsed": int(v.get("unparsed", 0))}
            for k, v in ((cscli_json("metrics", "show", "acquisition") or {}).get("acquisition") or {}).items() if isinstance(v, dict)])):
        try:
            state[key] = fn()
        except (ValueError, AttributeError, TypeError) as e:
            state[key] = []
            state["errors"].append(f"{key}: {e}"[:200])
    return state


def firewall_banned():
    tables = [t["table"]["name"] for t in json.loads(must("nft", "-j", "list", "tables"))["nftables"] if "table" in t and t["table"]["name"].startswith("f2b")]
    found = set()
    for table in tables:
        for block in json.loads(must("nft", "-j", "list", "table", "inet", table))["nftables"]:
            found |= {e for e in (block.get("set") or {}).get("elem") or [] if isinstance(e, str)}
    return found


def firewall_has(ip):
    return ip in firewall_banned()


def ban_state(ip):
    state = {"ip": ip, "jails": f2b_jails_of(ip), "firewall": firewall_has(ip), "crowdsec": None}
    if cscli_cmd() is not None:
        state["crowdsec"] = bool(crowdsec_decisions(ip))
    return state


def ban(ip, by, duration="24h"):
    if duration not in BAN_FALLBACK:
        raise ValueError("Choose 24h or permanent")
    if os.path.exists(PERIMETER_BAN):
        must(PERIMETER_BAN, ip, "perm" if duration == "permanent" else duration, timeout=60)
    else:
        jail, cs_duration = BAN_FALLBACK[duration]
        if duration == "permanent" and must("fail2ban-client", "get", jail, "bantime").strip() != "-1":
            raise ValueError("Permanent bans need bantime = -1 in the recidive jail")
        must("fail2ban-client", "set", jail, "banip", ip)
        if cscli_cmd() is not None:
            must(*cscli_cmd(), "decisions", "add", "--ip", ip, "--duration", cs_duration, "--reason", "helm")
    state = ban_state(ip)
    if not state["firewall"] and not state["crowdsec"]:
        raise ValueError(f"Not applied by the firewall: {ip}")
    return state


def unban(ip):
    if os.path.exists(PERIMETER_UNBAN):
        must(PERIMETER_UNBAN, ip, timeout=60)
    else:
        for jail in f2b_jails_of(ip):
            must("fail2ban-client", "set", jail, "unbanip", ip)
        if cscli_cmd() is not None:
            subprocess.run([*cscli_cmd(), "decisions", "delete", "--ip", ip], capture_output=True, timeout=30)
    for b in stalwart_bans():
        if b["ip"] == ip:
            stalwart_call("x:BlockedIp/set", {"destroy": [b["stalwartId"]]})
    if os.path.exists(STALWART_BAN_SYNC):
        subprocess.run([STALWART_BAN_SYNC], timeout=30)
    state = ban_state(ip)
    if state["jails"] or state["firewall"] or state["crowdsec"]:
        raise ValueError(f"Still banned after unban: {ip} ({', '.join(state['jails']) or 'CrowdSec'})")
    return state


def allow(ip):
    if ip not in ignore_list():
        try:
            with open(JAIL_LOCAL) as f:
                text = f.read()
        except FileNotFoundError:
            text = ""
        line = f"ignoreip = 127.0.0.1/8 ::1 {ip}"
        if re.search(r"^ignoreip\s*=", text, re.M):
            text = re.sub(r"^(ignoreip\s*=.*)$", lambda m: f"{m.group(1)} {ip}", text, count=1, flags=re.M)
        elif re.search(r"^\[DEFAULT\][ \t]*$", text, re.M):
            text = re.sub(r"^\[DEFAULT\][ \t]*$", lambda m: f"{m.group(0)}\n{line}", text, count=1, flags=re.M)
        else:
            text = f"[DEFAULT]\n{line}\n\n{text}"
        with open(JAIL_LOCAL, "w") as f:
            f.write(text)
        for jail in f2b_jails():
            must("fail2ban-client", "set", jail["name"], "addignoreip", ip)
    state = unban(ip)
    threading.Thread(target=ssh_lock_apply, daemon=True).start()
    return state


ALERT_EVERY = 1800
ALERTS_PER_MINUTE = 10
alerted: dict = {}
alert_minute = [0, 0]


def emit(batch):
    now = time.time()
    with lock:
        for ip in [ip for ip, at in alerted.items() if now - at > ALERT_EVERY]:
            del alerted[ip]
        batch = [(kind, data) for kind, data in batch if data["ip"] not in alerted]
        for _, data in batch:
            alerted[data["ip"]] = now
        minute = int(now // 60)
        if alert_minute[0] != minute:
            alert_minute[:] = [minute, 0]
        overflow = alert_minute[1] + len(batch) > ALERTS_PER_MINUTE
        alert_minute[1] += len(batch)
    if not batch:
        return
    if overflow:
        ips = ", ".join(d["ip"] for _, d in batch[:5]) + ("…" if len(batch) > 5 else "")
        publish("system", ev("threat_digest", f"{len(batch)} more IPs banned or probing", ips, f"{len(batch)} IP de plus bannies ou en sondage", ips))
        return
    for kind, data in batch:
        publish(kind, data)


def ban_watcher():
    try:
        seen_at = max([b["bannedAt"] for b in f2b_bans(limit=1)] or [int(time.time())])
    except sqlite3.Error:
        seen_at = int(time.time())
    seen_mail = {b["ip"] for b in stalwart_bans()}
    seen_cs = None
    while True:
        time.sleep(15)
        batch = []
        try:
            for b in reversed(f2b_bans(limit=50)):
                if b["bannedAt"] > seen_at:
                    seen_at = b["bannedAt"]
                    batch.append(("ban", {"id": f"{b['ip']}-{b['bannedAt']}", "ip": b["ip"], "source": b["source"], "reason": b["reason"], "reasonFr": b["reasonFr"]}))
            for b in stalwart_bans():
                if b["ip"] not in seen_mail:
                    seen_mail.add(b["ip"])
                    batch.append(("ban", {"id": f"{b['ip']}-{b['bannedAt']}", "ip": b["ip"], "source": "mail", "reason": b["reason"], "reasonFr": b["reasonFr"]}))
            if cscli_cmd() is not None:
                own = [d for d in crowdsec_decisions() if not d["reason"].startswith(("fail2ban", "helm"))]
                fresh = [d for d in own if seen_cs is not None and d["id"] not in seen_cs]
                seen_cs = {d["id"] for d in own}
                batch += [("ban", {"id": f"cs-{d['id']}", "ip": d["ip"], "source": "crowdsec", "reason": d["reason"], "reasonFr": d["reason"]}) for d in fresh]
        except (sqlite3.Error, OSError, ValueError):
            pass
        emit(batch)


DOCKER_PORT = re.compile(r"(?:(\S+?):)?(\d+)(?:-(\d+))?->\d+(?:-\d+)?/(tcp|udp)")


def exposure():
    published = []
    for kind, cmd in (("container", ["docker", "ps", "--format", "{{json .}}"]), ("service", ["docker", "service", "ls", "--format", "{{json .}}"])):
        for line in run(*cmd).splitlines():
            row = json.loads(line)
            for bind, first, last, proto in DOCKER_PORT.findall(row.get("Ports", "")):
                if not bind.startswith(("127.", "[::1]")):
                    published.append({"port": int(first), "portEnd": int(last or first), "proto": proto, "bind": bind or "*",
                                      "owner": row.get("Names") or row.get("Name", ""), "via": kind})
    published = list({(p["port"], p["proto"]): p for p in published}.values())
    listening = set()
    for line in run("ss", "-tulnpH").splitlines():
        parts = line.split()
        bind, _, port = parts[4].rpartition(":") if len(parts) >= 5 else ("", "", "")
        if port.isdigit() and not bind.startswith(("127.", "[::1]")):
            proc = re.search(r'"([^"]+)"', line)
            listening.add((parts[0], int(port), bind, proc.group(1) if proc else ""))
    host = []
    for proto, port, bind, owner in sorted(listening, key=lambda x: (x[0], x[1])):
        if any(p["proto"] == proto and p["port"] <= port <= p["portEnd"] for p in published):
            continue
        last = host[-1] if host else None
        if last and last["proto"] == proto and last["port"] <= port <= last["portEnd"]:
            continue
        if last and last["proto"] == proto and last["owner"] == owner and last["portEnd"] + 1 == port:
            last["portEnd"] = port
        else:
            host.append({"port": port, "portEnd": port, "proto": proto, "bind": bind, "owner": owner, "via": "host"})
    return sorted(published + host, key=lambda p: (p["port"], p["proto"]))


def gwbridge_ips(*filters):
    ids = set()
    for f in filters:
        ids |= set(run("docker", "ps", "-q", "--no-trunc", "--filter", f).split())
    try:
        members = (json.loads(run("docker", "network", "inspect", "docker_gwbridge") or "[]") or [{}])[0].get("Containers") or {}
    except ValueError:
        return []
    return sorted(m["IPv4Address"].split("/")[0] for cid, m in members.items() if cid in ids and m.get("IPv4Address"))


proxy_ips: set = set()
dokploy_ips: list = []


def agent_firewall_pass():
    traefik = gwbridge_ips("name=dokploy-traefik")
    dokploy = gwbridge_ips("label=com.docker.swarm.service.name=dokploy")
    dokploy_ips[:] = dokploy
    if not traefik:
        return
    proxy_ips.clear()
    proxy_ips.update(traefik)
    allowed = ["127.0.0.0/8", *traefik, *dokploy]
    rules = "\n".join(["table inet helm-agent", "delete table inet helm-agent", "table inet helm-agent {", "  chain input {",
                       "    type filter hook input priority filter - 3; policy accept;",
                       f"    tcp dport 9311 ip saddr {{ {', '.join(allowed)} }} accept", "    tcp dport 9311 ip6 saddr ::1 accept", "    tcp dport 9311 drop", "  }", "}", ""])
    try:
        with open(AGENT_FIREWALL) as f:
            if f.read() == rules:
                return
    except OSError:
        pass
    proc = subprocess.run(["nft", "-f", "-"], input=rules, capture_output=True, text=True, timeout=20)
    if proc.returncode != 0:
        print(f"agent firewall: {proc.stderr.strip()[:200]}", file=sys.stderr)
        return
    with open(AGENT_FIREWALL + ".tmp", "w") as f:
        f.write(rules)
    os.replace(AGENT_FIREWALL + ".tmp", AGENT_FIREWALL)


def agent_port_state():
    try:
        with open(AGENT_FIREWALL) as f:
            text = f.read()
    except OSError:
        text = ""
    m = re.search(r"tcp dport 9311 ip saddr \{ ([^}]*) \} accept", text)
    live = subprocess.run(["nft", "list", "table", "inet", "helm-agent"], capture_output=True, text=True, timeout=10)
    return {"allowed": [a.strip() for a in m.group(1).split(",")] if m else [], "active": live.returncode == 0 and "9311" in live.stdout}


SECURITY_ACTIONS = {"manual_ban", "unban", "allow", "geo_block", "geo_block_failed", "lowtrust", "lowtrust_failed", "originlock", "originlock_failed", "panic_on", "panic_off", "claimed", "claim_failed",
                    "code_rotated", "pair_requested", "device_approved", "device_denied", "device_revoked", "auth_failed", "reset"}


def web_rejections(minutes=60):
    per_ip = {}
    for ts, line in access_lines(minutes * 60, 128 << 20):
        try:
            e = json.loads(line)
        except ValueError:
            continue
        status = int(e.get("DownstreamStatus") or 0)
        if status < 400 or status >= 500:
            continue
        ip = real_ip(e)
        try:
            if not ipaddress.ip_address(ip).is_global:
                continue
        except ValueError:
            continue
        row = per_ip.setdefault(ip, {"n": 0, "n429": 0, "at": ts, "host": str(e.get("RequestHost", ""))[:80], "country": header(e, "Cf-Ipcountry")})
        row["n"] += 1
        row["n429"] += status == 429
    out = []
    for ip, r in sorted(per_ip.items(), key=lambda x: -x[1]["n"])[:25]:
        at = int(datetime.strptime(r["at"].decode(), "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc).timestamp())
        out.append({"at": at, "source": "web", "ip": ip, "country": r["country"] or local_country(ip), "what": r["host"], "count": r["n"], "count429": r["n429"]})
    return out


SUSPECT_PATH = re.compile(r"(?i)/(admin|identities|ops|kratos)(/|$)|/debug/pprof|/\.env|/\.git|/wp-|phpmyadmin|actuator")


SERVER_IPS = set(env_list("HELM_SERVER_IPS"))
OWN_HOSTS = {h.lower() for h in env_list("HELM_OWN_HOSTS")}


def host_ips():
    return SERVER_IPS | set(re.findall(r"inet6? ([0-9a-fA-F.:]+)/\d+ scope global", run("ip", "-o", "addr")))


def owner_networks():
    with db() as con:
        ips = [r["last_ip"] for r in con.execute("SELECT last_ip FROM devices WHERE revoked=0 AND role='owner' AND last_ip IS NOT NULL")]
    nets = []
    for ip in ips + [a for a in ignore_list() if "/" not in a]:
        try:
            addr = ipaddress.ip_address(ip)
        except ValueError:
            continue
        if addr.is_global:
            nets.append(ipaddress.ip_network(f"{ip}/{24 if addr.version == 4 else 64}", strict=False))
    return nets


def near_owner(ip):
    return in_networks(ip, owner_networks())


def access_by_ip(minutes):
    owners = set(ignore_list()) | set(phone_ips()) | cached("host_ips", 3600, host_ips)
    per = {}
    for ts, line in access_lines(minutes * 60, 128 << 20):
        try:
            e = json.loads(line)
            ip = real_ip(e)
            if ip in owners or not ipaddress.ip_address(ip).is_global:
                continue
        except ValueError:
            continue
        path = str(e.get("RequestPath", "")).split("?")[0][:80]
        status = int(e.get("DownstreamStatus") or 0)
        r = per.setdefault(ip, {"ip": ip, "hits": 0, "statuses": {}, "paths": {}, "host": str(e.get("RequestHost", ""))[:80], "country": header(e, "Cf-Ipcountry"),
                                "agent": header(e, "User-Agent")[:120], "lastSeen": ts, "probes": 0, "denied": 0})
        r["hits"] += 1
        r["statuses"][str(status)] = r["statuses"].get(str(status), 0) + 1
        r["paths"][path] = r["paths"].get(path, 0) + 1
        own_host = str(e.get("RequestHost", "")).lower().split(":")[0] in OWN_HOSTS
        r["probes"] += not own_host and bool(SUSPECT_PATH.search(path) or SCANNER.search(path))
        r["denied"] += status in (401, 403, 429)
    for r in per.values():
        r["lastSeen"] = int(datetime.strptime(r["lastSeen"].decode(), "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc).timestamp())
        r["country"] = r["country"] or local_country(r["ip"])
        r["topPaths"] = [{"path": k, "count": v} for k, v in sorted(r.pop("paths").items(), key=lambda x: -x[1])[:5]]
    return per


def live_attackers(minutes=15):
    per = access_by_ip(minutes)
    since = time.time() - 3600
    flagged = {}
    for b in f2b_bans(limit=100):
        if b["bannedAt"] >= since:
            flagged.setdefault(b["ip"], []).append(f"fail2ban {b['jail']}")
    banned = firewall_banned()
    if cscli_cmd() is not None:
        try:
            decisions = crowdsec_decisions()
            banned |= {d["ip"] for d in decisions}
            for a in cscli_json("alerts", "list", "--since", "1h", "-l", "100") or []:
                src = a.get("source") or {}
                ip = src.get("ip") or src.get("value", "")
                flagged.setdefault(ip, []).append(f"CrowdSec {a.get('scenario', '')}")
        except (ValueError, TypeError):
            pass
    rows, nets = [], owner_networks()
    for ip, r in per.items():
        if not (ip in flagged or r["probes"] or r["denied"] >= 5 or r["statuses"].get("404", 0) >= 10 or r["hits"] >= 300):
            continue
        rows.append({**r, "banned": ip in banned, "flags": sorted(set(flagged.get(ip, []))), "probablyYou": in_networks(ip, nets)})
    return sorted(rows, key=lambda r: -r["lastSeen"])[:50]


country_seen: dict = {}


def threat_pass():
    now = time.time()
    per = access_by_ip(5)
    first = not country_seen
    hits = {}
    for r in per.values():
        hits[r["country"]] = hits.get(r["country"], 0) + r["hits"]
    batch = []
    for cc, n in hits.items():
        if cc and cc not in NEVER_BLOCK and n >= 100 and not first and now - country_seen.get(cc, 0) > 86400:
            top = max((r for r in per.values() if r["country"] == cc), key=lambda r: r["hits"])
            batch.append(("system", {**ev("threat", f"Traffic spike from a new country: {cc}", f"{n} requests in 5 min, mostly {top['ip']}",
                                          f"Pic de trafic depuis un nouveau pays : {cc}", f"{n} requêtes en 5 min, surtout {top['ip']}"), "ip": top["ip"]}))
    for cc in hits:
        country_seen[cc] = now
    banned, nets = firewall_banned(), owner_networks()
    for r in per.values():
        if r["ip"] in banned or in_networks(r["ip"], nets) or not (r["probes"] or r["denied"] >= 20):
            continue
        what = r["topPaths"][0]["path"] if r["topPaths"] else ""
        title, title_fr = (f"{r['ip']} ({r['country'] or '?'}) is probing {what}", f"{r['ip']} ({r['country'] or '?'}) sonde {what}") if r["probes"] \
            else (f"{r['ip']} ({r['country'] or '?'}) was refused {r['denied']} times", f"{r['ip']} ({r['country'] or '?'}) a été refusée {r['denied']} fois")
        message = f"{r['hits']} requests on {r['host']} in 5 min, not banned yet"
        message_fr = f"{r['hits']} requêtes sur {r['host']} en 5 min, pas encore bannie"
        batch.append(("system", {**ev("threat", title, message, title_fr, message_fr), "ip": r["ip"]}))
    emit(batch)


def security_log():
    events = [{"at": b["bannedAt"], "source": "fail2ban", "ip": b["ip"], "country": local_country(b["ip"]), "what": f"{b['jail']}: {b['reason']}", "count": 1}
              for b in f2b_bans(limit=60)]
    if cscli_cmd() is not None:
        try:
            for a in cscli_json("alerts", "list", "--since", "24h", "-l", "60") or []:
                at = datetime.fromisoformat(str(a.get("created_at", "")).replace("Z", "+00:00")).timestamp()
                src = a.get("source") or {}
                events.append({"at": int(at), "source": "crowdsec", "ip": src.get("ip") or src.get("value", ""), "country": src.get("cn", ""),
                               "what": a.get("scenario", ""), "count": int(a.get("events_count") or 1)})
        except (ValueError, TypeError) as e:
            events.append({"at": int(time.time()), "source": "crowdsec", "ip": "", "country": "", "what": f"CrowdSec alerts unavailable: {e}"[:200], "count": 0})
    with db() as con:
        rows = con.execute(f"SELECT at, device, action, detail, ip FROM audit WHERE action IN ({','.join('?' * len(SECURITY_ACTIONS))}) ORDER BY at DESC LIMIT 60",  # vibe-guardian: ignore
                           sorted(SECURITY_ACTIONS)).fetchall()
    events += [{"at": r["at"], "source": "helm", "ip": r["ip"] or "", "country": "", "what": f"{r['action']} {r['detail'] or ''}".strip(), "count": 1, "by": r["device"]} for r in rows]
    events += web_rejections()
    return sorted(events, key=lambda e: -e["at"])[:200]


def security():
    def safe(fn, fallback):
        try:
            return fn()
        except (OSError, ValueError, subprocess.SubprocessError) as e:
            return {**fallback, "error": str(e)[:200]}
    ssh = nft_elements("helm-ssh", "allow4")
    port = safe(agent_port_state, {})
    open_ports = safe(lambda: {"ports": exposure()}, {"ports": []})
    for p in open_ports["ports"]:
        p["restricted"] = (p["port"] == 22 and ssh is not None) or (p["port"] == 9311 and bool(port.get("active")))
    return {"geo": safe(geo_applied, {}), "lowTrust": safe(lambda: perimeter_state("lowtrust"), {}), "panic": panic_state(),
            "originLock": safe(lambda: {**perimeter_state("originlock"), "unproxied": unproxied_hosts()}, {}),
            "sshLock": {"active": ssh is not None, "entries": ssh or 0}, "agentPort": port,
            "fail2ban": safe(lambda: {"jails": f2b_jails()}, {"jails": []}), "crowdsec": safe(crowdsec_state, {"installed": False}),
            "exposure": open_ports, "accessLog": access_log()}


def service_states():
    states = {}
    for line in run("docker", "service", "ls", "--format", "{{json .}}").splitlines():
        s = json.loads(line)
        running, _, desired = s["Replicas"].split(" ")[0].partition("/")
        if desired.isdigit() and int(desired) > 0:
            states[s["Name"]] = ("container", None, "up" if running == desired else "down", f"{running}/{desired} replicas")
    for c in inspect_all():
        labels = c["Config"].get("Labels") or {}
        if "com.docker.swarm.service.name" in labels:
            continue
        st = c["State"]
        if c["HostConfig"]["RestartPolicy"]["Name"] in ("", "no") and st["Status"] == "exited" and st["ExitCode"] == 0:
            continue
        health = (st.get("Health") or {}).get("Status")
        up = st["Status"] == "running" and health != "unhealthy"
        reason = "unhealthy" if health == "unhealthy" else f"{st['Status']} ({st['ExitCode']})"
        states[c["Name"].lstrip("/")] = ("container", None, "up" if up else "down", reason)
    for h in sorted(routed_hosts()):
        states[h] = ("http", h) + http_check(h)
    return states


def http_check(hostname):
    started = time.time()
    try:
        req = urllib.request.Request(f"https://{hostname}/", method="HEAD", headers={"User-Agent": "helm-agent"})
        with urllib.request.urlopen(req, timeout=10) as r:
            status = r.status
    except urllib.error.HTTPError as e:
        status = e.code
    except OSError as e:
        return "down", str(getattr(e, "reason", e))[:80]
    latency = int((time.time() - started) * 1000)
    return ("up" if status < 500 else "down"), f"HTTP {status} · {latency}"


def cert_expiry(hostname):
    try:
        with socket.create_connection((hostname, 443), timeout=5) as sock:
            with ssl.create_default_context().wrap_socket(sock, server_hostname=hostname) as s:
                return int(ssl.cert_time_to_seconds(s.getpeercert()["notAfter"]))
    except OSError:
        return None


def uptime_monitor():
    while True:
        try:
            today = date.today().isoformat()
            now = int(time.time())
            states = service_states()
            with db() as con:
                previous = {r["name"]: r["state"] for r in con.execute("SELECT name, state FROM services")}
                for name, (kind, target, state, reason) in states.items():
                    latency = int(reason.rsplit("· ", 1)[1]) if kind == "http" and state == "up" and "· " in reason else None
                    expires = cached(f"cert:{name}", 21600, lambda h=name: cert_expiry(h)) if kind == "http" else None
                    con.execute("INSERT OR REPLACE INTO services VALUES(?,?,?,?,?,?)", (name, kind, target, state, latency, expires))
                    con.execute("INSERT INTO uptime_days VALUES(?,?,?,1) ON CONFLICT(day, service) DO UPDATE SET up=up+excluded.up, total=total+1",
                                (today, name, 1 if state == "up" else 0))
                    was = previous.get(name)
                    if state == "down" and was != "down":
                        con.execute("INSERT INTO incidents VALUES(?,?,NULL,?)", (name, now, reason))
                        if was is not None:
                            publish("system", {**fr(f"{name} est hors service", reason), "kind": "service_down", "title": f"{name} is down", "message": reason, "service": name})
                    if state == "up" and was == "down":
                        con.execute("UPDATE incidents SET end=? WHERE service=? AND end IS NULL", (now, name))
                        publish("system", {**fr(f"{name} est de retour", "Fonctionne de nouveau"), "kind": "service_up", "title": f"{name} is back", "message": "Running again"})
                    if expires and expires - now < 14 * 86400:
                        notice_once(f"cert:{name}", 86400, "system", {**fr(f"Le certificat de {name} expire bientôt", f"Dans {(expires - now) // 86400} jours"), "kind": "cert_expiring", "title": f"Certificate for {name} expires soon",
                                                                      "message": f"In {(expires - now) // 86400} days"})
                gone = set(previous) - set(states)
                for name in gone:
                    con.execute("DELETE FROM services WHERE name=?", (name,))
                con.execute("DELETE FROM uptime_days WHERE day < date('now','-90 days')")
        except (OSError, ValueError, KeyError, subprocess.SubprocessError, sqlite3.Error) as e:
            print(f"uptime monitor: {e}", file=sys.stderr)
        time.sleep(60)


def uptime(days):
    days = max(1, min(days, 90))
    out = []
    with db() as con:
        for s in con.execute("SELECT * FROM services ORDER BY kind, name").fetchall():
            rows = con.execute("SELECT day, up, total FROM uptime_days WHERE service=? AND day >= date('now', ?) ORDER BY day",
                               (s["name"], f"-{days - 1} days")).fetchall()
            up, total = sum(r["up"] for r in rows), sum(r["total"] for r in rows)
            incidents = con.execute("SELECT start, end, reason FROM incidents WHERE service=? ORDER BY start DESC LIMIT 20", (s["name"],)).fetchall()
            out.append({"name": s["name"], "kind": s["kind"], "target": s["target"], "current": s["state"],
                        "uptimePercent": round(100 * up / total, 3) if total else None,
                        "days": [{"date": r["day"], "upPercent": round(100 * r["up"] / r["total"], 2), "downMinutes": r["total"] - r["up"]} for r in rows],
                        "incidents": [dict(i) for i in incidents], "latencyMs": s["latency"], "certExpiresAt": s["cert_expires"]})
        first = con.execute("SELECT min(day) d FROM uptime_days").fetchone()["d"]
    since = int(datetime.fromisoformat(first).replace(tzinfo=timezone.utc).timestamp()) if first else int(time.time())
    return {"services": out, "since": since}


def docker_events():
    while True:
        try:
            proc = subprocess.Popen(["docker", "events", "--format", "{{json .}}", "--filter", "type=container", "--filter", "event=die",
                                     "--filter", "event=oom", "--filter", "event=health_status"], stdout=subprocess.PIPE, text=True)
        except OSError:
            time.sleep(300)
            continue
        for line in proc.stdout:
            try:
                e = json.loads(line)
            except ValueError:
                continue
            attrs = e.get("Actor", {}).get("Attributes", {})
            name = attrs.get("com.docker.swarm.service.name") or attrs.get("name", "container")
            action = e.get("Action", "")
            if action == "oom":
                publish("system", {**fr(f"{name} a manqué de mémoire", "Le noyau l’a arrêté. Il redémarre si sa politique le permet."), "kind": "oom", "title": f"{name} ran out of memory", "message": "The kernel killed it. It restarts if its policy allows.", "service": name})
            elif action == "die" and attrs.get("exitCode") not in ("0", "143", None):
                publish("system", {**fr(f"{name} a planté", f"Code de sortie {attrs.get('exitCode')}"), "kind": "container_died", "title": f"{name} crashed", "message": f"Exit code {attrs.get('exitCode')}", "service": name})
            elif action.startswith("health_status") and "unhealthy" in action:
                publish("system", {**fr(f"{name} est en mauvaise santé", "Son contrôle de santé échoue"), "kind": "unhealthy", "title": f"{name} is unhealthy", "message": "Its health check is failing", "service": name})
        time.sleep(5)


def dokploy_kind(text):
    t = text.lower()
    for words, kind in ((("build", "error"), "build_error"), (("backup", "error"), "backup_error"), (("backup", "fail"), "backup_error"),
                        (("threshold",), "threshold"), (("cpu",), "threshold"), (("memory",), "threshold"), (("backup",), "backup"),
                        (("clean",), "cleanup"), (("restart",), "restart"), (("deploy",), "deploy"), (("build",), "deploy")):
        if all(w in t for w in words):
            return kind
    return "other"


def dokploy(method, procedure, query, body):
    key = os.environ.get("HELM_DOKPLOY_KEY")
    if not key:
        return 503, b'{"error": "Dokploy is not configured"}'
    path = procedure + (f"?{query}" if query else "")
    bases = [f"http://{ip}:3000/api/" for ip in dokploy_ips] + [DOKPLOY]
    for i, base in enumerate(bases):
        req = urllib.request.Request(base + path, data=body if method == "POST" else None, method=method,
                                     headers={"x-api-key": key, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=60 if i == len(bases) - 1 else 5) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()
        except OSError:
            if i == len(bases) - 1:
                return 503, b'{"error": "Dokploy is not reachable"}'


COOLIFY_ID = r"[A-Za-z0-9_-]{1,64}"
COOLIFY_READ = re.compile(rf"(projects|applications|services|databases|servers|deployments|resources)(/{COOLIFY_ID})?|projects/{COOLIFY_ID}/(?!envs$){COOLIFY_ID}"
                          rf"|servers/{COOLIFY_ID}/(resources|domains)|(applications|services|databases)/{COOLIFY_ID}/logs|deployments/applications/{COOLIFY_ID}")
COOLIFY_ACTION = re.compile(rf"deploy|(applications|services|databases)/{COOLIFY_ID}/(start|stop|restart)|deployments/{COOLIFY_ID}/cancel")


def coolify(method, path, query, body):
    token = os.environ.get("HELM_COOLIFY_TOKEN")
    if not token:
        return 503, b'{"error": "Coolify is not configured"}'
    req = urllib.request.Request(COOLIFY + path + (f"?{query}" if query else ""), data=body if method == "POST" else None, method=method,
                                 headers={"Authorization": f"Bearer {token}", "Accept": "application/json", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except OSError:
        return 503, b'{"error": "Coolify is not reachable"}'


def capabilities():
    images = run("docker", "ps", "--format", "{{.Image}}", timeout=10)
    dokploy_ok = dokploy("GET", "settings.getDokployVersion", "", b"")[0] == 200
    coolify_ok = coolify("GET", "version", "", b"")[0] == 200
    return {"platform": "dokploy" if dokploy_ok else "coolify" if coolify_ok else "none", "dokploy": dokploy_ok, "coolify": coolify_ok,
            "docker": bool(run("docker", "version", "--format", "{{.Server.Version}}", timeout=10).strip()),
            "fail2ban": bool(shutil.which("fail2ban-client")) and os.path.exists(F2B_DB), "crowdsec": cscli_cmd() is not None,
            "traefik": os.path.isdir(TRAEFIK_DYNAMIC) or "traefik" in images, "stalwart": bool(STALWART_ENV) and os.path.isfile(STALWART_ENV),
            "ssh": bool(NEVER_BLOCK), "version": VERSION}


ONE_ENDPOINT = {"applications": ("application.one", "applicationId"), "compose": ("compose.one", "composeId"),
                "postgres": ("postgres.one", "postgresId"), "redis": ("redis.one", "redisId")}


def projects_with_app_names():
    status, raw = dokploy("GET", "project.all", "", b"")
    if status != 200:
        return status, raw
    projects = json.loads(raw)
    for p in projects:
        for env in p.get("environments", []):
            for kind, (procedure, id_field) in ONE_ENDPOINT.items():
                for svc in env.get(kind) or []:
                    if svc.get("appName") or not svc.get(id_field):
                        continue
                    def lookup(procedure=procedure, id_field=id_field, sid=svc[id_field]):
                        code, body = dokploy("GET", procedure, urlencode({id_field: sid}), b"")
                        return json.loads(body).get("appName") if code == 200 else None
                    svc["appName"] = cached(f"appname:{svc[id_field]}", 86400, lookup)
    return 200, json.dumps(projects).encode()


def containers_with_app_names():
    status, raw = dokploy("GET", "docker.getContainers", "", b"")
    if status != 200:
        return status, raw
    owners = {}
    for c in inspect_all():
        labels = c["Config"].get("Labels") or {}
        owners[c["Name"].lstrip("/")] = labels.get("com.docker.swarm.service.name") or labels.get("com.docker.compose.project")
    rows = json.loads(raw)
    for row in rows:
        row["appName"] = owners.get(row.get("name"))
    return 200, json.dumps(rows).encode()


PUBLIC_IP = os.environ.get("HELM_PUBLIC_IP", "")
ACME_JSON = f"{TRAEFIK_DYNAMIC}/acme.json"
CONFIG_FIXED = ("/etc/docker/daemon.json", "/etc/dokploy/traefik/traefik.yml")
REBOOT_TIMER = "helm-quiet-reboot.timer"
REQUIRED = "/var/run/reboot-required"
AGENT_BIN = "/usr/local/bin/helm-agent"
ENV_FILE = "/etc/helm-agent/env"
UPDATES_FLAG = f"{STATE}/updates.running"
SCANNER = re.compile(r"(?i)/\.env|/\.git|wp-|xmlrpc|\.php|cgi-bin|actuator|server-status|phpmyadmin|boaform|HNAP1|/owa/|\.aws|\.ssh")
SKIP_REASONS = {
    "not_required": ("The update no longer needs a restart", "La mise à jour ne demande plus de redémarrage"),
    "already_rebooted": ("Already restarted for these updates", "Déjà redémarré pour ces mises à jour"),
    "recent_reboot": ("An automatic restart already happened in the last 7 days", "Un redémarrage automatique a déjà eu lieu ces 7 derniers jours"),
    "reboot_cap": ("3 automatic restarts in 90 days, restart manually", "3 redémarrages automatiques en 90 jours, redémarrez manuellement"),
    "traffic_spike": ("Visitors are on the site right now, retrying tomorrow", "Des visiteurs sont sur le site en ce moment, nouvel essai demain"),
    "apt_busy": ("Another update is running, retrying tomorrow", "Une autre mise à jour est en cours, nouvel essai demain"),
    "deploy_busy": ("A deployment or update was still running, retrying tomorrow", "Un déploiement ou une mise à jour était encore en cours, nouvel essai demain"),
}
DOMAIN = re.compile(r"(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}")
SERVICE_ID = re.compile(r"[A-Za-z0-9_.-]{1,128}")
STARTUTC = re.compile(rb'"StartUTC":\s*"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)')
UPGRADABLE = re.compile(r"^([^/\s]+)/(\S+) (\S+) \S+ \[upgradable from: (\S+)\]", re.M)
CLEAN_ITEMS = ("build_cache", "dangling_images", "unused_images", "stopped_containers")
updates_running = threading.Event()
clean_lock = threading.Lock()
watch_state: dict = {"docker_started": None}
log_since: dict = {}
error_windows: dict = {}


def ev(kind, title, message, title_fr, message_fr, **extra):
    return {**fr(title_fr, message_fr), "kind": kind, "title": title, "message": message, **extra}


def human(n, french=False):
    units = ("o", "Ko", "Mo", "Go", "To") if french else ("B", "KB", "MB", "GB", "TB")
    n, i = float(n), 0
    while n >= 1000 and i < 4:
        n, i = n / 1000, i + 1
    text = f"{n:.1f}" if i else str(int(n))
    return f"{text.replace('.', ',') if french else text}\u202f{units[i]}"


def percentile(values, p):
    if not values:
        return 0
    ordered = sorted(values)
    return ordered[max(0, math.ceil(p / 100 * len(ordered)) - 1)]


def task_name(name):
    return re.sub(r"\.\d+\.[a-z0-9]{25}$", "", name)


def line_at(text, match):
    return text[text.rfind("\n", 0, match.start()) + 1:(text.find("\n", match.end()) % (len(text) + 1) or len(text))].strip()[:200]


def every(seconds, delay, fn):
    time.sleep(delay)
    while True:
        try:
            fn()
        except Exception as e:
            print(f"{fn.__name__}: {type(e).__name__}: {e}", file=sys.stderr)
        time.sleep(seconds)


def routed_hosts():
    hosts = set()
    for name in os.listdir(TRAEFIK_DYNAMIC) if os.path.isdir(TRAEFIK_DYNAMIC) else []:
        if name.endswith(".yml"):
            with open(os.path.join(TRAEFIK_DYNAMIC, name)) as f:
                hosts.update(re.findall(r"Host\(`([a-z0-9.-]+)`\)", f.read()))
    for c in inspect_all():
        for k, v in (c["Config"].get("Labels") or {}).items():
            if k.endswith(".rule"):
                hosts.update(re.findall(r"Host\(`([a-z0-9.-]+)`\)", v))
    return hosts


def cloudflare_ranges():
    try:
        with open(f"{PERIMETER}/cloudflare.txt") as f:
            found = [l.strip() for l in f if l.strip() and not l.startswith("#")]
        nets = [str(ipaddress.ip_network(n)) for n in found]
        if any(":" not in n for n in nets):
            return nets
    except (OSError, ValueError):
        pass
    return list(CLOUDFLARE)


def cf_networks():
    return cached("cf_networks", 600, lambda: [ipaddress.ip_network(n) for n in cloudflare_ranges()])


def access_log():
    found = [p for p in ACCESS_LOGS if os.path.exists(p)]
    return max(found, key=os.path.getmtime) if found else ACCESS_LOGS[-1]


def header(entry, name):
    name = f"request_{name}".lower()
    return next((str(v) for k, v in entry.items() if k.lower() == name), "")


def real_ip(entry):
    ip = str(entry.get("ClientHost", ""))
    forwarded = header(entry, "Cf-Connecting-Ip")
    return forwarded if forwarded and in_networks(ip, cf_networks()) else ip


def in_networks(ip, nets):
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return False
    return any(addr.version == n.version and addr in n for n in nets)


def server_ip():
    m = re.search(r"\bsrc (\S+)", run("ip", "route", "get", "1.1.1.1"))
    return m.group(1) if m and ipaddress.ip_address(m.group(1)).is_global else PUBLIC_IP


def resolve(host, family):
    try:
        return sorted({a[4][0] for a in socket.getaddrinfo(host, None, family, socket.SOCK_STREAM)})
    except OSError:
        return []


def access_lines(seconds, scan_cap=512 << 20):
    cutoff = time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(time.time() - seconds)).encode()
    try:
        f = open(access_log(), "rb")
    except OSError:
        return
    with f:
        pos, buf, scanned = f.seek(0, 2), b"", 0
        while pos > 0 and scanned < scan_cap:
            step = min(1 << 20, pos)
            pos -= step
            scanned += step
            f.seek(pos)
            lines = (f.read(step) + buf).split(b"\n")
            buf = lines[0] if pos > 0 else b""
            for line in reversed(lines[1:] if pos > 0 else lines):
                m = STARTUTC.search(line)
                if m is None:
                    continue
                if m.group(1) < cutoff:
                    return
                yield m.group(1), line


def banned_ips():
    try:
        out = run("fail2ban-client", "banned")
    except OSError:
        return set()
    ips = set()
    for item in re.findall(r"'([^']+)'", out):
        try:
            ips.add(str(ipaddress.ip_address(item)))
        except ValueError:
            pass
    return ips


def blocklist_ranges():
    try:
        data = json.loads(run("nft", "-j", "list", "set", "inet", "blocklist", "v4", timeout=30))
    except (ValueError, OSError):
        return [], []
    spans = []
    for block in data.get("nftables", []):
        for e in (block.get("set") or {}).get("elem", []):
            if isinstance(e, dict) and "elem" in e:
                e = e["elem"]["val"]
            try:
                if isinstance(e, str):
                    a = b = int(ipaddress.ip_address(e))
                elif "prefix" in e:
                    net = ipaddress.ip_network(f"{e['prefix']['addr']}/{e['prefix']['len']}")
                    a, b = int(net[0]), int(net[-1])
                else:
                    a, b = (int(ipaddress.ip_address(x)) for x in e["range"])
            except (ValueError, KeyError, TypeError):
                continue
            spans.append((a, b))
    merged = []
    for a, b in sorted(spans):
        if merged and a <= merged[-1][1] + 1:
            merged[-1] = (merged[-1][0], max(merged[-1][1], b))
        else:
            merged.append((a, b))
    return [m[0] for m in merged], [m[1] for m in merged]


def ip_blocked(ip, starts, ends):
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return False
    if addr.version != 4:
        return False
    i = bisect.bisect_right(starts, int(addr)) - 1
    return i >= 0 and ends[i] >= int(addr)


def count_real(lines, banned, starts, ends, observe=None):
    per_ip, cells = {}, {}
    for ts, line in lines:
        try:
            e = json.loads(line)
        except ValueError:
            continue
        if observe:
            observe(ts, e)
        ip = str(e.get("ClientHost", ""))
        seen = per_ip.setdefault(ip, [0, 0])
        seen[0] += 1
        seen[1] += e.get("DownstreamStatus") == 404
        cell = cells.setdefault((ts[:10].decode(), int(ts[11:13]) * 2 + (int(ts[14:16]) >= 30), ip), [0, 0])
        cell[0] += 1
        cell[1] += SCANNER.search(str(e.get("RequestPath", ""))) is None
    excluded = {ip for ip, (n, n404) in per_ip.items() if (n >= 20 and n404 / n >= 0.5) or ip in banned or ip_blocked(ip, starts, ends)}
    out = {}
    for (day, slot, ip), (n, clean) in cells.items():
        total = out.setdefault((day, slot), [0, 0])
        total[1] += n
        if ip not in excluded:
            total[0] += clean
    return out


def traffic_pass():
    state = load_json("access_pos.json", {})
    log = access_log()
    try:
        st = os.stat(log)
    except OSError:
        return
    offset = state.get("offset", 0) if state.get("inode") == st.st_ino and state.get("offset", 0) <= st.st_size else 0
    offset = max(offset, st.st_size - (64 << 20))
    consumed = [offset]
    def new_lines():
        with open(log, "rb") as f:
            f.seek(offset)
            for raw in f:
                if not raw.endswith(b"\n"):
                    return
                consumed[0] += len(raw)
                m = STARTUTC.search(raw)
                if m:
                    yield m.group(1), raw
    banned, (starts, ends) = banned_ips(), blocklist_ranges()
    observe, finish = probe_collector(cf_networks())
    cells = count_real(new_lines(), banned, starts, ends, observe)
    with db() as con:
        finish(con)
        for (day, slot), (real, total) in cells.items():
            con.execute("INSERT INTO traffic_slots VALUES(?,?,?,?) ON CONFLICT(day, slot) DO UPDATE SET real=real+excluded.real, total=total+excluded.total", (day, slot, real, total))
        con.execute("DELETE FROM traffic_slots WHERE day < date('now','-30 days')")
    save_json("access_pos.json", {"inode": st.st_ino, "offset": consumed[0]})


def backup_slots():
    status, raw = cached("proxy:project.all:", 10, projects_with_app_names)
    found, stack = set(), [json.loads(raw)] if status == 200 else []
    while stack:
        node = stack.pop()
        if isinstance(node, dict):
            if "backupId" in node and isinstance(node.get("schedule"), str):
                try:
                    minutes, hours = cron_parse(node["schedule"])[0][:2]
                except ValueError:
                    continue
                if len(hours) < 24:
                    for h in hours:
                        for m in minutes:
                            found |= {h * 2 + m // 30, (h * 2 + m // 30 + 1) % 48}
            stack.extend(node.values())
        elif isinstance(node, list):
            stack.extend(node)
    return found


def slot_name(slot):
    return f"{slot // 2:02d}:{30 * (slot % 2):02d}"


def quiet_profile():
    today = time.strftime("%Y-%m-%d", time.gmtime())
    with db() as con:
        rows = con.execute("SELECT day, slot, real, total FROM traffic_slots WHERE day >= date('now','-14 days') AND day < ?", (today,)).fetchall()
    days = sorted({r["day"] for r in rows})
    days = days[1:] if len(days) > 3 else days
    real, total = [0] * 48, [0] * 48
    for r in rows:
        if r["day"] in days:
            real[r["slot"]] += r["real"]
            total[r["slot"]] += r["total"]
    n = len(days)
    average = [x / n for x in real] if n else [0.0] * 48
    learning = n < 3
    if learning:
        slot = 9
    else:
        allowed = [s for s in range(48) if s not in {8} | backup_slots()] or list(range(48))
        slot = min(allowed, key=lambda s: (average[s], abs(s - 9)))
    by_hour = lambda v: [round((v[2 * h] + v[2 * h + 1]) / n) if n else 0 for h in range(24)]
    return {"slot": slot, "quietSlot": slot_name(slot), "learning": learning, "learningDays": n, "trafficByHour": by_hour(real), "allByHour": by_hour(total), "average": average}


def next_slot(slot, earliest):
    t = datetime.fromtimestamp(earliest, timezone.utc).replace(hour=slot // 2, minute=30 * (slot % 2), second=0, microsecond=0)
    return int((t if t.timestamp() >= earliest else t + timedelta(days=1)).timestamp())


def real_recent(minutes=15):
    starts, ends = blocklist_ranges()
    return sum(c[0] for c in count_real(access_lines(minutes * 60), banned_ips(), starts, ends).values())


def apt_pending():
    env = {**os.environ, "LC_ALL": "C"}
    listing = subprocess.run(["apt", "list", "--upgradable"], capture_output=True, text=True, env=env, timeout=60).stdout
    held = [l.strip() for l in run("apt-mark", "showhold").splitlines() if l.strip()]
    pending = []
    for name, origins, new, old in UPGRADABLE.findall(listing):
        group = "held" if name in held else "security" if "-security" in origins else "system"
        pending.append({"name": name, "from": old, "to": new, "group": group})
    return pending[:300], held


def rb_load():
    return {"autoReboot": False, "postponements": 0, "skips": [], "history": [], **load_json("reboot.json", {})}


def rb_save(**changes):
    with lock:
        state = {**rb_load(), **changes}
        save_json("reboot.json", state)
        return state


def required_hash():
    try:
        with open(REQUIRED + ".pkgs") as f:
            pkgs = sorted({l.strip() for l in f if l.strip()})
    except OSError:
        pkgs = []
    return hashlib.sha256("\n".join(pkgs).encode()).hexdigest()[:16]


def deadline(rb):
    since = rb.get("required_since")
    return since + (24 if rb.get("security") else 72) * 3600 if since else None


def reboot_final(rb, now):
    limit = deadline(rb)
    return bool(limit) and (now >= limit or rb["postponements"] >= 2)


def stop_timer():
    subprocess.run(["systemctl", "stop", REBOOT_TIMER, "helm-quiet-reboot.service"], capture_output=True, timeout=20)


def timer_active():
    return run("systemctl", "is-active", REBOOT_TIMER).strip() == "active"


def timer_time():
    m = re.search(r"(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d) UTC", run("systemctl", "show", REBOOT_TIMER, "-p", "NextElapseUSecRealtime", "--value"))
    return int(datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S").replace(tzinfo=timezone.utc).timestamp()) if m else None


def outbox(event):
    fd = os.open(f"{STATE}/outbox.jsonl", os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
    with os.fdopen(fd, "a") as f:
        f.write(json.dumps(event) + "\n")


def outbox_pass():
    src = f"{STATE}/outbox.jsonl"
    try:
        os.replace(src, src + ".read")
    except OSError:
        return
    with open(src + ".read") as f:
        for line in f:
            try:
                publish("system", json.loads(line))
            except ValueError:
                pass
    os.remove(src + ".read")


def reboot_reason():
    try:
        with open(REQUIRED + ".pkgs") as f:
            pkgs = sorted({l.strip() for l in f if l.strip()})
    except OSError:
        pkgs = []
    kernels = sorted({m.group(1) for p in pkgs if (m := re.match(r"linux-image-(\d+\.\d+\.\d+-\d+)", p))})
    others = [p for p in pkgs if not p.startswith("linux-")][:3]
    def join(parts, word):
        return parts[0] if len(parts) == 1 else f"{', '.join(parts[:-1])} {word} {parts[-1]}"
    en = [f"New kernel {k}" for k in kernels] + others
    fr_parts = [f"Nouveau noyau {k}" for k in kernels] + others
    if not en:
        return "Some updates need a restart to take effect", "Certaines mises à jour demandent un redémarrage"
    return join(en, "and") + (" are installed" if len(en) > 1 else " is installed"), join(fr_parts, "et") + (" installés" if len(en) > 1 else " installé")


def reboot_status():
    rb, now = rb_load(), time.time()
    q = quiet_profile()
    plan = rb.get("plan")
    upcoming = plan["at"] if plan and plan["at"] > now and timer_active() else None
    since = rb.get("required_since") if os.path.exists(REQUIRED) else None
    return {"autoReboot": rb["autoReboot"], "nextReboot": upcoming, "scheduledReboot": upcoming, "rebootPlan": plan["kind"] if upcoming else None,
            "lastReboot": rb.get("last"), "skippedReasons": rb["skips"], "quietSlot": q["quietSlot"], "quietHour": q["slot"] // 2, "learning": q["learning"],
            "learningDays": q["learningDays"], "trafficByHour": q["trafficByHour"], "allByHour": q["allByHour"], "rebootRequired": os.path.exists(REQUIRED),
            "requiredSince": since, "postponements": rb["postponements"] if since else 0, "deadline": deadline(rb) if since else None, "final": bool(since and reboot_final(rb, now))}


def updates_center():
    pending, held = apt_pending()
    reason, reason_fr = reboot_reason()
    return {"pending": pending, "held": held, "rebootReason": reason, "rebootReasonFr": reason_fr, "runningKernel": os.uname().release,
            "unattended": unattended_status(), **needrestart_status()}


def updates_job():
    try:
        pending = apt_pending()[0]
        count = sum(1 for p in pending if p["group"] != "held")
        open(UPDATES_FLAG, "w").close()
        publish("system", ev("updates_started", "Updating the server", f"Installing {count} updates", "Mise à jour du serveur", f"Installation de {count} mises à jour", count=count))
        env = {**os.environ, "DEBIAN_FRONTEND": "noninteractive", "NEEDRESTART_MODE": "l"}
        proc = subprocess.run(["apt-get", "-y", "-o", "Dpkg::Options::=--force-confold", "-o", "DPkg::Lock::Timeout=300", "upgrade"],
                              capture_output=True, text=True, errors="replace", env=env, timeout=3600)
        if proc.returncode:
            tail = (proc.stderr.strip().splitlines() or ["apt-get failed"])[-1][:200]
            publish("system", ev("updates_failed", "Server update failed", tail, "La mise à jour du serveur a échoué", tail))
        else:
            m = re.search(r"(\d+) upgraded", proc.stdout)
            done = int(m.group(1)) if m else 0
            restart = os.path.exists(REQUIRED)
            if any(p["group"] == "security" for p in pending):
                rb_save(security_batch_at=int(time.time()))
            publish("system", ev("updates_done", "Server updated", f"{done} packages updated" + (". A restart is needed." if restart else ""),
                                 "Serveur à jour", f"{done} paquets mis à jour" + (". Un redémarrage est nécessaire." if restart else ""), count=done, rebootRequired=restart))
    except (OSError, subprocess.SubprocessError) as e:
        publish("system", ev("updates_failed", "Server update failed", str(e)[:200], "La mise à jour du serveur a échoué", str(e)[:200]))
    finally:
        try:
            os.remove(UPDATES_FLAG)
        except OSError:
            pass
        cache.pop("pending", None)
        cache.pop("updates", None)
        try:
            cache["updates_center"] = (time.time(), updates_center())
        except (OSError, subprocess.SubprocessError, ValueError):
            cache.pop("updates_center", None)
        updates_running.clear()
        threading.Thread(target=audit_run, args=("host_patch",), kwargs={"host_only": True}, daemon=True).start()


def updates_install():
    with lock:
        if updates_running.is_set():
            raise ValueError("Updates are already running")
        updates_running.set()
    threading.Thread(target=updates_job, daemon=True).start()


def schedule_timer(at, kind, final=False):
    stop_timer()
    rb_save(plan={"at": at, "kind": kind, "final": final})
    stamp = time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime(at))
    proc = subprocess.run(["systemd-run", "--unit=helm-quiet-reboot", f"--on-calendar={stamp} UTC", "/usr/bin/python3", "-I", AGENT_BIN, "reboot-if-safe"],
                          capture_output=True, text=True, timeout=20)
    if proc.returncode:
        rb_save(plan=None)
        raise ValueError(f"The reboot could not be scheduled: {proc.stderr.strip()[:200]}")
    when = f"{stamp[:16]} UTC"
    if kind == "auto":
        publish("system", ev("reboot_scheduled", "Reboot scheduled", f"Automatic restart on {when} to finish the updates", "Redémarrage programmé", f"Redémarrage automatique le {when} pour terminer les mises à jour", at=at, plan=kind))
    else:
        publish("system", ev("reboot_scheduled", "Reboot scheduled", f"The server restarts on {when}", "Redémarrage programmé", f"Le serveur redémarre le {when}", at=at, plan=kind))


def reboot_schedule(at):
    now = time.time()
    if at == "quiet":
        at = next_slot(quiet_profile()["slot"], now + 1800)
    waiting = os.path.exists(REQUIRED)
    if isinstance(at, bool) or not isinstance(at, int) or not now + 60 <= at <= now + (48 * 3600 if waiting else 30 * 86400):
        raise ValueError("Choose a reboot time between 1 minute and 48 hours from now" if waiting else "Choose a reboot time between 1 minute and 30 days from now")
    schedule_timer(at, "manual")
    rb_save(hold_until=0)
    return at


def reboot_cancel(confirm):
    rb, now = rb_load(), time.time()
    plan = rb.get("plan")
    waiting = os.path.exists(REQUIRED)
    overdue = bool(plan and waiting and (plan.get("final") or reboot_final(rb, now)))
    if overdue and confirm != "postpone":
        raise ValueError("This reboot is overdue. Send confirm postpone to cancel it")
    if overdue and rb.get("overdue_cancel_hash") == rb.get("required_hash"):
        raise ValueError("This overdue reboot was already postponed once")
    stop_timer()
    changes = {"plan": None, "hold_until": now + (48 if overdue else 24) * 3600}
    if overdue:
        changes["overdue_cancel_hash"] = rb.get("required_hash")
    elif waiting:
        changes["postponements"] = rb["postponements"] + 1
    rb_save(**changes)
    publish("system", ev("reboot_cancelled", "Reboot cancelled", "The scheduled restart was removed", "Redémarrage annulé", "Le redémarrage programmé a été supprimé"))


def set_auto_reboot(enabled):
    changes = {"autoReboot": enabled}
    if enabled:
        changes["rebooted_for_hash"] = None
    elif (rb_load().get("plan") or {}).get("kind") == "auto":
        stop_timer()
        changes["plan"] = None
    rb_save(**changes)


def reboot_plan_pass():
    rb, now = rb_load(), time.time()
    if rb.get("followup"):
        return
    plan, active = rb.get("plan"), timer_active()
    if not plan and active and (at := timer_time()):
        schedule_timer(at, "manual")
        return
    if plan and (plan["at"] < now - 3 * 3600 or (plan["at"] > now and not active)):
        plan = None
        rb = rb_save(plan=None)
    if not os.path.exists(REQUIRED):
        if plan and plan["kind"] == "auto":
            stop_timer()
            plan = None
        rb_save(plan=plan, required_hash=None, required_since=None, postponements=0, skips=[], security=False, rebooted_for_hash=None)
        return
    h = required_hash()
    if rb.get("required_hash") != h:
        rb = rb_save(required_hash=h, required_since=int(now), postponements=0, skips=[], hold_until=0,
                     security=rb.get("security_batch_at", 0) > now - 3 * 86400)
    if not rb.get("security") and any(p["group"] == "security" for p in cached("pending", 600, apt_pending)[0]):
        rb = rb_save(security=True)
    final = reboot_final(rb, now)
    if plan:
        when = time.strftime("%H:%M", time.gmtime(plan["at"]))
        if now < plan["at"] <= now + 1800:
            notice_once(f"reboot_soon:{plan['at']}", 86400, "system", ev("reboot_soon", "Server restarts soon", f"The restart is planned at {when} UTC", "Redémarrage imminent", f"Le redémarrage est prévu à {when} UTC", at=plan["at"]))
        if now < plan["at"] <= now + 7200 and (plan.get("final") or final):
            notice_once(f"reboot_final:{plan['at']}", 86400, "system", ev("reboot_final", "Reboot overdue", f"Reboot overdue, it will happen at {when}, cannot be postponed automatically",
                                                                          "Redémarrage en retard", f"Redémarrage en retard, il aura lieu à {when}, il ne peut plus être reporté automatiquement", at=plan["at"]))
        return
    recent = [t for t in rb["history"] if t > now - 90 * 86400]
    if not rb["autoReboot"] or h == rb.get("rebooted_for_hash") or now < rb.get("hold_until", 0) or len(recent) >= 3:
        return
    earliest = max(now + 7200, max(recent) + 7 * 86400 if recent and max(recent) > now - 7 * 86400 else 0)
    schedule_timer(next_slot(quiet_profile()["slot"], earliest), "auto", final)


def apt_locked():
    try:
        with open("/var/lib/dpkg/lock-frontend", "a") as f:
            fcntl.lockf(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
            fcntl.lockf(f, fcntl.LOCK_UN)
        return False
    except OSError:
        return True


def deploy_running():
    code, body = dokploy("GET", "deployment.allCentralized", "", b"")
    if code != 200:
        return False
    rows = json.loads(body)
    for d in rows if isinstance(rows, list) else []:
        if isinstance(d, dict) and d.get("status") == "running":
            try:
                if time.time() - iso_time(d["createdAt"]).timestamp() > 7200:
                    continue
            except (KeyError, ValueError):
                pass
            return True
    return False


def reboot_busy(include_apt):
    stale = os.path.exists(UPDATES_FLAG) and time.time() - os.path.getmtime(UPDATES_FLAG) > 3 * 3600
    return deploy_running() or (os.path.exists(UPDATES_FLAG) and not stale) or (include_apt and apt_locked())


def reboot_if_safe():
    try:
        with open(ENV_FILE) as f:
            for line in f:
                key, sep, value = line.strip().partition("=")
                if sep and key and not key.startswith("#"):
                    os.environ.setdefault(key, value.strip().strip('"'))
    except OSError:
        pass
    init_db()
    rb, now = rb_load(), time.time()
    plan = rb.get("plan") or {}
    manual = plan.get("kind") == "manual"
    present, h = os.path.exists(REQUIRED), required_hash()
    recent = [t for t in rb["history"] if t > now - 90 * 86400]
    final = bool(plan.get("final")) or reboot_final(rb, now)
    def verdict():
        if not manual:
            if not present:
                return "not_required", False
            if h == rb.get("rebooted_for_hash"):
                return "already_rebooted", False
            if recent and max(recent) > now - 7 * 86400:
                return "recent_reboot", False
            if len(recent) >= 3:
                return "reboot_cap", False
        limit = plan.get("at", now) + 7200
        while (busy := reboot_busy(final or manual)) and time.time() < limit:
            time.sleep(120)
        if busy:
            return "deploy_busy", not final
        if not final and not manual:
            if apt_locked():
                return "apt_busy", True
            if real_recent() > max(10, quiet_profile()["average"][time.gmtime().tm_hour * 2 + (time.gmtime().tm_min >= 30)]):
                return "traffic_spike", True
        return None, False
    reason, postpone = verdict()
    stamp = int(time.time())
    if reason:
        changes = {"plan": None, "skips": (rb["skips"] + [{"at": stamp, "reason": reason}])[-10:], "last": {"at": stamp, "forHash": h if present else None, "result": f"skipped:{reason}"}}
        if postpone:
            changes["postponements"] = rb["postponements"] + 1
        rb_save(**changes)
        en, fr_text = SKIP_REASONS[reason]
        outbox(ev("reboot_skipped", "Reboot postponed", en, "Redémarrage reporté", fr_text, reason=reason))
        return
    for_hash = h if present else None
    rb_save(plan=None, rebooted_for_hash=for_hash or rb.get("rebooted_for_hash"), history=recent if manual else recent + [stamp],
            followup={"hash": for_hash, "at": stamp}, last={"at": stamp, "forHash": for_hash, "result": "rebooting"})
    subprocess.run(["systemctl", "reboot"], timeout=60)


def reboot_followup():
    follow = rb_load().get("followup")
    if not follow:
        return
    with open("/proc/uptime") as f:
        up = float(f.read().split()[0])
    if up > 900:
        rb_save(followup=None)
        return
    time.sleep(120)
    with db() as con:
        counts = {r["state"]: r["n"] for r in con.execute("SELECT state, count(*) n FROM services GROUP BY state")}
    stamp = int(time.time())
    if follow.get("hash") and os.path.exists(REQUIRED) and required_hash() == follow["hash"]:
        rb_save(followup=None, autoReboot=False, last={"at": stamp, "forHash": follow["hash"], "result": "ineffective"})
        publish("system", ev("reboot_ineffective", "Reboot did not help", "Restarted but the update still needs a reboot. Automatic reboots paused, check the server.",
                             "Le redémarrage n’a rien changé", "Redémarré, mais la mise à jour demande toujours un redémarrage. Redémarrages automatiques en pause, vérifiez le serveur."))
        return
    rb_save(followup=None, last={"at": stamp, "forHash": follow.get("hash"), "result": "ok"})
    up_n, down_n = counts.get("up", 0), counts.get("down", 0)
    publish("system", ev("reboot_done", "Server restarted", f"{up_n} services up, {down_n} down", "Serveur redémarré", f"{up_n} services en marche, {down_n} en panne"))


UU_LOG = "/var/log/unattended-upgrades/unattended-upgrades.log"
UU_START = re.compile(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d),\d+ INFO Starting unattended upgrades script", re.M)
DB_IMAGE = re.compile(r"^(mariadb|mysql|percona|postgres|postgresql|postgis|timescaledb|redis|valkey|keydb|mongo|mongodb)\b", re.I)
DB_KINDS = {"postgres", "mysql", "mariadb", "mongo", "redis", "database"}
DOKPLOY_KINDS = (("applications", "application"), ("compose", "compose"), ("postgres", "postgres"), ("mysql", "mysql"),
                 ("mariadb", "mariadb"), ("mongo", "mongo"), ("redis", "redis"))
AUTOFIX_KINDS = {"dokploy": {"application", "compose"}, "coolify": {"application", "service"}}
AUTOFIX_DEPLOY_TIMEOUT = 1800
AUTOFIX_HEALTH_TIMEOUT = 300
AUTOFIX_POLL = 15
AUTOFIX_CAUSES = {"error": ("the deployment failed", "le déploiement a échoué"), "timeout": ("the deployment did not finish in 30 min", "le déploiement n’a pas fini en 30 min"),
                  "refused": ("the platform refused the redeploy", "la plateforme a refusé le redéploiement"),
                  "unhealthy": ("the containers did not become healthy", "les conteneurs ne sont pas redevenus sains"), "http": ("the site answers with an error", "le site répond par une erreur")}
audit_lock = threading.Lock()
audit_state = {"manualAt": 0}


def unattended_status():
    installed = bool(shutil.which("unattended-upgrade"))
    out = {"installed": installed, "enabled": installed and '"1"' in run("apt-config", "dump", "APT::Periodic::Unattended-Upgrade"),
           "lastRun": None, "upgraded": [], "keptBack": [], "ok": None, "error": None}
    try:
        with open(UU_LOG, "rb") as f:
            f.seek(max(0, os.path.getsize(UU_LOG) - (256 << 10)))
            text = f.read().decode(errors="replace")
    except OSError:
        return out
    starts = list(UU_START.finditer(text))
    if not starts:
        return out
    block = text[starts[-1].start():]
    out["lastRun"] = int(time.mktime(time.strptime(starts[-1].group(1), "%Y-%m-%d %H:%M:%S")))
    for label, key in (("Packages that will be upgraded: ", "upgraded"), ("Packages that are kept back: ", "keptBack")):
        m = re.search(re.escape(label) + r"(.*)", block)
        out[key] = m.group(1).split()[:100] if m else []
    error = re.search(r" (?:ERROR|CRITICAL) (.*)", block)
    out["ok"], out["error"] = error is None, error.group(1)[:200] if error else None
    return out


def needrestart_status():
    out = run("needrestart", "-b", "-r", "l", timeout=120) if shutil.which("needrestart") else ""
    kernel, microcode = re.search(r"NEEDRESTART-KSTA: (\d)", out), re.search(r"NEEDRESTART-UCSTA: (\d)", out)
    return {"kernelPending": bool(kernel and kernel.group(1) in "23"), "microcodePending": bool(microcode and microcode.group(1) == "2")}


def trivy_run(args, timeout):
    cache_dir = f"{STATE}/trivy"
    os.makedirs(cache_dir, mode=0o700, exist_ok=True)
    prefix = (["nice", "-n", "19"] if shutil.which("nice") else []) + (["ionice", "-c", "3"] if shutil.which("ionice") else [])
    # Trivy gets a bare environment: the agent's API keys and hook secret must not reach a third-party binary.
    env = {"PATH": os.environ.get("PATH", "/usr/sbin:/usr/bin:/sbin:/bin"), "HOME": cache_dir, "TMPDIR": cache_dir}
    cmd = ["trivy", *args] if args[0].startswith("-") else ["trivy", args[0], "--cache-dir", cache_dir, "--quiet", *args[1:]]
    proc = subprocess.run([*prefix, *cmd], capture_output=True, text=True, errors="replace", env=env, timeout=timeout)
    if proc.returncode:
        raise ValueError((proc.stderr.strip().splitlines() or ["trivy failed"])[-1][:200])
    return proc.stdout


def scan_findings(raw, limit=20):
    rows = {}
    for result in json.loads(raw or "{}").get("Results") or []:
        for v in result.get("Vulnerabilities") or []:
            severity = str(v.get("Severity", "")).lower()
            if severity in ("critical", "high"):
                rows.setdefault((v.get("VulnerabilityID"), v.get("PkgName")), {
                    "id": str(v.get("VulnerabilityID", ""))[:40], "package": str(v.get("PkgName", ""))[:80], "installed": str(v.get("InstalledVersion", ""))[:60],
                    "fixed": str(v.get("FixedVersion") or "")[:60] or None, "severity": severity, "fixable": bool(v.get("FixedVersion"))})
    found = sorted(rows.values(), key=lambda f: (f["severity"] != "critical", not f["fixable"], f["id"]))
    return {"counts": {s: sum(1 for f in found if f["severity"] == s) for s in ("critical", "high")},
            "fixable": {s: sum(1 for f in found if f["severity"] == s and f["fixable"]) for s in ("critical", "high")}, "findings": found[:limit]}


def running_containers():
    ids = run("docker", "ps", "-q").split()
    return json.loads(run("docker", "inspect", *ids, timeout=60) or "[]") if ids else []


def svc_key(svc):
    return f"{svc['platform']}:{svc['kind']}:{svc['id']}"


def service_owners():
    by_app, by_uuid = {}, {}
    status, raw = cached("proxy:project.all:", 10, projects_with_app_names)
    for p in json.loads(raw) if status == 200 else []:
        for env in p.get("environments", []):
            for field, kind in DOKPLOY_KINDS:
                for svc in env.get(field) or []:
                    if svc.get("appName") and svc.get(f"{kind}Id"):
                        by_app[svc["appName"]] = {"platform": "dokploy", "kind": kind, "id": svc[f"{kind}Id"], "name": svc.get("name") or svc["appName"]}
    for field, kind in (("applications", "application"), ("services", "service"), ("databases", "database")):
        status, raw = cached(f"coolify:{field}:", 60, lambda field=field: coolify("GET", field, "", b""))
        for svc in json.loads(raw) if status == 200 else []:
            if isinstance(svc, dict) and re.fullmatch(COOLIFY_ID, str(svc.get("uuid", ""))):
                by_uuid[svc["uuid"]] = {"platform": "coolify", "kind": kind, "id": svc["uuid"], "name": svc.get("name") or svc["uuid"]}
    return by_app, by_uuid


def owner_of(container, by_app, by_uuid):
    labels = container.get("Config", {}).get("Labels") or {}
    for label in ("com.docker.swarm.service.name", "com.docker.stack.namespace", "com.docker.compose.project"):
        if labels.get(label) in by_app:
            return by_app[labels[label]]
    name = container.get("Name", "").lstrip("/")
    return next((svc for uuid, svc in by_uuid.items() if uuid in name), None)


def image_repo(ref):
    return ref.split("@")[0].rsplit("/", 1)[-1].split(":")[0]


def critical_keys(report):
    return {f"{t['target']}|{f['id']}|{f['package']}" for t in report.get("targets", []) for f in t.get("findings", []) if f["severity"] == "critical" and f["fixable"]}


def audit_start():
    if not shutil.which("trivy"):
        raise ValueError("Trivy is not installed on the server")
    if audit_lock.locked():
        raise ValueError("A scan is already running")
    if time.time() - audit_state["manualAt"] < 600:
        raise ValueError("A scan ran less than 10 minutes ago, try again later")
    if reboot_busy(False):
        raise ValueError("A deployment or update is running, try again after it finishes")
    audit_state["manualAt"] = time.time()
    threading.Thread(target=audit_run, args=("manual",), daemon=True).start()


def audit_pass():
    last, now = load_json("audit.json", {}).get("at", 0), time.time()
    if shutil.which("trivy") and now - last > 20 * 3600 and (in_quiet_window(now) or now - last > 48 * 3600) and not reboot_busy(True):
        audit_run("daily")


def audit_run(trigger, now=None, host_only=False):
    if not shutil.which("trivy") or not audit_lock.acquire(blocking=False):
        return None
    try:
        now = now or time.time()
        prev = load_json("audit.json", {})
        db_error = interrupted = m = None
        try:
            m = re.search(r"Version: (\S+)", trivy_run(["--version"], 30))
            trivy_run(["image", "--download-db-only"], 900)
        except (ValueError, OSError, subprocess.SubprocessError) as e:
            db_error = str(e)[:200]
        def scan(args, timeout):
            try:
                return {**scan_findings(trivy_run([*args[:1], "--scanners", "vuln", "--severity", "HIGH,CRITICAL", "--format", "json", "--skip-db-update", *args[1:]], timeout)), "error": None}
            except (ValueError, OSError, subprocess.SubprocessError) as e:
                return {"counts": {"critical": 0, "high": 0}, "fixable": {"critical": 0, "high": 0}, "findings": [], "error": str(e)[:200] or type(e).__name__}
        targets = [{"kind": "host", "target": "host", **scan(["rootfs", "--pkg-types", "os", "--timeout", "30m",
                                                               "--skip-dirs", "/proc,/sys,/dev,/run,/var/lib/docker,/var/lib/containerd,/etc/dokploy,/data", "/"], 1900)}]
        if host_only:
            targets += [t for t in prev.get("targets", []) if t.get("kind") == "image"]
        else:
            by_app, by_uuid = service_owners()
            groups = {}
            for c in running_containers():
                g = groups.setdefault(c.get("Image", ""), {"ref": c.get("Config", {}).get("Image", ""), "containers": [], "service": None, "stateful": False})
                g["containers"].append(c.get("Name", "").lstrip("/"))
                g["service"] = g["service"] or owner_of(c, by_app, by_uuid)
                g["stateful"] |= any(mount.get("Type") == "volume" for mount in c.get("Mounts") or [])
            for image_id, g in groups.items():
                if deploy_running():
                    interrupted = "deploy"
                    break
                database = DB_IMAGE.match(image_repo(g["ref"])) is not None or (g["service"] or {}).get("kind") in DB_KINDS
                targets.append({"kind": "image", "target": g["ref"], "imageId": image_id[:19], "containers": g["containers"][:10], "service": g["service"],
                                "database": database, "stateful": g["stateful"], **scan(["image", "--image-src", "docker", "--timeout", "15m", g["ref"]], 1000)})
        total = lambda field, s: sum(t[field][s] for t in targets)
        day = time.strftime("%Y-%m-%d", time.gmtime(now))
        history = [h for h in prev.get("history", []) if h.get("day") != day][-29:] + [{"day": day, "critical": total("counts", "critical"), "high": total("counts", "high")}]
        report = {"at": int(now), "trigger": trigger, "trivy": m.group(1) if m else None, "dbError": db_error, "interrupted": interrupted, "targets": targets,
                  "counts": {s: total("counts", s) for s in ("critical", "high")}, "fixable": {s: total("fixable", s) for s in ("critical", "high")}, "history": history}
        save_json("audit.json", report)
        audit("agent", "vuln_scan", f"{trigger} critical={report['counts']['critical']} high={report['counts']['high']} db={'error' if db_error else 'ok'}", "local")
        new = sorted(critical_keys(report) - critical_keys(prev))
        if new:
            target, cve, pkg = new[0].split("|")
            publish("system", ev("vuln_critical", "Critical vulnerability", f"{len(new)} new critical issues with a fix, e.g. {cve} in {pkg} ({target})",
                                 "Faille critique", f"{len(new)} nouvelles failles critiques corrigeables, par ex. {cve} dans {pkg} ({target})", count=len(new)))
        if not host_only:
            autofix_pass(report, now)
        return report
    finally:
        audit_lock.release()


def in_quiet_window(now):
    t = time.gmtime(now)
    return (t.tm_hour * 2 + (t.tm_min >= 30) - quiet_profile()["slot"]) % 48 < 4


def autofix_save(key, **changes):
    with lock:
        data = load_json("autofix.json", {})
        data[key] = {**data.get(key, {}), **changes}
        save_json("autofix.json", data)
        return data[key]


def autofix_settings(data, who, ip):
    platform, kind, sid = str(data.get("platform", "")), str(data.get("kind", "")), str(data.get("id", ""))
    enabled, stateful = data.get("enabled"), data.get("allowStateful", False)
    if kind not in AUTOFIX_KINDS.get(platform, ()):
        raise ValueError("Only applications and compose services can be fixed automatically")
    if not SERVICE_ID.fullmatch(sid):
        raise ValueError("Bad service id")
    if not isinstance(enabled, bool) or not isinstance(stateful, bool):
        raise ValueError("Send enabled and allowStateful as true or false")
    key = f"{platform}:{kind}:{sid}"
    svc = next((s for owners in service_owners() for s in owners.values() if svc_key(s) == key), None)
    if svc is None:
        raise ValueError("Unknown service")
    if enabled and any(t.get("database") and t.get("service") and svc_key(t["service"]) == key for t in load_json("audit.json", {}).get("targets", [])):
        raise ValueError("Databases are never fixed automatically")
    audit(who, "autofix_settings", f"{key} enabled={enabled} allowStateful={stateful}", ip)
    return {"key": key, **autofix_save(key, **svc, enabled=enabled, allowStateful=enabled and stateful)}


def autofix_pass(report, now):
    services = {}
    for t in report.get("targets", []):
        if t.get("kind") == "image" and t.get("service"):
            s = services.setdefault(svc_key(t["service"]), {"svc": t["service"], "critical": [], "database": False, "stateful": False})
            s["database"] |= bool(t.get("database"))
            s["stateful"] |= bool(t.get("stateful"))
            s["critical"] += [f["id"] for f in t.get("findings", []) if f["severity"] == "critical" and f["fixable"]]
    settings = load_json("autofix.json", {})
    for key, s in services.items():
        cfg = settings.get(key, {})
        if not cfg.get("enabled") or not s["critical"] or now - cfg.get("lastAttempt", 0) < 86400:
            continue
        if os.environ.get("HELM_NO_AUTOFIX") == "1" or not in_quiet_window(now):
            return
        reason = "database" if s["database"] else "stateful" if s["stateful"] and not cfg.get("allowStateful") else None
        if reason is None and (reboot_busy(True) or real_recent() > max(10, quiet_profile()["average"][time.gmtime(now).tm_hour * 2 + (time.gmtime(now).tm_min >= 30)])):
            reason = "busy"
        if reason:
            autofix_save(key, last={"at": int(now), "result": f"skipped:{reason}"})
            audit("agent", "autofix_skipped", f"{key} {reason}", "local")
            continue
        autofix_one(key, s["svc"], s["critical"], now)


def service_hosts(svc):
    if svc["platform"] == "dokploy":
        proc = "domain.byApplicationId" if svc["kind"] == "application" else "domain.byComposeId"
        code, body = dokploy("GET", proc, urlencode({f"{svc['kind']}Id": svc["id"]}), b"")
        rows = json.loads(body) if code == 200 else []
        return [str(d["host"]) for d in rows if isinstance(d, dict) and DOMAIN.fullmatch(str(d.get("host", "")))]
    code, body = coolify("GET", f"applications/{svc['id']}", "", b"") if svc["kind"] == "application" else (0, b"")
    fqdn = str((json.loads(body) if code == 200 else {}).get("fqdn") or "")
    return [h for h in (urlparse(u.strip()).hostname for u in fqdn.split(",")) if h and DOMAIN.fullmatch(h)]


def http_status(host):
    try:
        with urllib.request.urlopen(urllib.request.Request(f"https://{host}/", headers={"User-Agent": "helm-agent"}), timeout=15) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except OSError:
        return 0


def deploy_state(svc, since, handle):
    if svc["platform"] == "coolify":
        code, body = coolify("GET", f"deployments/{handle}", "", b"") if handle else (0, b"")
        status = (json.loads(body) if code == 200 else {}).get("status")
        return "done" if status == "finished" else "error" if status in ("failed", "cancelled-by-user") else "pending"
    proc, field = ("deployment.all", "applicationId") if svc["kind"] == "application" else ("deployment.allByCompose", "composeId")
    code, body = dokploy("GET", proc, urlencode({field: svc["id"]}), b"")
    rows = [d for d in (json.loads(body) if code == 200 else []) if isinstance(d, dict) and d.get("createdAt") and iso_time(d["createdAt"]).timestamp() >= since - 60]
    status = max(rows, key=lambda d: iso_time(d["createdAt"])).get("status") if rows else None
    return status if status in ("done", "error") else "pending"


def wait_healthy(svc, host, before):
    end, streak, cause = time.time() + AUTOFIX_HEALTH_TIMEOUT, 0, "unhealthy"
    while time.time() < end:
        owners = service_owners()
        mine = [c for c in running_containers() if (o := owner_of(c, *owners)) and svc_key(o) == svc_key(svc)]
        good = bool(mine) and all(c["State"].get("Running") and not c["State"].get("Restarting")
                                  and (c["State"].get("Health") or {}).get("Status", "healthy") == "healthy" for c in mine)
        streak = streak + 1 if good else 0
        if streak >= 2:
            status = http_status(host) if host else None
            if status is None or 0 < status < 500 or status == before:
                return True, None
            cause = "http"
        time.sleep(AUTOFIX_POLL)
    return False, cause


def autofix_one(key, svc, critical, now):
    autofix_save(key, lastAttempt=int(now), last={"at": int(now), "result": "running"})
    audit("agent", "autofix_redeploy", f"{key} {','.join(critical[:5])}", "local")
    hosts = service_hosts(svc)
    host = hosts[0] if hosts else None
    before = http_status(host) if host else None
    rollback_id = None
    if svc["platform"] == "dokploy" and svc["kind"] == "application":
        code, body = dokploy("GET", "deployment.all", urlencode({"applicationId": svc["id"]}), b"")
        good = [d for d in (json.loads(body) if code == 200 else []) if isinstance(d, dict) and d.get("status") == "done" and d.get("rollbackId") and d.get("createdAt")]
        rollback_id = max(good, key=lambda d: iso_time(d["createdAt"]))["rollbackId"] if good else None
    started, handle = time.time(), None
    if svc["platform"] == "dokploy":
        code, body = dokploy("POST", f"{svc['kind']}.redeploy", "", json.dumps({f"{svc['kind']}Id": svc["id"]}).encode())
    else:
        code, body = coolify("GET", "deploy", urlencode({"uuid": svc["id"]}), b"")
        try:
            handle = next((d.get("deployment_uuid") for d in json.loads(body).get("deployments", []) if re.fullmatch(COOLIFY_ID, str(d.get("deployment_uuid", "")))), None)
        except (ValueError, AttributeError):
            handle = None
    state = "refused" if code >= 300 else "pending"
    while state == "pending" and time.time() < started + AUTOFIX_DEPLOY_TIMEOUT:
        time.sleep(AUTOFIX_POLL)
        state = deploy_state(svc, started, handle)
    state = "timeout" if state == "pending" else state
    cause, rolled = state, None
    if state == "done":
        ok, cause = wait_healthy(svc, host, before)
        if ok:
            autofix_save(key, last={"at": int(time.time()), "result": "done"})
            audit("agent", "autofix_done", key, "local")
            publish("system", ev("autofix_done", "Auto-fix applied", f"{svc['name']} redeployed and healthy", "Correctif automatique appliqué",
                                 f"{svc['name']} redéployé et en bonne santé", serviceId=svc["id"]))
            return
        if rollback_id:
            code, _ = dokploy("POST", "rollback.rollback", "", json.dumps({"rollbackId": rollback_id}).encode())
            rolled = code < 300 and wait_healthy(svc, host, before)[0]
    result = f"failed:{cause}" + ("" if rolled is None else ":rolled_back" if rolled else ":rollback_failed")
    autofix_save(key, last={"at": int(time.time()), "result": result})
    audit("agent", "autofix_failed", f"{key} {result}", "local")
    en, fr_text = AUTOFIX_CAUSES[cause]
    tail_en = " Rolled back to the previous version." if rolled else " Rollback failed, check it now." if rolled is False else " Nothing was rolled back."
    tail_fr = " Retour à la version précédente." if rolled else " Le retour arrière a échoué, vérifiez maintenant." if rolled is False else " Aucun retour arrière."
    publish("system", ev("autofix_failed", "Auto-fix failed", f"{svc['name']}: {en}.{tail_en}", "Échec du correctif automatique", f"{svc['name']} : {fr_text}.{tail_fr}",
                         serviceId=svc["id"], result=result))


def diff_counts(old, new):
    added = removed = 0
    for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, old, new, autojunk=False).get_opcodes():
        if tag != "equal":
            removed += i2 - i1
            added += j2 - j1
    return added, removed


def config_pass():
    paths = list(CONFIG_FIXED)
    paths += sorted(os.path.join(TRAEFIK_DYNAMIC, n) for n in (os.listdir(TRAEFIK_DYNAMIC) if os.path.isdir(TRAEFIK_DYNAMIC) else []) if n.endswith(".yml"))
    now = int(time.time())
    for path in paths:
        try:
            with open(path, errors="replace") as f:
                content = f.read(262144)
        except OSError:
            continue
        sha = hashlib.sha256(content.encode()).hexdigest()
        with db() as con:
            last = con.execute("SELECT sha256, content FROM config_versions WHERE path=? ORDER BY rowid DESC LIMIT 1", (path,)).fetchone()
            if last and last["sha256"] == sha:
                continue
            con.execute("INSERT INTO config_versions VALUES(?,?,?,?)", (path, now, sha, content))
            con.execute("DELETE FROM config_versions WHERE path=? AND rowid NOT IN (SELECT rowid FROM config_versions WHERE path=? ORDER BY rowid DESC LIMIT 50)", (path, path))
        if last:
            added, removed = diff_counts(last["content"].splitlines(), content.splitlines())
            base = os.path.basename(path)
            publish("system", ev("config_changed", f"{base} changed", f"+{added} −{removed} lines", f"{base} modifié", f"+{added} −{removed} lignes",
                                 path=path, critical=path in CONFIG_FIXED))
    started = run("systemctl", "show", "docker", "-p", "ActiveEnterTimestamp", "--value").strip()
    previous = watch_state["docker_started"]
    watch_state["docker_started"] = started or previous
    if started and previous and started != previous:
        with db() as con:
            edited = con.execute("SELECT 1 FROM config_versions WHERE path=? AND at>? AND rowid > (SELECT min(rowid) FROM config_versions WHERE path=?)",
                                 (CONFIG_FIXED[0], now - 600, CONFIG_FIXED[0])).fetchone() is not None
        publish("system", ev("docker_restarted", "Docker restarted", "daemon.json was changed in the 10 minutes before" if edited else "Its settings did not change",
                             "Docker a redémarré", "daemon.json a été modifié dans les 10 minutes précédentes" if edited else "Ses réglages n’ont pas changé", daemonConfigChanged=edited))


def watchdog_list():
    out = []
    with db() as con:
        for path in [r[0] for r in con.execute("SELECT DISTINCT path FROM config_versions ORDER BY path")]:
            rows = con.execute("SELECT at, sha256, content FROM config_versions WHERE path=? ORDER BY rowid DESC LIMIT 21", (path,)).fetchall()[::-1]
            changes, prev = [], None
            for r in rows:
                lines = r["content"].splitlines()
                if prev is not None:
                    added, removed = diff_counts(prev["lines"], lines)
                    changes.append({"at": r["at"], "sha": r["sha256"][:12], "prevSha": prev["sha"], "added": added, "removed": removed})
                prev = {"sha": r["sha256"][:12], "lines": lines}
            out.append({"path": path, "changes": changes[::-1]})
    return out


def watchdog_diff(path, a, b):
    with db() as con:
        def version(sha):
            if not re.fullmatch(r"[0-9a-f]{6,64}", sha):
                raise ValueError("Bad version")
            row = con.execute("SELECT content FROM config_versions WHERE path=? AND substr(sha256,1,?)=? ORDER BY rowid DESC LIMIT 1", (path, len(sha), sha)).fetchone()
            if row is None:
                raise ValueError("Unknown version")
            return row["content"].splitlines()
        old, new = version(a), version(b)
    diff = difflib.unified_diff(old, new, lineterm="", n=3)
    next(diff, None)
    next(diff, None)
    out, o, n = [], 0, 0
    for line in diff:
        if len(out) >= 500:
            break
        if line.startswith("@@"):
            m = re.match(r"@@ -(\d+)(?:,\d+)? \+(\d+)", line)
            o, n = int(m.group(1)), int(m.group(2))
            out.append({"type": "hunk", "text": line, "oldLine": None, "newLine": None})
        elif line[0] == "+":
            out.append({"type": "add", "text": line[1:], "oldLine": None, "newLine": n})
            n += 1
        elif line[0] == "-":
            out.append({"type": "del", "text": line[1:], "oldLine": o, "newLine": None})
            o += 1
        else:
            out.append({"type": "ctx", "text": line[1:], "oldLine": o, "newLine": n})
            o, n = o + 1, n + 1
    return out


def networks():
    listing = [json.loads(l) for l in run("docker", "network", "ls", "--format", "{{json .}}").splitlines()]
    ids = [n["ID"] for n in listing if n["Driver"] in ("overlay", "bridge")]
    if not ids:
        return []
    out = []
    for n in json.loads(run("docker", "network", "inspect", "-v", *ids, timeout=60) or "[]"):
        subnets = [c["Subnet"] for c in (n.get("IPAM") or {}).get("Config") or [] if c.get("Subnet") and ":" not in c["Subnet"]]
        if not subnets:
            continue
        net = ipaddress.ip_network(subnets[0], strict=False)
        ips = {c["IPv4Address"].split("/")[0] for c in (n.get("Containers") or {}).values() if c.get("IPv4Address")}
        for s in (n.get("Services") or {}).values():
            ips.add(str(s.get("VIP") or "").split("/")[0])
            ips |= {str(t.get("EndpointIP") or "").split("/")[0] for t in s.get("Tasks") or []}
        used = sum(1 for ip in ips if in_networks(ip, [net]))
        capacity = max(net.num_addresses - 2, 1)
        percent = round(100 * used / capacity, 1)
        out.append({"name": n["Name"], "driver": n["Driver"], "scope": n.get("Scope", ""), "subnet": str(net), "capacity": capacity, "used": used, "percentUsed": percent})
        if n["Driver"] == "overlay" and percent > 85:
            notice_once(f"net:{n['Name']}", 86400, "system", ev("network_exhausting", f"Network {n['Name']} is {percent:.0f}% full", f"{used} of {capacity} addresses are used. New services may fail to start.",
                                                                  f"Le réseau {n['Name']} est plein à {percent:.0f}\u202f%", f"{used} adresses sur {capacity} sont utilisées. De nouveaux services risquent de ne pas démarrer.", network=n["Name"]))
    return sorted(out, key=lambda r: -r["percentUsed"])


def incidents(days):
    days = max(1, min(days, 90))
    now = time.time()
    since = now - days * 86400
    items = []
    with db() as con:
        for r in con.execute("SELECT service, start, end FROM incidents WHERE start > ? ORDER BY start", (since,)):
            s = r["service"]
            items.append({"start": r["start"], "end": r["end"], "kind": "service_down", "title": f"{s} is down", "titleFr": f"{s} est hors service", "services": {s}, "open": r["end"] is None})
            if r["end"]:
                items.append({"start": r["end"], "end": r["end"], "kind": "service_up", "title": f"{s} is back", "titleFr": f"{s} est de retour", "services": {s}, "open": False})
    with lock:
        events = list(history)
    for e in events:
        kind = e.get("kind")
        if kind not in INCIDENT_CAUSES or e["at"] < since:
            continue
        service = e.get("service") or (re.search(r"(?i)(?:application|compose)\s*name\s*[:=]\s*(\S+)", e.get("message", "")) or [None, None])[1]
        items.append({"start": e["at"], "end": e["at"], "kind": kind, "title": e.get("title", ""), "titleFr": e.get("titleFr", ""), "services": {service} if service else set(), "open": False})
    clusters = []
    for item in sorted(items, key=lambda i: i["start"]):
        last = now if item["open"] else item["end"]
        home = next((c for c in clusters if item["start"] <= c["last"] + 600 and (not c["services"] or not item["services"] or c["services"] & item["services"])), None)
        if home is None:
            home = {"start": item["start"], "last": last, "services": set(), "items": [], "open": False}
            clusters.append(home)
        home["last"] = max(home["last"], last)
        home["services"] |= item["services"]
        home["open"] = home["open"] or item["open"]
        home["items"].append(item)
    out = []
    for c in clusters:
        causes = {INCIDENT_CAUSES[i["kind"]] for i in c["items"] if i["kind"] in INCIDENT_CAUSES}
        if not causes & {"down", "crash", "oom", "build_error"}:
            continue
        cause = next(x for x in ("config_change", "docker_restart", "build_error", "oom", "crash", "down") if x in causes)
        end = None if c["open"] else int(c["last"])
        out.append({"id": f"inc-{int(c['start'])}", "start": int(c["start"]), "end": end, "durationSec": int((end or now) - c["start"]), "services": sorted(c["services"]),
                    "cause": cause, "timeline": [{"at": int(i["start"]), "kind": i["kind"], "title": i["title"], "titleFr": i["titleFr"]} for i in c["items"]]})
    return sorted(out, key=lambda i: -i["start"])


def disk_preview():
    df = {}
    for line in run("docker", "system", "df", "--format", "{{json .}}", timeout=120).splitlines():
        row = json.loads(line)
        df[row["Type"]] = row
    used = {c["Image"] for c in inspect_all()}
    def images(*flags):
        return [json.loads(l) for l in run("docker", "images", "--no-trunc", *flags, "--format", "{{json .}}", timeout=60).splitlines()]
    dangling = images("-f", "dangling=true")
    dangling_ids = {i["ID"] for i in dangling}
    unused = [i for i in images() if i["ID"] not in used and i["ID"] not in dangling_ids]
    stopped = run("docker", "ps", "-a", "-q", "-f", "status=exited", "-f", "status=created", "-f", "status=dead").split()
    cache_row = df.get("Build Cache", {})
    items = [{"id": "build_cache", "count": max(int(cache_row.get("TotalCount") or 0) - int(cache_row.get("Active") or 0), 0), "bytes": size_bytes((cache_row.get("Reclaimable") or "0B").split()[0])},
             {"id": "dangling_images", "count": len(dangling), "bytes": sum(size_bytes(i["Size"]) for i in dangling)},
             {"id": "unused_images", "count": len(unused), "bytes": sum(size_bytes(i["Size"]) for i in unused)},
             {"id": "stopped_containers", "count": len(stopped), "bytes": size_bytes((df.get("Containers", {}).get("Reclaimable") or "0B").split()[0]) if stopped else 0}]
    return {"items": items, "totalBytes": sum(i["bytes"] for i in items)}


def disk_clean(items):
    items = list(dict.fromkeys(items))
    if not items or any(i not in CLEAN_ITEMS for i in items):
        raise ValueError("Choose what to clean")
    if not clean_lock.acquire(blocking=False):
        raise ValueError("Busy, try again")
    try:
        commands = []
        if "stopped_containers" in items:
            commands.append(["docker", "container", "prune", "-f"])
        if "unused_images" in items:
            commands.append(["docker", "image", "prune", "-f", "-a"])
        elif "dangling_images" in items:
            commands.append(["docker", "image", "prune", "-f"])
        if "build_cache" in items:
            commands.append(["docker", "builder", "prune", "-f", "-a"])
        freed = 0
        for cmd in commands:
            m = re.search(r"Total(?: reclaimed space)?:\s*(\S+)", run(*cmd, timeout=900))
            freed += size_bytes(m.group(1)) if m else 0
    finally:
        clean_lock.release()
    for key in ("dockerdf", "disk_preview"):
        cache.pop(key, None)
    publish("system", ev("cleanup_done", "Cleanup done", f"Freed {human(freed)}", "Nettoyage terminé", f"{human(freed, True)} libérés", freedBytes=freed))
    return freed


def certs():
    try:
        with open(ACME_JSON) as f:
            data = json.load(f)
    except (OSError, ValueError):
        return []
    hosts = cached("routed", 120, routed_hosts)
    now = time.time()
    out = []
    for resolver in data.values():
        for c in (resolver.get("Certificates") or []) if isinstance(resolver, dict) else []:
            try:
                cert = x509.load_pem_x509_certificate(base64.b64decode(c["certificate"]))
            except (KeyError, ValueError, TypeError):
                continue
            expires = (getattr(cert, "not_valid_after_utc", None) or cert.not_valid_after.replace(tzinfo=timezone.utc)).timestamp()
            names = (cert.issuer.get_attributes_for_oid(NameOID.COMMON_NAME) or cert.issuer.get_attributes_for_oid(NameOID.ORGANIZATION_NAME))
            main = (c.get("domain") or {}).get("main", "")
            sans = (c.get("domain") or {}).get("sans") or []
            days = int((expires - now) // 86400)
            out.append({"domain": main, "sans": sans[:10], "issuer": names[0].value if names else "", "notAfter": int(expires), "daysLeft": days,
                        "routed": any(d in hosts for d in [main, *sans]), "autoRenew": True, "renewsInDays": days - 30})
    return sorted(out, key=lambda r: r["daysLeft"])


def dns_check():
    ip = cached("server_ip", 86400, server_ip)
    nets = cf_networks()
    out = []
    for host in sorted(cached("routed", 120, routed_hosts)):
        a, aaaa = resolve(host, socket.AF_INET), resolve(host, socket.AF_INET6)
        status = "missing" if not a else "ok" if ip in a else "proxied" if all(in_networks(x, nets) for x in a) else "mismatch"
        out.append({"host": host, "a": a, "aaaa": aaaa, "status": status, "serverIp": ip})
    return out


def task_stats(rows):
    return {task_name(r["name"]): r for r in rows}


def sample_containers():
    now = int(time.time())
    grouped = {}
    for r in cached("containers", 10, containers):
        g = grouped.setdefault(task_name(r["name"]), [0.0, 0])
        g[0] += r["cpuPercent"]
        g[1] += r["memBytes"]
    with db() as con:
        con.executemany("INSERT INTO container_samples VALUES(?,?,?,?)", [(now, n, c, m) for n, (c, m) in grouped.items()])
        con.execute("DELETE FROM container_samples WHERE at < ?", (now - 48 * 3600,))


def memory_history():
    out = {}
    with db() as con:
        for r in con.execute("SELECT name, mem FROM container_samples WHERE at > ? ORDER BY at", (time.time() - 3 * 3600,)):
            out.setdefault(r["name"], []).append(r["mem"])
    return {k: v[-24:] for k, v in out.items()}


def container_history(name, hours):
    if not CONTAINER_NAME.match(name):
        raise ValueError("Bad container name")
    with db() as con:
        rows = con.execute("SELECT at, cpu, mem FROM container_samples WHERE name=? AND at>? ORDER BY at", (task_name(name), time.time() - max(1, min(hours, 48)) * 3600)).fetchall()
    return [{"t": r["at"], "cpuPercent": round(r["cpu"], 1), "memBytes": r["mem"]} for r in rows]


def new_site():
    return {"requests": 0, "minutes": {}, "errors": 0, "ms": [], "paths": {}, "countries": {}, "visitors": set()}


def aggregate_web(lines, hosts, nets, now):
    minute_keys = [time.strftime("%Y-%m-%dT%H:%M", time.gmtime(now - 60 * i)).encode() for i in range(59, -1, -1)]
    minute_set = set(minute_keys)
    sites = {h: new_site() for h in hosts}
    origin = {}
    for ts, line in lines:
        try:
            e = json.loads(line)
        except ValueError:
            continue
        s = sites.get(str(e.get("RequestHost", "")).lower().split(":")[0])
        if s is None:
            continue
        status = int(e.get("DownstreamStatus") or 0)
        ms = round((e.get("Duration") or 0) / 1e6, 1)
        s["requests"] += 1
        if ts[:16] in minute_set:
            s["minutes"][ts[:16]] = s["minutes"].get(ts[:16], 0) + 1
        if status >= 500:
            s["errors"] += 1
        if len(s["ms"]) < 100000:
            s["ms"].append(ms)
        path = str(e.get("RequestPath", ""))[:120]
        p = s["paths"].get(path)
        if p is None and len(s["paths"]) < 2000:
            p = s["paths"][path] = [0, 0.0, 0, []]
        if p:
            p[0] += 1
            p[1] += ms
            p[2] += status >= 500
            if len(p[3]) < 200:
                p[3].append(ms)
        ip = str(e.get("ClientHost", ""))
        s["visitors"].add(ip)
        code = origin_of(ip, nets, origin)
        if code:
            s["countries"][code] = s["countries"].get(code, 0) + 1
    out = {}
    for host, s in sites.items():
        n = s["requests"]
        paths = [(k, v) for k, v in s["paths"].items()]
        out[host] = {"host": host, "requests": n, "requestsPerMin": [s["minutes"].get(k, 0) for k in minute_keys], "errors5xx": s["errors"],
                     "errorRate": round(100 * s["errors"] / n, 2) if n else 0, "p50Ms": percentile(s["ms"], 50), "p95Ms": percentile(s["ms"], 95),
                     "uniqueVisitors": len(s["visitors"]),
                     "topPaths": [{"path": k, "count": v[0], "avgMs": round(v[1] / v[0], 1), "errors5xx": v[2]} for k, v in sorted(paths, key=lambda kv: -kv[1][0])[:8]],
                     "slowestPaths": [{"path": k, "count": v[0], "p95Ms": percentile(v[3], 95)} for k, v in sorted((kv for kv in paths if kv[1][0] >= 3), key=lambda kv: -percentile(kv[1][3], 95))[:5]],
                     "countries": [{"code": c, "count": k} for c, k in sorted(s["countries"].items(), key=lambda kv: -kv[1])[:10]]}
    return out


def web_stats():
    return aggregate_web(access_lines(86400), cached("routed", 120, routed_hosts), cf_networks(), time.time())


def parse_env(text):
    env = {}
    for line in (text or "").splitlines():
        m = re.match(r"\s*([^=#\s][^=]*?)\s*=(.*)$", line)
        if m:
            env[m.group(1)] = m.group(2)
    return env


def mask(value):
    return "••••" + value[-2:] if len(value) >= 6 else "••••"


def env_pass():
    status, raw = cached("proxy:project.all:", 10, projects_with_app_names)
    if status != 200:
        return
    now = int(time.time())
    for project in json.loads(raw):
        for environment in project.get("environments", []):
            for kind, field, key in (("application", "applications", "applicationId"), ("compose", "compose", "composeId")):
                for svc in environment.get(field) or []:
                    code, body = dokploy("GET", f"{kind}.one", urlencode({key: svc[key]}), b"")
                    if code != 200:
                        continue
                    content = json.loads(body).get("env") or ""
                    sha = hashlib.sha256(content.encode()).hexdigest()
                    with db() as con:
                        last = con.execute("SELECT sha FROM env_versions WHERE service_id=? AND kind=? ORDER BY rowid DESC LIMIT 1", (svc[key], kind)).fetchone()
                        if last and last["sha"] == sha:
                            continue
                        con.execute("INSERT INTO env_versions VALUES(?,?,?,?,?)", (svc[key], kind, now, sha, content))
                        con.execute("DELETE FROM env_versions WHERE service_id=? AND kind=? AND rowid NOT IN (SELECT rowid FROM env_versions WHERE service_id=? AND kind=? ORDER BY rowid DESC LIMIT 50)",
                                    (svc[key], kind, svc[key], kind))


def env_target(kind, service_id):
    if kind not in ("application", "compose"):
        raise ValueError("Unknown service kind")
    if not SERVICE_ID.fullmatch(service_id):
        raise ValueError("Bad service id")


def env_history(kind, service_id):
    env_target(kind, service_id)
    with db() as con:
        rows = con.execute("SELECT at, sha, content FROM env_versions WHERE service_id=? AND kind=? ORDER BY rowid DESC LIMIT 51", (service_id, kind)).fetchall()[::-1]
    out, prev = [], None
    for r in rows:
        env = parse_env(r["content"])
        if prev is not None:
            out.append({"at": r["at"], "sha": r["sha"][:12], "prevSha": prev[0], "changedKeys": sorted(k for k in env.keys() | prev[1].keys() if env.get(k) != prev[1].get(k))})
        prev = (r["sha"][:12], env)
    return out[::-1]


def env_diff(kind, service_id, a, b, reveal):
    env_target(kind, service_id)
    with db() as con:
        def version(sha):
            if not re.fullmatch(r"[0-9a-f]{6,64}", sha):
                raise ValueError("Bad version")
            row = con.execute("SELECT content FROM env_versions WHERE service_id=? AND kind=? AND substr(sha,1,?)=? ORDER BY rowid DESC LIMIT 1", (service_id, kind, len(sha), sha)).fetchone()
            if row is None:
                raise ValueError("Unknown version")
            return parse_env(row["content"])
        old, new = version(a), version(b)
    show = (lambda v: v) if reveal else (lambda v: None if v is None else mask(v))
    out = []
    for key in sorted(old.keys() | new.keys()):
        o, n = old.get(key), new.get(key)
        kind_ = "added" if o is None else "removed" if n is None else "changed" if o != n else "same"
        out.append({"key": key, "type": kind_, "old": show(o), "new": show(n)})
    return out


SECRET_PATTERNS = {
    "aws_key": (re.compile(r"AKIA[0-9A-Z]{16}"), "AWS access key", "Clé d’accès AWS"),
    "private_key": (re.compile(r"-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----"), "Private key", "Clé privée"),
    "stripe_live": (re.compile(r"sk_live_[0-9A-Za-z]{8,}"), "Stripe live key", "Clé Stripe live"),
    "github_token": (re.compile(r"ghp_[0-9A-Za-z]{20,}|github_pat_[0-9A-Za-z_]{20,}"), "GitHub token", "Jeton GitHub"),
    "slack_token": (re.compile(r"xox[baprs]-[0-9A-Za-z-]{10,}"), "Slack token", "Jeton Slack"),
    "jwt": (re.compile(r"eyJ[\w-]+\.[\w-]+\.[\w-]+"), "JWT", "JWT"),
    "generic_secret": (re.compile(r"(?i)(?:password|secret|api[_-]?key|token)[\"']?\s*[=:]\s*[\"']?(?P<v>[^\s\"',;]{8,})"), "Password or key in plain text", "Mot de passe ou clé en clair"),
}
PLACEHOLDER = re.compile(r"(?i)^(?:\*|x{4}|•|<|\[|\{|\$|%|null$|none$|true$|false$|undefined$|redacted|hidden|masked)")


def running_names():
    return [n for n in run("docker", "ps", "--format", "{{.Names}}").split() if CONTAINER_NAME.match(n)]


def docker_logs(name, *args):
    proc = subprocess.run(["docker", "logs", *args, name], capture_output=True, text=True, errors="replace", timeout=60)
    return proc.stdout + proc.stderr


def secrets_pass():
    now = int(time.time())
    for name in running_names():
        text = docker_logs(name, "--tail", "2000", "--since", "30m")
        for key, (rx, label, label_fr) in SECRET_PATTERNS.items():
            values = [m.groupdict().get("v") or m.group(0) for m in rx.finditer(text)]
            if key == "generic_secret":
                values = [v for v in values if not PLACEHOLDER.match(v)]
            if not values:
                continue
            with db() as con:
                con.execute("INSERT INTO secret_findings VALUES(?,?,?,?,?,?) ON CONFLICT(container, pattern) DO UPDATE SET count=count+excluded.count, last_seen=excluded.last_seen",
                            (name, key, values[0][:4] + "…" + values[0][-2:], now, now, len(values)))
            notice_once(f"secret:{name}:{key}", 86400, "system", ev("secret_leak", f"{label} in {name} logs", "A secret is printed in the logs. Rotate it, then stop logging it.",
                                                                   f"{label_fr} dans les logs de {name}", "Un secret apparaît dans les logs. Changez-le, puis arrêtez de le journaliser.", container=name, pattern=key))
    with db() as con:
        con.execute("DELETE FROM secret_findings WHERE last_seen < ?", (now - 30 * 86400,))


def secret_findings():
    with db() as con:
        rows = con.execute("SELECT container, pattern, sample, first_seen firstSeen, last_seen lastSeen, count FROM secret_findings ORDER BY last_seen DESC LIMIT 200").fetchall()
    return [dict(r) for r in rows]


PROBE_CATEGORIES = [
    ("env", "Environment files", "Fichiers d’environnement", r"/\.env"),
    ("infra_secrets", "Infrastructure secrets", "Secrets d’infrastructure", r"/\.aws/|/\.docker/|/\.ssh/|/\.kube/|/id_rsa|/\.npmrc|/\.git-credentials|/config\.json$|/credentials\.json$|/\.vscode/"),
    ("git", "Git and version control", "Git et gestion de versions", r"/\.git(/|$)|/\.svn|/\.hg(/|$)|/\.ds_store"),
    ("laravel", "Laravel and Symfony", "Laravel et Symfony", r"/storage/logs/|/vendor/phpunit|eval-stdin\.php|/_ignition|/telescope|/_profiler|/app_dev\.php"),
    ("dbadmin", "Database admin panels", "Interfaces d’administration de base de données", r"/phpmyadmin|/pma(/|$)|/adminer|/mysql(/|$)|/dbadmin|/sqlite"),
    ("wordpress", "WordPress", "WordPress", r"/wp-|/xmlrpc\.php"),
    ("backups", "Backups and dumps", "Sauvegardes et dumps", r"\.(bak|old|swp)$|/(backup|site)\.(zip|tar\.gz)$|/(database|dump|db)\.sql$"),
    ("debug", "Debug and info pages", "Pages de debug et d’info", r"/phpinfo|/info\.php|/debug|/server-(status|info)|/_debug|/console"),
    ("api_docs", "API docs", "Documentation d’API", r"/swagger|/openapi\.json|/api-docs|/v2/api-docs|/graphql"),
    ("java", "Java and Spring", "Java et Spring", r"/actuator|/jolokia|/manager/html"),
    ("devops", "DevOps panels", "Panneaux DevOps", r"/jenkins|/script$|/grafana|/kibana|/solr/|/_cat/|/druid|/nacos|/portainer"),
    ("routers", "Routers and IoT", "Routeurs et objets connectés", r"/boaform|/hnap1|/goform|/cgi-bin/|/gponform|/setup\.cgi|/shell$|/tmunblock\.cgi"),
    ("vpn", "VPN and mail edge exploits", "Failles VPN et messagerie", r"/remote/fgt_lang|/dana-na/|/vpn/|/owa/|/ecp/|/autodiscover|/\+cscoe\+"),
    ("docker_api", "Docker and Kubernetes API", "API Docker et Kubernetes", r"/v1\.\d+/containers|/containers/json|/api/v1/pods|/version$"),
    ("php_shells", "PHP shells", "Shells PHP", r"\.php$|/alfa"),
    ("other", "Other scanning", "Autres balayages", None),
]
PROBE_RX = [(cid, re.compile(rx)) for cid, _, _, rx in PROBE_CATEGORIES if rx]


def origin_of(ip, nets, memo):
    if ip not in memo:
        memo[ip] = "CF" if in_networks(ip, nets) else local_country(ip)
    return memo[ip]


def probe_collector(nets):
    hits, ips, exposed, leftovers, cats, memo = {}, set(), [], [], {}, {}
    def observe(ts, e):
        path = str(e.get("RequestPath", "")).split("?")[0].lower()[:120]
        cat = next((cid for cid, rx in PROBE_RX if rx.search(path)), None)
        status = int(e.get("DownstreamStatus") or 0)
        if cat is None and status != 404:
            return
        ip = str(e.get("ClientHost", ""))
        day, country = ts[:10].decode(), origin_of(ip, nets, memo) or "??"
        if cat is None:
            if len(leftovers) < 20000:
                leftovers.append((day, path, ip, country))
            return
        cats.setdefault(ip, set()).add(cat)
        key = (day, cat, path, status, country)
        hits[key] = hits.get(key, 0) + 1
        ips.add((day, cat, ip))
        size = int(e.get("DownstreamContentSize") or 0)
        if status in (200, 206) and size > 0:
            exposed.append((str(e.get("RequestHost", "")).lower().split(":")[0], path, status, size, ts, cat))
    def finish(con):
        for day, path, ip, country in leftovers:
            if len(cats.get(ip, ())) >= 3:
                key = (day, "other", path, 404, country)
                hits[key] = hits.get(key, 0) + 1
                ips.add((day, "other", ip))
        sizes = load_json("probe_sizes.json", {})
        for host, path, _, size, _, _ in exposed:
            seen = sizes.setdefault(host, {}).setdefault(str(size), [])
            if path not in seen and len(seen) < 3:
                seen.append(path)
        for host, path, status, size, ts, cat in exposed:
            if any(len(p) >= 3 and abs(size - int(s)) <= 0.05 * int(s) for s, p in sizes[host].items()):
                continue
            at = int(datetime.strptime(ts.decode(), "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc).timestamp())
            con.execute("INSERT OR REPLACE INTO probe_exposed VALUES(?,?,?,?,?,?)", (host, path, cat, status, size, at))
            notice_once(f"exposure:{host}{path}", 86400, "system", ev("exposure", f"{host}{path} answered 200 to a scanner, check it", "Make sure this address is meant to be public.",
                                                                   f"{host}{path} a répondu 200 à un scanner, vérifiez-le", "Assurez-vous que cette adresse doit être publique.", host=host, path=path))
        con.executemany("INSERT INTO probe_hits VALUES(?,?,?,?,?,?) ON CONFLICT(day, category, path, status, country) DO UPDATE SET count=count+excluded.count",
                        [(*k, n) for k, n in hits.items()])
        con.executemany("INSERT OR IGNORE INTO probe_ips VALUES(?,?,?)", list(ips))
        for table in ("probe_hits", "probe_ips"):
            con.execute(f"DELETE FROM {table} WHERE day < date('now','-30 days')")  # vibe-guardian: ignore
        con.execute("DELETE FROM probe_exposed WHERE at < ?", (time.time() - 30 * 86400,))
        save_json("probe_sizes.json", {h: dict(list(v.items())[-10:]) for h, v in list(sizes.items())[-50:]})
    return observe, finish


def probes(days):
    days = max(1, min(days, 30))
    since_day = time.strftime("%Y-%m-%d", time.gmtime(time.time() - (days - 1) * 86400))
    with db() as con:
        rows = con.execute("SELECT category, path, status, country, sum(count) n FROM probe_hits WHERE day >= ? GROUP BY category, path, status, country", (since_day,)).fetchall()
        ip_counts = {r["category"]: r["n"] for r in con.execute("SELECT category, count(DISTINCT ip) n FROM probe_ips WHERE day >= ? GROUP BY category", (since_day,))}
        exposed = con.execute("SELECT category, host, path, status, bytes, at FROM probe_exposed WHERE at >= ? ORDER BY at DESC", (time.time() - days * 86400,)).fetchall()
    out = []
    for cid, en, fr_name, _ in PROBE_CATEGORIES:
        mine = [r for r in rows if r["category"] == cid]
        if not mine:
            continue
        paths, countries = {}, {}
        for r in mine:
            p = paths.setdefault(r["path"], {"path": r["path"], "hits": 0, "statuses": {}})
            p["hits"] += r["n"]
            p["statuses"][str(r["status"])] = p["statuses"].get(str(r["status"]), 0) + r["n"]
            countries[r["country"]] = countries.get(r["country"], 0) + r["n"]
        out.append({"id": cid, "nameEn": en, "nameFr": fr_name, "hits": sum(r["n"] for r in mine), "ips": ip_counts.get(cid, 0),
                    "topPaths": sorted(paths.values(), key=lambda p: -p["hits"])[:8],
                    "topCountries": [{"code": c, "hits": n} for c, n in sorted(countries.items(), key=lambda kv: -kv[1])[:5]],
                    "exposed": [{"host": e["host"], "path": e["path"], "status": e["status"], "bytes": e["bytes"], "lastAt": e["at"]} for e in exposed if e["category"] == cid]})
    return {"total": sum(c["hits"] for c in out), "categories": sorted(out, key=lambda c: -c["hits"]), "exposedCount": len(exposed)}


def dns_query(rtype, name):
    try:
        out = subprocess.run(["dig", "+short", "+time=3", "+tries=1", rtype, name], capture_output=True, text=True, timeout=15).stdout
    except FileNotFoundError:
        try:
            raw = subprocess.run(["host", "-t", rtype, name], capture_output=True, text=True, timeout=15).stdout
        except FileNotFoundError:
            raise ValueError("Install dig or host on the server to check DNS")
        out = "\n".join(re.sub(r"^.*?(?:descriptive text|handled by) ", "", l) for l in raw.splitlines() if "descriptive text" in l or "handled by" in l)
    return ["".join(re.findall(r'"([^"]*)"', l)) or l.strip() for l in out.splitlines() if l.strip()]


def mail_queue():
    try:
        q = stalwart_call("x:QueuedMessage/query", {"calculateTotal": True, "limit": 200})
        if "ids" not in q:
            print(f"mail queue: {q.get('type')}", file=sys.stderr)
            return None
        deferred = 0
        if q["ids"]:
            got = stalwart_call("x:QueuedMessage/get", {"ids": q["ids"], "properties": ["recipients", "nextRetry"]}).get("list", [])
            deferred = sum(1 for m in got if any(r.get("retryCount", 0) > 0 for r in (m.get("recipients") or {}).values()))
        return {"size": q.get("total", len(q["ids"])), "deferred": deferred, "sampled": len(q["ids"])}
    except (OSError, ValueError, KeyError, AttributeError):
        return None


def mail_domain(name, zone, ip):
    def check(ok, value, fix, fix_fr, **extra):
        return {"ok": ok, "value": value, **({} if ok else {"fix": fix, "fixFr": fix_fr}), **extra}
    mx = dns_query("MX", name)
    mx_ok = any(ip in resolve(m.split()[-1].rstrip("."), socket.AF_INET) for m in mx)
    spf = next((t for t in dns_query("TXT", name) if t.lower().startswith("v=spf1")), None)
    spf_ok = False
    for token in (spf or "").split()[1:]:
        t = token.lstrip("+")
        if t in ("mx", "a") or t.startswith("include:"):
            spf_ok = True
        elif t.startswith("ip4:"):
            try:
                spf_ok = spf_ok or ipaddress.ip_address(ip) in ipaddress.ip_network(t[4:], strict=False)
            except ValueError:
                pass
    dmarc = next((t for t in dns_query("TXT", f"_dmarc.{name}") if t.lower().startswith("v=dmarc1")), None)
    policy = (re.search(r"\bp=(\w+)", dmarc or "") or [None, None])[1]
    selectors = re.findall(r"^\s*([A-Za-z0-9_-]+)\._domainkey", zone, re.M)[:4]
    published = [s for s in selectors if any("p=" in t for t in dns_query("TXT", f"{s}._domainkey.{name}"))]
    return {"name": name,
            "mx": check(mx_ok, ", ".join(mx) or None, f"Point the MX record of {name} to a host that resolves to {ip}.", f"Faites pointer l’enregistrement MX de {name} vers un hôte qui résout vers {ip}."),
            "spf": check(spf_ok, spf, f"Add a TXT record on {name}: v=spf1 ip4:{ip} -all", f"Ajoutez un enregistrement TXT sur {name} : v=spf1 ip4:{ip} -all"),
            "dmarc": check(policy in ("quarantine", "reject"), dmarc,
                           f"Add a TXT record on _dmarc.{name}: v=DMARC1; p=quarantine" if not dmarc else "The policy is none, so spoofed mail is not blocked. Move to quarantine once reports look clean.",
                           f"Ajoutez un enregistrement TXT sur _dmarc.{name} : v=DMARC1; p=quarantine" if not dmarc else "La politique est none, le courrier usurpé n’est pas bloqué. Passez à quarantine quand les rapports sont propres.", policy=policy),
            "dkim": check(bool(published), ", ".join(published) or None,
                          f"Stalwart has no DKIM key for {name} yet. Create one in Stalwart, then publish it." if not selectors else f"Publish the DKIM TXT records of {name} shown in Stalwart's DNS zone file.",
                          f"Stalwart n’a pas encore de clé DKIM pour {name}. Créez-en une dans Stalwart, puis publiez-la." if not selectors else f"Publiez les enregistrements TXT DKIM de {name} indiqués dans la zone DNS de Stalwart.", selectors=selectors)}


STALWART_LOG = "/var/log/stalwart/stalwart.log"
MAIL_LINE = re.compile(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)Z \w+ .*? \(([a-z0-9-]+\.[a-z0-9.-]+)\) ?(.*)$")
MAIL_REJECT = re.compile(r"^smtp\.(mailbox-does-not-exist|relay-not-allowed|too-many-[a-z-]+|rate-limit[a-z-]*|[a-z-]*reject[a-z-]*|invalid-ehlo|dmarc-fail)$")
mail_events: deque = deque(maxlen=20000)


def mail_field(rest, key):
    m = re.search(rf"\b{key} = (\"[^\"]*\"|[^,\s]+)", rest)
    return m.group(1).strip('"') if m else ""


def mail_kind(event, rest):
    inbound = mail_field(rest, "localPort") == "25"
    if event == "queue.message-queued" and inbound:
        return "accepted"
    if event == "smtp.ehlo" and inbound:
        return "attempt"
    if MAIL_REJECT.match(event) and inbound:
        return "rejected"
    if event.startswith("delivery.") and "fail" in event:
        return "outbound_failed"
    if event == "delivery.delivered":
        return "delivered"
    if event in ("security.authentication-ban", "security.scan-ban") or (event.startswith("auth.") and event != "auth.success"):
        return "auth_ban" if event.startswith("security.") else "auth_failed"
    return None


def mail_pass():
    state = load_json("mail_pos.json", {})
    try:
        st = os.stat(STALWART_LOG)
    except OSError:
        return
    if not mail_events:
        with lock:
            mail_events.extend(tuple(e) for e in load_json("mail_events.json", []))
    offset = state.get("offset", 0) if state.get("inode") == st.st_ino and state.get("offset", 0) <= st.st_size else 0
    offset = max(offset, st.st_size - (16 << 20))
    senders, fresh = state.get("senders", {}), []
    with open(STALWART_LOG, "rb") as f:
        f.seek(offset)
        for raw in f:
            if not raw.endswith(b"\n"):
                break
            offset += len(raw)
            m = MAIL_LINE.match(raw.decode(errors="replace").rstrip("\n"))
            if not m:
                continue
            at, event, rest = m.groups()
            session = f"{mail_field(rest, 'remoteIp')}:{mail_field(rest, 'remotePort')}"
            if event == "smtp.mail-from":
                senders[session] = mail_field(rest, "from").rpartition("@")[2].lower()
            kind = mail_kind(event, rest)
            if kind:
                ts = int(datetime.strptime(at, "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc).timestamp())
                domain = senders.get(session) or mail_field(rest, "domain") or mail_field(rest, "to").strip("[]\"").rpartition("@")[2]
                fresh.append((ts, kind, event, mail_field(rest, "remoteIp"), domain[:80], (mail_field(rest, "reason") or mail_field(rest, "details"))[:120]))
    cutoff = time.time() - 86400
    with lock:
        mail_events.extend(fresh)
        while mail_events and mail_events[0][0] < cutoff:
            mail_events.popleft()
        rows = list(mail_events)
    last = max([state.get("lastInboundAt") or 0] + [e[0] for e in fresh if e[1] == "accepted"]) or None
    save_json("mail_events.json", rows)
    save_json("mail_pos.json", {"inode": st.st_ino, "offset": offset, "lastInboundAt": last, "senders": dict(list(senders.items())[-500:])})
    mail_alerts()


def mail_activity():
    with lock:
        rows = list(mail_events)
    count = lambda kind, since=0: sum(1 for e in rows if e[1] == kind and e[0] >= since)
    reasons = {}
    for e in rows:
        if e[1] == "rejected":
            key = (e[2], e[4], e[3])
            reasons[key] = reasons.get(key, 0) + 1
    pick = lambda kinds: [{"at": e[0], "event": e[2], "ip": e[3], "domain": e[4], "detail": e[5]} for e in reversed(rows) if e[1] in kinds][:15]
    return {"logFound": os.path.exists(STALWART_LOG), "lastInboundAt": load_json("mail_pos.json", {}).get("lastInboundAt"),
            "accepted24h": count("accepted"), "rejected24h": count("rejected"), "attempts24h": count("attempt"),
            "delivered24h": count("delivered"), "outboundFailed24h": count("outbound_failed"),
            "authBans24h": count("auth_ban"), "authFailures24h": count("auth_failed"),
            "topRejections": [{"event": k[0], "domain": k[1], "ip": k[2], "count": n} for k, n in sorted(reasons.items(), key=lambda x: -x[1])[:8]],
            "recentRejections": pick({"rejected"}), "recentFailures": pick({"outbound_failed", "auth_ban"})}


def mail_alerts():
    now = time.time()
    with lock:
        rows = list(mail_events)
    accepted = [e[0] for e in rows if e[1] == "accepted"]
    attempts = sum(1 for e in rows if e[1] == "attempt" and e[0] >= now - 6 * 3600)
    if attempts >= 3 and (not accepted or max(accepted) < now - 6 * 3600):
        notice_once("mail:silent", 6 * 3600, "system", ev("mail_silent", "No inbound mail accepted for 6 hours",
                                                       f"{attempts} senders connected but nothing was queued. Check the firewall and Stalwart.",
                                                       "Aucun mail entrant accepté depuis 6 heures",
                                                       f"{attempts} expéditeurs se sont connectés mais rien n’a été accepté. Vérifiez le pare-feu et Stalwart."))
    rejected = [e for e in rows if e[1] == "rejected" and e[0] >= now - 900]
    if len(rejected) >= 10:
        top = max({e[4] or e[3] for e in rejected}, key=lambda k: sum(1 for e in rejected if (e[4] or e[3]) == k))
        notice_once("mail:rejects", 1800, "system", ev("mail_rejects", f"{len(rejected)} inbound mails rejected in 15 min", f"Mostly from {top}",
                                                    f"{len(rejected)} mails entrants rejetés en 15 min", f"Surtout depuis {top}"))


def mail_health():
    ip = cached("server_ip", 86400, server_ip)
    try:
        domains = stalwart_call("x:Domain/get", {"ids": None}).get("list", [])
    except (OSError, ValueError, KeyError, AttributeError):
        domains = []
    return {"queue": mail_queue(), "domains": [mail_domain(d["name"], d.get("dnsZoneFile") or "", ip) for d in domains[:20] if DOMAIN.fullmatch(str(d.get("name", "")))]}


def notes_list():
    with db() as con:
        return [dict(r) for r in con.execute("SELECT service_id serviceId, text, author, at FROM notes ORDER BY at DESC").fetchall()]


def notes_set(service_id, text, author):
    if not SERVICE_ID.fullmatch(service_id):
        raise ValueError("Bad service id")
    text = text.strip()
    if len(text) > 500:
        raise ValueError("Notes are limited to 500 characters")
    with db() as con:
        if text:
            con.execute("INSERT OR REPLACE INTO notes VALUES(?,?,?,?)", (service_id, text, author, int(time.time())))
        else:
            con.execute("DELETE FROM notes WHERE service_id=?", (service_id,))


CRASH_SIGNATURES = {
    "traceback": (re.compile(r"Traceback \(most recent call last\)"), "Python traceback", "traceback Python"),
    "panic": (re.compile(r"\bpanic:"), "Go panic", "panic Go"),
    "unhandled_rejection": (re.compile(r"(?i)Unhandled\s*(?:promise\s*)?rejection"), "Unhandled promise rejection", "promesse rejetée non gérée"),
    "fatal": (re.compile(r"\bFATAL\b"), "FATAL error", "erreur FATAL"),
    "segfault": (re.compile(r"Segmentation fault"), "Segmentation fault", "erreur de segmentation"),
    "out_of_memory": (re.compile(r"OutOfMemoryError"), "Out of memory error", "erreur de mémoire insuffisante"),
    "econnrefused": (re.compile(r"ECONNREFUSED"), "Repeated connection refused", "connexions refusées en boucle"),
}
ERROR_LINE = re.compile(r"(?i)\b(?:error|exception)\b")


def logwatch_settings():
    return {"burst": 20, "enabled": True, **load_json("logwatch.json", {})}


def logwatch_pass():
    cfg = logwatch_settings()
    now = time.time()
    if now - error_windows.setdefault("_cron", [0])[0] > 300:
        error_windows["_cron"][0] = now
        try:
            cron_check()
        except (OSError, ValueError, KeyError, TypeError) as e:
            print(f"cron check: {e}", file=sys.stderr)
    if not cfg["enabled"]:
        return
    names = running_names()
    for gone in set(log_since) - set(names):
        log_since.pop(gone)
        error_windows.pop(gone, None)
    for name in names:
        text = docker_logs(name, "--since", f"{log_since.get(name, now - 60):.3f}", "--tail", "2000")
        log_since[name] = now
        for key, (rx, label, label_fr) in CRASH_SIGNATURES.items():
            if len(rx.findall(text)) >= (10 if key == "econnrefused" else 1):
                line = line_at(text, rx.search(text))
                notice_once(f"logwatch:{name}:{key}", 1800, "system", ev("log_crash", f"{name} logged a crash: {label}", line, f"{name} a journalisé un plantage : {label_fr}", line, container=name, signature=key, line=line))
        window = error_windows.setdefault(name, deque())
        first = ERROR_LINE.search(text)
        window.append((now, len(ERROR_LINE.findall(text)), line_at(text, first) if first else ""))
        while window[0][0] < now - 300:
            window.popleft()
        total = sum(w[1] for w in window)
        if total >= cfg["burst"]:
            line = next(w[2] for w in window if w[2])
            notice_once(f"logwatch:{name}:burst", 1800, "system", ev("log_error_burst", f"{name} is logging many errors", f"{total} errors in 5 minutes. First: {line}",
                                                                   f"{name} journalise beaucoup d’erreurs", f"{total} erreurs en 5 minutes. Première : {line}", container=name, signature="error_burst", line=line))


def logwatch_detections():
    with lock:
        events = [e for e in history if e.get("kind") in ("log_crash", "log_error_burst")]
    return {"settings": logwatch_settings(), "detections": events[::-1][:50]}


def cron_parse(expr):
    fields = expr.split()
    if len(fields) != 5:
        raise ValueError("Bad cron expression")
    sets = []
    for field, (lo, hi) in zip(fields, ((0, 59), (0, 23), (1, 31), (1, 12), (0, 7))):
        values = set()
        for part in field.split(","):
            rng, _, step = part.partition("/")
            if rng == "*":
                a, b = lo, hi
            elif "-" in rng:
                a, b = (int(x) for x in rng.split("-"))
            else:
                a = int(rng)
                b = hi if step else a
            values.update(range(a, b + 1, int(step) if step else 1))
        if not values or min(values) < lo or max(values) > hi:
            raise ValueError("Bad cron expression")
        sets.append(values)
    sets[4] = {v % 7 for v in sets[4]}
    return sets, fields[2].startswith("*"), fields[4].startswith("*")


def cron_prev(expr, now):
    (minutes, hours, doms, months, dows), dom_any, dow_any = cron_parse(expr)
    today = now.replace(hour=0, minute=0, second=0, microsecond=0)
    for back in range(400):
        day = today - timedelta(days=back)
        dom_ok, dow_ok = day.day in doms, (day.weekday() + 1) % 7 in dows
        if day.month not in months or not ((dom_ok or dow_ok) if not dom_any and not dow_any else (dom_ok and dow_ok)):
            continue
        limit = (now.hour, now.minute) if back == 0 else (23, 59)
        found = [(h, m) for h in hours for m in minutes if (h, m) <= limit]
        if found:
            return day.replace(hour=max(found)[0], minute=max(found)[1])
    return None


def iso_time(text):
    moment = datetime.fromisoformat(str(text).replace("Z", "+00:00"))
    return moment if moment.tzinfo else moment.replace(tzinfo=timezone.utc)


def cron_check():
    status, raw = cached("proxy:project.all:", 10, projects_with_app_names)
    if status != 200:
        return
    now = datetime.now(timezone.utc)
    for project in json.loads(raw):
        for environment in project.get("environments", []):
            for kind, field, key in (("application", "applications", "applicationId"), ("compose", "compose", "composeId")):
                for svc in environment.get(field) or []:
                    code, body = dokploy("GET", "schedule.list", urlencode({"id": svc[key], "scheduleType": kind}), b"")
                    for s in (json.loads(body) if code == 200 else []):
                        if isinstance(s, dict) and s.get("enabled", True):
                            cron_late(s, svc.get("name") or s.get("name", ""), now)


def cron_late(s, service, now):
    try:
        zone = ZoneInfo(s.get("timezone") or "UTC")
    except (KeyError, ValueError, OSError):
        zone = timezone.utc
    try:
        expected = cron_prev(s["cronExpression"], now.astimezone(zone))
    except (KeyError, ValueError, TypeError):
        return
    if expected is None or now - expected < timedelta(minutes=10) or (s.get("createdAt") and expected < iso_time(s["createdAt"])):
        return
    code, body = dokploy("GET", "deployment.allByType", urlencode({"id": s["scheduleId"], "type": "schedule"}), b"")
    runs = [iso_time(d["createdAt"]) for d in (json.loads(body) if code == 200 else []) if isinstance(d, dict) and d.get("createdAt")]
    if runs and max(runs) >= expected - timedelta(minutes=1):
        return
    late = int((now - expected).total_seconds() // 60)
    label = s.get("name") or service
    notice_once(f"cron:{s['scheduleId']}:{int(expected.timestamp())}", 7 * 86400, "system",
                ev("cron_late", f"Scheduled job {label} did not run", f"It was due {late} minutes ago on {service}.", f"La tâche planifiée {label} ne s’est pas exécutée", f"Elle était attendue il y a {late} minutes sur {service}.", scheduleId=s["scheduleId"]))


def owner_only(owner):
    if not owner:
        raise Denied("Only the owner phone can do this")


def extra_routes(method, path, qs, body, owner, name, ip):
    q = lambda key, default="": qs.get(key, [default])[0]
    data = json.loads(body or b"{}") if method != "GET" else {}
    if not isinstance(data, dict):
        data = {}
    if method == "GET":
        if path == "/updates":
            return 200, {**cached("updates_center", 600, updates_center), **reboot_status()}
        if path == "/probes":
            days = max(1, min(int(q("days", "7")), 30))
            return 200, cached(f"probes:{days}", 300, lambda: probes(days))
        if path == "/watchdog":
            return 200, watchdog_list()
        if path == "/watchdog/diff":
            owner_only(owner)
            return 200, watchdog_diff(q("path"), q("a"), q("b"))
        if path == "/incidents":
            return 200, incidents(int(q("days", "30")))
        if path == "/disk/preview":
            return 200, cached("disk_preview", 60, disk_preview)
        if path == "/certs":
            return 200, cached("certs", 3600, certs)
        if path == "/containers/history":
            return 200, container_history(q("name"), int(q("hours", "24")))
        if path == "/schedules":
            return 200, cached("schedules", 120, all_schedules)
        if path == "/web":
            sites = cached("web", 120, web_stats)
            return 200, sorted(sites.values(), key=lambda s: -s["requests"])
        if path == "/web/host":
            host = q("name").lower()
            if not DOMAIN.fullmatch(host):
                raise ValueError("Bad host name")
            site = cached("web", 120, web_stats).get(host)
            return (200, site) if site else (404, {"error": "Not found"})
        if path == "/env/history":
            owner_only(owner)
            return 200, env_history(q("kind"), q("id"))
        if path == "/env/diff":
            owner_only(owner)
            reveal = q("reveal") == "1"
            result = env_diff(q("kind"), q("id"), q("a"), q("b"), reveal)
            if reveal:
                audit(name, "env_reveal", f"{q('kind')}:{q('id')}", ip)
            return 200, result
        if path == "/secrets/findings":
            owner_only(owner)
            return 200, secret_findings()
        if path == "/mail/activity":
            return 200, mail_activity()
        if path == "/mail":
            return 200, cached("mail", 300, mail_health)
        if path == "/dns":
            return 200, cached("dns", 300, dns_check)
        if path == "/notes":
            return 200, notes_list()
        if path == "/logwatch":
            return 200, logwatch_detections()
        if path == "/vulns":
            settings = load_json("autofix.json", {})
            return 200, {**load_json("audit.json", {}), "installed": bool(shutil.which("trivy")), "running": audit_lock.locked(),
                         "autofixOff": os.environ.get("HELM_NO_AUTOFIX") == "1", "autofix": [{"key": k, **v} for k, v in settings.items()]}
    if method == "POST":
        if path == "/vulns/run":
            owner_only(owner)
            audit_start()
            audit(name, "vuln_scan_start", "", ip)
            return 202, {"started": True}
        if path == "/updates/install":
            owner_only(owner)
            updates_install()
            audit(name, "updates_install", "", ip)
            return 202, {"started": True}
        if path == "/reboot/schedule":
            owner_only(owner)
            at = reboot_schedule(data.get("at"))
            audit(name, "reboot_schedule", at, ip)
            return 200, {"scheduledReboot": at}
        if path == "/reboot/cancel":
            owner_only(owner)
            reboot_cancel(data.get("confirm"))
            audit(name, "reboot_cancel", str(data.get("confirm", "")), ip)
            return 200, {"scheduledReboot": None}
        if path == "/disk/clean":
            owner_only(owner)
            items = data.get("items")
            freed = disk_clean(items if isinstance(items, list) else [])
            audit(name, "disk_clean", ",".join(map(str, items)), ip)
            return 200, {"freedBytes": freed}
        if path == "/notes":
            owner_only(owner)
            notes_set(str(data.get("serviceId", "")), str(data.get("text", "")), name)
            audit(name, "note_set", str(data.get("serviceId", "")), ip)
            return 200, {"ok": True}
    if method == "PUT" and path == "/updates/settings":
        owner_only(owner)
        if not isinstance(data.get("autoReboot"), bool):
            raise ValueError("Send autoReboot as true or false")
        set_auto_reboot(data["autoReboot"])
        audit(name, "auto_reboot", str(data["autoReboot"]), ip)
        return 200, {"autoReboot": data["autoReboot"]}
    if method == "PUT" and path == "/vulns/autofix":
        owner_only(owner)
        return 200, autofix_settings(data, name, ip)
    if method == "PUT" and path == "/logwatch/settings":
        owner_only(owner)
        burst, enabled = data.get("burst", logwatch_settings()["burst"]), data.get("enabled", logwatch_settings()["enabled"])
        if isinstance(burst, bool) or not isinstance(burst, int) or not 3 <= burst <= 1000 or not isinstance(enabled, bool):
            raise ValueError("Burst must be a number from 3 to 1000, enabled true or false")
        save_json("logwatch.json", {"burst": burst, "enabled": enabled})
        audit(name, "logwatch_settings", f"burst={burst} enabled={enabled}", ip)
        return 200, logwatch_settings()
    return None


INCIDENT_CAUSES = {"service_down": "down", "unhealthy": "down", "container_died": "crash", "oom": "oom", "build_error": "build_error",
                   "docker_restarted": "docker_restart", "config_changed": "config_change"}

FR_ERRORS = {
    "Updates are already running": "Les mises à jour sont déjà en cours",
    "Choose a reboot time between 1 minute and 48 hours from now": "Choisissez une heure de redémarrage entre 1 minute et 48 heures à partir de maintenant",
    "This reboot is overdue. Send confirm postpone to cancel it": "Ce redémarrage est en retard. Envoyez confirm postpone pour l’annuler",
    "This overdue reboot was already postponed once": "Ce redémarrage en retard a déjà été reporté une fois",
    "Choose a reboot time between 1 minute and 30 days from now": "Choisissez une heure de redémarrage entre 1 minute et 30 jours à partir de maintenant",
    "Unknown version": "Version inconnue",
    "Send autoReboot as true or false": "Envoyez autoReboot à true ou false",
    "Send enabled as true or false": "Envoyez enabled à true ou false",
    "Choose 24h or permanent": "Choisissez 24h ou permanent",
    "This IP is on the same network as one of your devices. Confirm on the IP screen to ban it anyway.": "Cette IP est sur le même réseau qu’un de vos appareils. Confirmez sur l’écran de l’IP pour la bannir quand même.",
    "A permanent ban needs a confirmation on the IP screen": "Un bannissement définitif doit être confirmé sur l’écran de l’IP",
    "This IP is protected (yours, an always-allowed country, Cloudflare, private or allowed) and cannot be banned": "Cette IP est protégée (la vôtre, un pays toujours autorisé, Cloudflare, privée ou autorisée) et ne peut pas être bannie",
    "Permanent bans need bantime = -1 in the recidive jail": "Un bannissement définitif demande bantime = -1 dans la prison recidive",
    "This Coolify action is not allowed from the app": "Cette action Coolify n’est pas autorisée depuis l’app",
    "The firewall did not take the change, check the agent log": "Le pare-feu n’a pas pris le changement, regardez le journal de l’agent",
    "The country IP lists are not downloaded yet, nothing was blocked. Try again in a few minutes.": "Les listes d’IP par pays ne sont pas encore téléchargées, rien n’a été bloqué. Réessayez dans quelques minutes.",
    "Bad version": "Version invalide",
    "Choose what to clean": "Choisissez quoi nettoyer",
    "Unknown service kind": "Type de service inconnu",
    "Bad service id": "Identifiant de service invalide",
    "Bad host name": "Nom d’hôte invalide",
    "Notes are limited to 500 characters": "Les notes sont limitées à 500 caractères",
    "Burst must be a number from 3 to 1000, enabled true or false": "Le seuil doit être un nombre de 3 à 1000, et enabled vrai ou faux",
    "Install dig or host on the server to check DNS": "Installez dig ou host sur le serveur pour vérifier le DNS",
    "Bad hook secret": "Secret du webhook incorrect",
    "Forbidden": "Interdit",
    "No owner yet. Use the pairing code from the server.": "Pas encore de propriétaire. Utilisez le code d’appairage du serveur.",
    "Not allowed": "Non autorisé",
    "Not found": "Introuvable",
    "Only the owner phone can do this": "Seul le téléphone propriétaire peut faire ça",
    "That request expired or was already handled": "Cette demande a expiré ou a déjà été traitée",
    "The agent hit an internal error, it was logged": "L’agent a rencontré une erreur interne, elle a été enregistrée",
    "This Dokploy action is not allowed from the app": "Cette action Dokploy n’est pas autorisée depuis l’app",
    "This phone is already paired": "Ce téléphone est déjà appairé",
    "This server already has an owner. Ask them to approve this phone.": "Ce serveur a déjà un propriétaire. Demandez-lui d’approuver ce téléphone.",
    "Too many live connections": "Trop de connexions en direct",
    "Too many pairing attempts, wait a minute": "Trop de tentatives d’appairage, attendez une minute",
    "Too many phones waiting for approval": "Trop de téléphones en attente d’approbation",
    "Too many requests, slow down": "Trop de requêtes, ralentissez",
    "Unknown request": "Demande inconnue",
    "Wrong pairing code": "Code d’appairage incorrect",
    "You cannot revoke this phone from itself": "Vous ne pouvez pas retirer ce téléphone depuis lui-même",
    "missing signature": "signature manquante",
    "clock skew, check the phone's time": "l’heure du téléphone est décalée, vérifiez-la",
    "unknown device": "appareil inconnu",
    "bad signature": "signature invalide",
    "replayed request": "requête rejouée",
    "Bad container name": "Nom de conteneur invalide",
    "Unknown deployment log": "Log de déploiement inconnu",
    "Chunked bodies are not accepted": "Les corps fragmentés ne sont pas acceptés",
    "Body too large": "Requête trop volumineuse",
    "That container is not a Postgres database": "Ce conteneur n’est pas une base Postgres",
    "Dokploy's own database holds its secrets and is not available from the app": "La base de Dokploy contient ses secrets, elle n’est pas accessible depuis l’app",
    "Unknown database": "Base de données inconnue",
    "Write one query, under 4000 characters": "Écrivez une seule requête, de moins de 4000 caractères",
    "Only one read-only query: SELECT, WITH, SHOW, EXPLAIN, TABLE or VALUES": "Une seule requête en lecture seule : SELECT, WITH, SHOW, EXPLAIN, TABLE ou VALUES",
    "Choose between 0 and 720 minutes": "Choisissez entre 0 et 720 minutes",
    "Unknown country or continent": "Pays ou continent inconnu",
    "Only P-256 device keys are accepted": "Seules les clés d’appareil P-256 sont acceptées",
    "The query failed": "La requête a échoué",
    "That is not a valid IP address": "Ce n’est pas une adresse IP valide",
    "Busy, try again": "Occupé, réessayez",
    "Trivy is not installed on the server": "Trivy n’est pas installé sur le serveur",
    "A scan is already running": "Une analyse est déjà en cours",
    "A scan ran less than 10 minutes ago, try again later": "Une analyse a eu lieu il y a moins de 10 minutes, réessayez plus tard",
    "A deployment or update is running, try again after it finishes": "Un déploiement ou une mise à jour est en cours, réessayez après",
    "Only applications and compose services can be fixed automatically": "Seules les applications et les services compose peuvent être corrigés automatiquement",
    "Send enabled and allowStateful as true or false": "Envoyez enabled et allowStateful à true ou false",
    "Unknown service": "Service inconnu",
    "Databases are never fixed automatically": "Les bases de données ne sont jamais corrigées automatiquement",
}
FR_PREFIXES = {
    "These fields cannot be changed from the app: ": "Ces champs ne peuvent pas être modifiés depuis l’app : ",
    "Firewall refused the rules: ": "Le pare-feu a refusé les règles : ",
    "Always allowed, cannot be blocked: ": "Toujours autorisé, ne peut pas être bloqué : ",
    "Command failed: ": "La commande a échoué : ",
    "Could not run: ": "Impossible de lancer : ",
    "Not applied by the firewall: ": "Le pare-feu n’a pas appliqué le bannissement : ",
    "Still banned after unban: ": "Toujours banni après le débannissement : ",
    "The reboot could not be scheduled: ": "Le redémarrage n’a pas pu être programmé : ",
}


def to_french(message):
    if message in FR_ERRORS:
        return FR_ERRORS[message]
    for en, fr in FR_PREFIXES.items():
        if message.startswith(en):
            return fr + message[len(en):]
    if "does not appear to be an IPv4 or IPv6 address" in message:
        return "Ce n’est pas une adresse IP valide"
    return message


def fr(title, message=""):
    return {"titleFr": title, "messageFr": message}


def all_schedules():
    status, raw = projects_with_app_names()
    if status != 200:
        return []
    targets = []
    for p in json.loads(raw):
        for env in p.get("environments", []):
            for svc in env.get("applications") or []:
                targets.append(("application", svc.get("applicationId"), svc.get("name"), p.get("name")))
            for svc in env.get("compose") or []:
                targets.append(("compose", svc.get("composeId"), svc.get("name"), p.get("name")))
    out = []
    for kind, sid, name, project in targets:
        if not sid:
            continue
        code, body = dokploy("GET", "schedule.list", urlencode({"id": sid, "scheduleType": kind}), b"")
        if code != 200:
            continue
        for job in json.loads(body):
            out.append({**job, "serviceKind": kind, "serviceId": sid, "serviceName": name, "projectName": project})
    return out


class Unauthorized(Exception):
    pass


class Denied(Exception):
    pass


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    timeout = 30

    def log_message(self, *args):
        pass

    def client_ip(self):
        peer = self.client_address[0]
        forwarded = self.headers.get("X-Real-Ip", "").strip()
        if not forwarded or not (peer in proxy_ips or ipaddress.ip_address(peer).is_loopback):
            return peer
        edge = self.headers.get("Cf-Connecting-Ip", "").strip()
        return edge if edge and in_networks(forwarded, cf_networks()) else forwarded

    def reply(self, status, body, raw=False):
        if not raw and isinstance(body, dict) and "error" in body and body["error"] != "revoked" \
                and self.headers.get("Accept-Language", "").lower().startswith("fr"):
            body = {**body, "error": to_french(str(body["error"]))}
        data = body if raw else json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def read_body(self):
        if self.headers.get("Transfer-Encoding"):
            raise ValueError("Chunked bodies are not accepted")
        length = int(self.headers.get("Content-Length") or 0)
        if length < 0 or length > 262144:
            raise ValueError("Body too large")
        return self.rfile.read(length)

    def verify(self, body):
        device_id = self.headers.get("X-Helm-Device", "")
        stamp = self.headers.get("X-Helm-Time", "")
        nonce = self.headers.get("X-Helm-Nonce", "")
        signature = self.headers.get("X-Helm-Signature", "")
        if not (device_id and stamp.isdigit() and re.fullmatch(r"[0-9a-f]{32}", nonce) and signature):
            raise Unauthorized("missing signature")
        if abs(time.time() - int(stamp)) > CLOCK_SKEW:
            raise Unauthorized("clock skew, check the phone's time")
        with db() as con:
            device = con.execute("SELECT * FROM devices WHERE id=?", (device_id,)).fetchone()
        if device is None:
            raise Unauthorized("unknown device")
        if device["revoked"]:
            raise Unauthorized("revoked")
        message = "\n".join([self.command, self.path, stamp, nonce, hashlib.sha256(body).hexdigest()]).encode()
        try:
            key = serialization.load_der_public_key(base64.b64decode(device["pubkey"]))
            key.verify(base64.b64decode(signature, validate=True), message, ec.ECDSA(hashes.SHA256()))
        except (InvalidSignature, ValueError):
            raise Unauthorized("bad signature")
        now = time.time()
        with lock:
            for n, exp in list(nonces.items()):
                if exp < now:
                    del nonces[n]
            if nonce in nonces:
                raise Unauthorized("replayed request")
            nonces[nonce] = now + 2 * CLOCK_SKEW
        new_ip = self.client_ip()
        with db() as con:
            con.execute("UPDATE devices SET last_seen_at=?, last_ip=? WHERE id=?", (int(now), new_ip, device_id))
        if new_ip != device["last_ip"]:
            threading.Thread(target=ssh_lock_apply, daemon=True).start()
        return device

    def handle_one_request(self):
        if not slots.acquire(blocking=False):
            try:
                self.wfile.write(b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            except OSError:
                pass
            self.close_connection = True
            return
        try:
            super().handle_one_request()
        finally:
            slots.release()

    def do_GET(self):
        self.route()

    def do_POST(self):
        self.route()

    def do_PUT(self):
        self.route()

    def route(self):
        ip = self.client_ip()
        url = urlparse(self.path)
        path, qs = url.path, parse_qs(url.query)
        try:
            body = self.read_body()
            if not rate_ok(f"ip:{ip}", 600):
                return self.reply(429, {"error": "Too many requests, slow down"})
            if self.command == "GET" and path == "/health":
                return self.reply(200, {"ok": True, "version": VERSION, "claimed": is_claimed()})
            if self.command == "POST" and path == "/notify":
                return self.notify(body)
            if path.startswith("/pair/"):
                return self.pairing(path, qs, body, ip)
            device = self.verify(body)
            if not rate_ok(f"dev:{device['id']}", 300):
                return self.reply(429, {"error": "Too many requests, slow down"})
            return self.authed(device, path, url.query, qs, body, ip)
        except Unauthorized as e:
            if rate_ok(f"authfail:{ip}", 20):
                audit("-", "auth_failed", str(e), ip)
            return self.reply(401, {"error": str(e)})
        except Denied as e:
            return self.reply(403, {"error": str(e)})
        except ValueError as e:
            return self.reply(400, {"error": str(e)})
        except (BrokenPipeError, ConnectionResetError):
            return
        except Exception as e:
            print(f"{self.command} {path}: {type(e).__name__}: {e}", file=sys.stderr)
            try:
                self.reply(500, {"error": "The agent hit an internal error, it was logged"})
            except OSError:
                pass

    def notify(self, body):
        if ipaddress.ip_address(self.client_address[0]).is_global or self.headers.get("X-Forwarded-For"):
            return self.reply(403, {"error": "Forbidden"})
        secret = os.environ.get("HELM_HOOK_SECRET", "")
        if not secret or not hmac.compare_digest(self.headers.get("X-Helm-Hook", "").encode(), secret.encode()):
            return self.reply(401, {"error": "Bad hook secret"})
        payload = json.loads(body or b"{}")
        title = str(payload.get("title") or payload.get("event") or "Dokploy")[:200]
        message = str(payload.get("message") or payload.get("description") or "")[:1000]
        publish("dokploy", {"kind": dokploy_kind(f"{title} {message}"), "title": title, "message": message})
        return self.reply(200, {"ok": True})

    def pairing(self, path, qs, body, ip):
        global code_failures
        if not rate_ok(f"pair:{ip}", 6) or (self.command == "POST" and not rate_ok("pair:all", 3)):
            return self.reply(429, {"error": "Too many pairing attempts, wait a minute"})
        if self.command == "POST" and path == "/pair/claim":
            data = json.loads(body or b"{}")
            with lock:
                if is_claimed():
                    return self.reply(409, {"error": "This server already has an owner. Ask them to approve this phone."})
                if not hmac.compare_digest(str(data.get("code", "")).strip().upper().encode(), current_code().encode()):
                    code_failures += 1
                    audit("-", "claim_failed", f"attempt {code_failures}", ip)
                    if code_failures >= MAX_CODE_FAILURES:
                        rotate_code()
                        audit("-", "code_rotated", "too many wrong codes", ip)
                    return self.reply(401, {"error": "Wrong pairing code"})
                pubkey = parse_pubkey(str(data.get("publicKey", "")))
                device_id = secrets.token_hex(12)
                name = str(data.get("deviceName", "Phone"))[:60]
                with db() as con:
                    con.execute("INSERT INTO devices VALUES(?,?,?,?,?,?,?,0)", (device_id, name, "owner", pubkey, int(time.time()), int(time.time()), ip))
                os.remove(CODE_FILE)
            audit(name, "claimed", f"fingerprint {fingerprint(pubkey)}", ip)
            return self.reply(200, {"deviceId": device_id, "role": "owner"})
        if self.command == "POST" and path == "/pair/request":
            if not is_claimed():
                return self.reply(409, {"error": "No owner yet. Use the pairing code from the server."})
            data = json.loads(body or b"{}")
            pubkey = parse_pubkey(str(data.get("publicKey", "")))
            name = str(data.get("deviceName", "Phone"))[:60]
            now = int(time.time())
            with db() as con:
                con.execute("UPDATE pair_requests SET status='expired' WHERE status='pending' AND requested_at < ?", (now - 600,))
                if con.execute("SELECT count(*) n FROM pair_requests WHERE status='pending'").fetchone()["n"] >= 3:
                    return self.reply(429, {"error": "Too many phones waiting for approval"})
                if con.execute("SELECT 1 FROM devices WHERE pubkey=?", (pubkey,)).fetchone():
                    return self.reply(409, {"error": "This phone is already paired"})
                request_id = secrets.token_hex(16)
                con.execute("INSERT INTO pair_requests VALUES(?,?,?,?,?,'pending',NULL)", (request_id, pubkey, name, ip, now))
            audit(name, "pair_requested", f"fingerprint {fingerprint(pubkey)}", ip)
            publish("system", {**fr(f"{name} demande un accès root", f"Code {fingerprint(pubkey)} · depuis {ip}. N’approuvez que si c’est votre téléphone."), "kind": "pair_request",
                               "title": f"{name} asks for root access", "message": f"Code {fingerprint(pubkey)} · from {ip}. Approve only if this is your phone."}, owners_only=True)
            return self.reply(200, {"requestId": request_id, "fingerprint": fingerprint(pubkey)})
        if self.command == "GET" and path == "/pair/status":
            with db() as con:
                row = con.execute("SELECT status, device_id FROM pair_requests WHERE id=?", (qs.get("requestId", [""])[0],)).fetchone()
            if row is None:
                return self.reply(404, {"error": "Unknown request"})
            return self.reply(200, {"status": row["status"], **({"deviceId": row["device_id"]} if row["device_id"] else {})})
        return self.reply(404, {"error": "Not found"})

    def authed(self, device, path, query, qs, body, ip):
        owner = device["role"] == "owner"
        name = device["name"]
        if path.startswith("/dokploy/"):
            procedure = path.split("/", 2)[2]
            allowed = PROXY_GET if self.command == "GET" else PROXY_POST
            if procedure not in allowed:
                return self.reply(403, {"error": "This Dokploy action is not allowed from the app"})
            if procedure in PROXY_OWNER and not owner:
                return self.reply(403, {"error": "Only the owner phone can do this"})
            if procedure in PROXY_FIELDS:
                extra = set(json.loads(body or b"{}")) - PROXY_FIELDS[procedure]
                if extra:
                    return self.reply(403, {"error": f"These fields cannot be changed from the app: {', '.join(sorted(extra))}"})
            if self.command == "POST":
                audit(name, f"dokploy.{procedure}", body.decode(errors="replace")[:200], ip)
            if self.command == "GET":
                fetch = {"project.all": projects_with_app_names, "docker.getContainers": containers_with_app_names}.get(
                    procedure, lambda: dokploy("GET", procedure, query, b""))
                status, data = cached(f"proxy:{procedure}:{query}", 8, fetch)
                if status >= 400:
                    cache.pop(f"proxy:{procedure}:{query}", None)
            else:
                status, data = dokploy(self.command, procedure, query, body)
                for k in [k for k in cache if k.startswith("proxy:")]:
                    cache.pop(k, None)
            return self.reply(status, data, raw=True)
        if path.startswith("/coolify/"):
            sub = path.split("/", 2)[2]
            action = self.command in ("GET", "POST") and COOLIFY_ACTION.fullmatch(sub) is not None
            if not action and not (self.command == "GET" and COOLIFY_READ.fullmatch(sub)):
                return self.reply(403, {"error": "This Coolify action is not allowed from the app"})
            if action:
                audit(name, f"coolify.{sub}", query[:200], ip)
                status, data = coolify(self.command, sub, query, body)
                for k in [k for k in list(cache) if k.startswith("coolify:")]:
                    cache.pop(k, None)
            else:
                status, data = cached(f"coolify:{sub}:{query}", 8, lambda: coolify("GET", sub, query, b""))
                if status >= 400:
                    cache.pop(f"coolify:{sub}:{query}", None)
            return self.reply(status, data, raw=True)
        result = extra_routes(self.command, path, qs, body, owner, name, ip)
        if result:
            return self.reply(*result)
        if self.command == "GET":
            if path == "/stats":
                return self.reply(200, stats())
            if path == "/capabilities":
                return self.reply(200, cached("capabilities", 60, capabilities))
            if path == "/host":
                return self.reply(200, cached("host", 30, host))
            if path == "/containers":
                return self.reply(200, cached("containers", 10, containers))
            if path == "/uptime":
                return self.reply(200, uptime(int(qs.get("days", ["60"])[0])))
            if path == "/shield":
                return self.reply(200, cached("shield", 10, shield))
            if path.startswith("/shield/ip/"):
                return self.reply(200, ip_detail(str(ipaddress.ip_address(path.rsplit("/", 1)[1]))))
            if path == "/events/history":
                limit = min(int(qs.get("limit", ["100"])[0]), 300)
                with lock:
                    return self.reply(200, list(history)[-limit:][::-1])
            if path == "/events":
                return self.stream_events(device)
            if path.startswith("/logs/container/"):
                return self.stream_container(device, path.rsplit("/", 1)[1], int(qs.get("tail", ["200"])[0]))
            if path == "/logs/deployment":
                return self.stream_deployment(device, qs.get("path", [""])[0])
            if path == "/devices" and owner:
                return self.reply(200, devices())
            if path == "/logins" and owner:
                return self.reply(200, cached("logins", 30, logins))
            if path == "/shield/panic":
                return self.reply(200, panic_state())
            if path == "/geo":
                return self.reply(200, {**cached("geo", 30, geo_overview), "applied": geo_applied(), "yourCountry": country(ip) if ipaddress.ip_address(ip).is_global else min(NEVER_BLOCK, default=None)})
            if path == "/security":
                return self.reply(200, cached("security", 15, security))
            if path == "/security/live":
                return self.reply(200, cached("live", 10, live_attackers))
            if path == "/security/log" and owner:
                return self.reply(200, cached("security_log", 30, security_log))
            if path.startswith("/db/") and owner:
                return self.reply(200, db_info(path.split("/")[2], qs.get("database", [None])[0]))
            if path == "/audit" and owner:
                with db() as con:
                    rows = con.execute("SELECT at, device deviceName, action, detail, ip FROM audit ORDER BY at DESC LIMIT ?",
                                       (min(int(qs.get("limit", ["100"])[0]), 500),)).fetchall()
                return self.reply(200, [dict(r) for r in rows])
        if self.command == "POST":
            data = json.loads(body or b"{}")
            if path in ("/shield/unban", "/shield/allow") and owner:
                target = str(ipaddress.ip_address(str(data.get("ip", ""))))
                state = (unban if path.endswith("unban") else allow)(target)
                for key in ("shield", "security", "geo", "live"):
                    cache.pop(key, None)
                audit(name, path.rsplit("/", 1)[1], target, ip)
                return self.reply(200, state)
            if path in ("/geo/lowtrust", "/shield/originlock") and owner:
                control = path.rsplit("/", 1)[1]
                if not isinstance(data.get("enabled"), bool):
                    raise ValueError("Send enabled as true or false")
                try:
                    state = perimeter_set(control, data["enabled"])
                except ValueError as e:
                    audit(name, f"{control}_failed", str(e), ip)
                    raise
                cache.pop("security", None)
                audit(name, control, "on" if data["enabled"] else "off", ip)
                return self.reply(200, state)
            if path == "/geo/block" and owner:
                meta = countries_meta()
                wanted_c = sorted({str(c).upper() for c in data.get("countries", [])})
                wanted_k = sorted({str(c) for c in data.get("continents", [])})
                if any(c not in meta for c in wanted_c) or any(k not in CONTINENTS for k in wanted_k):
                    raise ValueError("Unknown country or continent")
                if NEVER_BLOCK & set(wanted_c):
                    raise ValueError(f"Always allowed, cannot be blocked: {', '.join(sorted(NEVER_BLOCK & set(wanted_c)))}")
                config = {"countries": wanted_c, "continents": wanted_k}
                try:
                    count = geo_apply(config, keep_open=[ip] if ipaddress.ip_address(ip).is_global else [])
                except ValueError as e:
                    audit(name, "geo_block_failed", str(e), ip)
                    raise
                save_json("geo.json", config)
                for key in ("geo", "security"):
                    cache.pop(key, None)
                audit(name, "geo_block", f"countries={','.join(wanted_c)} continents={','.join(wanted_k)}", ip)
                publish("system", {**fr("Blocage des régions mis à jour", f"{len(blocked_codes(config))} pays bloqués, {count} plages d’IP, par {name}"), "kind": "geo_changed", "title": "Region blocking updated",
                                   "message": f"{len(blocked_codes(config))} countries blocked, {count} IP ranges, by {name}"})
                return self.reply(200, {"ok": True, "blockedCountries": blocked_codes(config), "ranges": count, "applied": geo_applied()})
            if path == "/shield/ban" and owner:
                target = str(ipaddress.ip_address(str(data.get("ip", ""))))
                if not ipaddress.ip_address(target).is_global or target == ip or target in ignore_list() or local_country(target) in NEVER_BLOCK \
                        or in_networks(target, cf_networks()):
                    raise ValueError("This IP is protected (yours, an always-allowed country, Cloudflare, private or allowed) and cannot be banned")
                if near_owner(target) and data.get("confirmOwnNetwork") is not True:
                    raise ValueError("This IP is on the same network as one of your devices. Confirm on the IP screen to ban it anyway.")
                if data.get("duration") == "permanent" and data.get("confirmPermanent") is not True:
                    raise ValueError("A permanent ban needs a confirmation on the IP screen")
                state = ban(target, name, str(data.get("duration", "24h")))
                for key in ("shield", "geo", "security", "live"):
                    cache.pop(key, None)
                audit(name, "manual_ban", target, ip)
                publish("ban", {"ip": target, "source": "manual", "reason": f"Banned by {name}", "reasonFr": f"Banni par {name}"})
                return self.reply(200, state)
            if path == "/shield/panic" and owner:
                minutes = int(data.get("minutes", 60))
                if not 0 <= minutes <= 720:
                    raise ValueError("Choose between 0 and 720 minutes")
                panic_apply(time.time() + minutes * 60, extra_ip=ip if ipaddress.ip_address(ip).is_global else None)
                audit(name, "panic_on" if minutes else "panic_off", f"{minutes} min", ip)
                if minutes:
                    publish("system", {**fr("Verrouillage activé", f"Seules les IP autorisées pendant {minutes} min, par {name}"), "kind": "panic_on", "title": "Lockdown on", "message": f"Only allowed IPs for {minutes} min, by {name}"})
                return self.reply(200, panic_state())
            if path.startswith("/db/") and path.endswith("/query") and owner:
                result = db_query(path.split("/")[2], str(data.get("sql", "")), data.get("database"))
                audit(name, "sql_query", str(data.get("sql", ""))[:200], ip)
                return self.reply(200, result)
            if path.startswith("/devices/") and owner:
                return self.manage_devices(device, path.rsplit("/", 1)[1], data, ip)
        guarded = path.startswith(("/devices", "/audit", "/shield", "/geo", "/security"))
        return self.reply(403 if guarded else 404, {"error": "Only the owner phone can do this" if guarded else "Not found"})

    def manage_devices(self, device, action, data, ip):
        with db() as con:
            if action in ("approve", "deny"):
                req = con.execute("SELECT * FROM pair_requests WHERE id=? AND status='pending'", (str(data.get("requestId", "")),)).fetchone()
                if req is None:
                    return self.reply(404, {"error": "That request expired or was already handled"})
                if action == "deny":
                    con.execute("UPDATE pair_requests SET status='denied' WHERE id=?", (req["id"],))
                else:
                    device_id = secrets.token_hex(12)
                    con.execute("INSERT INTO devices VALUES(?,?,'member',?,?,NULL,?,0)", (device_id, req["name"], req["pubkey"], int(time.time()), req["ip"]))
                    con.execute("UPDATE pair_requests SET status='approved', device_id=? WHERE id=?", (device_id, req["id"]))
                audit(device["name"], f"device_{action}d", f"{req['name']} {fingerprint(req['pubkey'])}", ip)
                return self.reply(200, {"ok": True})
            if action == "revoke":
                target = con.execute("SELECT * FROM devices WHERE id=?", (str(data.get("deviceId", "")),)).fetchone()
                if target is None or target["id"] == device["id"]:
                    return self.reply(400, {"error": "You cannot revoke this phone from itself"})
                con.execute("UPDATE devices SET revoked=1 WHERE id=?", (target["id"],))
                audit(device["name"], "device_revoked", target["name"], ip)
                publish("system", {**fr(f"{target['name']} a été retiré", f"Par {device['name']}"), "kind": "device_revoked", "title": f"{target['name']} was removed", "message": f"By {device['name']}"}, owners_only=True)
                return self.reply(200, {"ok": True})
        return self.reply(404, {"error": "Not found"})

    def start_sse(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Accel-Buffering", "no")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        self.connection.settimeout(None)

    def sse(self, event, data):
        self.wfile.write(f"event: {event}\ndata: {json.dumps(data)}\n\n".encode())
        self.wfile.flush()

    def still_allowed(self, device):
        with db() as con:
            return not con.execute("SELECT revoked FROM devices WHERE id=?", (device["id"],)).fetchone()["revoked"]

    def stream_events(self, device):
        q: queue.Queue = queue.Queue()
        entry = (q, device["role"])
        with lock:
            if len(subscribers) >= 20:
                return self.reply(503, {"error": "Too many live connections"})
            subscribers.append(entry)
        self.start_sse()
        try:
            while self.still_allowed(device):
                try:
                    event = q.get(timeout=25)
                    self.sse(event["type"], {k: v for k, v in event.items() if k != "type"})
                except queue.Empty:
                    self.wfile.write(b": keepalive\n\n")
                    self.wfile.flush()
        except OSError:
            pass
        finally:
            with lock:
                subscribers.remove(entry)

    def stream_container(self, device, name, tail):
        if not CONTAINER_NAME.match(name):
            raise ValueError("Bad container name")
        proc = subprocess.Popen(["docker", "logs", "-f", "--timestamps", "--tail", str(min(max(tail, 1), 2000)), name],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace")
        self.start_sse()
        try:
            for count, raw in enumerate(proc.stdout):
                stamp, _, line = raw.rstrip("\n").partition(" ")
                self.sse("line", {"t": stamp[:19] + "Z", "line": line})
                if count % 200 == 0 and not self.still_allowed(device):
                    break
        except OSError:
            pass
        finally:
            proc.kill()

    def stream_deployment(self, device, log_path):
        real = os.path.realpath(log_path)
        if not real.startswith(DEPLOY_LOGS) or not os.path.isfile(real):
            raise ValueError("Unknown deployment log")
        self.start_sse()
        idle = 0
        with open(real, errors="replace") as f:
            while idle < 120 and self.still_allowed(device):
                line = f.readline()
                if line:
                    idle = 0
                    self.sse("line", {"t": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "line": line.rstrip("\n")})
                else:
                    idle += 1
                    time.sleep(1)
        self.sse("end", {})


def devices():
    with db() as con:
        rows = con.execute("SELECT id deviceId, name, role, added_at addedAt, last_seen_at lastSeenAt, last_ip lastIp FROM devices WHERE revoked=0").fetchall()
        pending = con.execute("SELECT id, name, ip, requested_at, pubkey FROM pair_requests WHERE status='pending' AND requested_at > ?",
                              (int(time.time()) - 600,)).fetchall()
    return {"devices": [dict(r) for r in rows],
            "pending": [{"requestId": p["id"], "deviceName": p["name"], "ip": p["ip"], "requestedAt": p["requested_at"], "fingerprint": fingerprint(p["pubkey"])} for p in pending]}


def selftest():
    load_geo()
    cloudflare = cf_networks()
    failed = False
    for label, rules, families in (("geo, every continent blocked", geo_rules({"countries": [], "continents": list(CONTINENTS)})[0], (4,)),
                                   ("panic", panic_rules(), (4, 6))):
        check = subprocess.run(["nft", "-c", "-f", "-"], input=rules, capture_output=True, text=True, timeout=120)
        allowed = [ipaddress.ip_network(x.strip(), strict=False) for m in re.finditer(r"set allow[46] \{[^}]*elements = \{ ([^}]*) \}", rules)
                   for x in m.group(1).split(",") if x.strip()]
        missing = [str(n) for n in cloudflare if n.version in families and not any(n.version == a.version and n.subnet_of(a) for a in allowed)]
        chains = [l for l in rules.splitlines() if "hook" in l]
        ordered = all(l.index("saddr @allow4 accept") < l.index("drop") for l in chains)
        v6_dropped = 6 not in families and any("ip6 saddr" in l and "drop" in l for l in chains)
        smtp_open = label == "panic" or all("tcp dport 25 accept" in l and l.index("tcp dport 25 accept") < l.index("drop") for l in chains)
        ok = check.returncode == 0 and not missing and ordered and not v6_dropped and smtp_open
        failed |= not ok
        print(f"{'PASS' if ok else 'FAIL'} {label}: nft -c rc={check.returncode} {check.stderr.strip()[:200]}; "
              f"{len(cloudflare)} Cloudflare ranges, missing from allow sets: {missing or 'none'}; allow before drop: {ordered}; port 25 accepted first: {smtp_open}")
    sys.exit(1 if failed else 0)


def reset():
    init_db()
    with db() as con:
        con.execute("UPDATE devices SET revoked=1")
        con.execute("UPDATE pair_requests SET status='expired' WHERE status='pending'")
    audit("root", "reset", "all phones removed", "local")
    rotate_code()
    print(f"All phones removed. New pairing code: {current_code()}")


if __name__ == "__main__":
    if sys.argv[1:] == ["reset"]:
        reset()
        sys.exit(0)
    if sys.argv[1:] == ["selftest"]:
        selftest()
    if sys.argv[1:] == ["reboot-if-safe"]:
        reboot_if_safe()
        sys.exit(0)
    init_db()
    if not is_claimed():
        current_code()
    try:
        agent_firewall_pass()
    except Exception as e:
        print(f"agent firewall: {e}", file=sys.stderr)
    history.extend(load_json("events.json", []))
    mem_history.extend(load_json("mem.json", []))
    disk_history.extend(load_json("disk.json", []))
    for target in (sampler, ban_watcher, uptime_monitor, docker_events, login_watcher, panic_watcher, warmer, geo_watcher, reboot_followup):
        threading.Thread(target=target, daemon=True).start()
    for seconds, delay, job in ((60, 5, config_pass), (300, 60, sample_containers), (900, 30, env_pass), (1800, 120, secrets_pass), (60, 45, logwatch_pass),
                                (600, 90, traffic_pass), (300, 20, reboot_plan_pass), (30, 10, outbox_pass), (60, 0, agent_firewall_pass), (60, 30, threat_pass), (60, 15, mail_pass), (600, 300, audit_pass)):
        threading.Thread(target=every, args=(seconds, delay, job), daemon=True).start()
    ThreadingHTTPServer.daemon_threads = True
    ThreadingHTTPServer((BIND, 9311), Handler).serve_forever()
