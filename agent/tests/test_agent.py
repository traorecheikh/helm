import base64, hashlib, http.client, importlib.util, json, os, secrets, subprocess, sys, tempfile, threading, time, unittest
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock

for name in [k for k in os.environ if k.startswith("HELM_")]:
    del os.environ[name]
SPEC = importlib.util.spec_from_file_location("helm_agent", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "helm_agent.py"))
agent = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(agent)

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


class Base(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        agent.STATE = cls.tmp.name
        agent.CODE_FILE = f"{cls.tmp.name}/pair-code"
        agent.JAIL_LOCAL = f"{cls.tmp.name}/jail.local"
        agent.F2B_DB = f"{cls.tmp.name}/fail2ban.sqlite3"
        agent.init_db()
        cls.keys = {}
        with agent.db() as con:
            for role in ("owner", "member"):
                key = ec.generate_private_key(ec.SECP256R1())
                der = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
                con.execute("INSERT INTO devices VALUES(?,?,?,?,?,?,?,0)", (role, role, role, base64.b64encode(der).decode(), 0, 0, "127.0.0.1"))
                cls.keys[role] = key
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), agent.Handler)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.tmp.cleanup()

    def call(self, method, path, role="owner", body=b""):
        stamp, nonce = str(int(time.time())), secrets.token_hex(16)
        message = "\n".join([method, path, stamp, nonce, hashlib.sha256(body).hexdigest()]).encode()
        signature = base64.b64encode(self.keys[role].sign(message, ec.ECDSA(hashes.SHA256()))).decode()
        con = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=60)
        con.request(method, path, body, {"X-Helm-Device": role, "X-Helm-Time": stamp, "X-Helm-Nonce": nonce,
                                         "X-Helm-Signature": signature, "Content-Length": str(len(body))})
        r = con.getresponse()
        data = r.read()
        con.close()
        return r.status, json.loads(data or b"null")


class AgentTest(Base):
    def test_bind_defaults_to_loopback(self):
        self.assertEqual(agent.BIND, "127.0.0.1")

    def test_capabilities_with_nothing_configured(self):
        for role in ("owner", "member"):
            status, caps = self.call("GET", "/capabilities", role)
            self.assertEqual(status, 200)
            self.assertEqual(set(caps), {"platform", "dokploy", "coolify", "docker", "fail2ban", "crowdsec", "traefik", "stalwart", "ssh", "version"})
            self.assertEqual((caps["platform"], caps["dokploy"], caps["coolify"], caps["stalwart"], caps["ssh"]), ("none", False, False, False, False))
            self.assertEqual(caps["version"], agent.VERSION)
            self.assertTrue(all(isinstance(caps[k], bool) for k in caps if k not in ("platform", "version")))

    def test_crash_loop_alerts_once_until_quiet_recovery(self):
        self.assertTrue(agent.alert_once("web", "container_died"))
        self.assertFalse(agent.alert_once("web", "container_died"))
        self.assertTrue(agent.alert_once("web", "oom"))
        self.assertTrue(agent.alert_once("web-2", "container_died"))
        with agent.db() as con:
            self.assertFalse(agent.clear_alerts(con, "web", int(time.time()) - 120))
            self.assertTrue(agent.clear_alerts(con, "web", int(time.time()) + 1))
            self.assertFalse(agent.clear_alerts(con, "web", int(time.time()) + 1))
        self.assertTrue(agent.alert_once("web", "container_died"))
        self.assertFalse(agent.alert_once("web-2", "container_died"))

    def test_missing_dokploy_key_is_a_clean_error(self):
        status, body = self.call("GET", "/dokploy/project.all")
        self.assertEqual((status, body), (503, {"error": "Dokploy is not configured"}))
        self.assertFalse(agent.deploy_running())

    def test_ssh_lock_is_off_by_default(self):
        self.assertEqual(agent.NEVER_BLOCK, set())
        os.makedirs(f"{agent.STATE}/zones", exist_ok=True)
        with open(agent.zone_path("SN"), "w") as f:
            f.write("41.82.0.0/16\n")
        calls, real = [], subprocess.run
        def spy(cmd, *args, **kwargs):
            calls.append(list(cmd))
            return real(cmd, *args, **kwargs)
        with mock.patch("subprocess.run", spy):
            self.assertEqual(agent.ssh_lock_apply(), 0)
        self.assertFalse(any(c[:2] == ["nft", "-f"] for c in calls), calls)

    def test_coolify_allow_list(self):
        for path in ("/coolify/security/keys", "/coolify/teams", "/coolify/private-keys", "/coolify/cloud-tokens",
                     "/coolify/applications/abc/envs", "/coolify/projects/abc/envs", "/coolify/applications/abc%2F..%2Fsecurity", "/coolify/disable"):
            self.assertEqual(self.call("GET", path)[0], 403, path)
        self.assertEqual(self.call("POST", "/coolify/applications")[0], 403)
        self.assertEqual(self.call("PUT", "/coolify/deploy")[0], 403)
        self.assertEqual(self.call("GET", "/coolify/applications"), (503, {"error": "Coolify is not configured"}))
        self.assertEqual(self.call("POST", "/coolify/applications/abc/restart", "member"), (503, {"error": "Coolify is not configured"}))

    def test_fresh_install_defaults(self):
        self.assertEqual(agent.ignore_list(), [])
        self.assertFalse(agent.rb_load()["autoReboot"])


WEB = {"Id": "c1", "Name": "/web-app.1.x", "Image": "sha256:aaa", "Config": {"Image": "web:1", "Labels": {"com.docker.swarm.service.name": "web-app"}},
       "Mounts": [], "State": {"Running": True, "Restarting": False}}
DB = {"Id": "c2", "Name": "/db-stack-postgres-1", "Image": "sha256:bbb", "Config": {"Image": "postgres:16", "Labels": {"com.docker.compose.project": "db-stack"}},
      "Mounts": [{"Type": "volume"}], "State": {"Running": True, "Restarting": False}}
PROJECTS = [{"name": "p", "environments": [{"applications": [{"applicationId": "app1", "appName": "web-app", "name": "web"}],
                                            "compose": [{"composeId": "cmp1", "appName": "db-stack", "name": "db"}]}]}]
QUIET = 1_795_669_800  # 2026-11-26 05:10 UTC, inside the learning quiet window that starts at 04:30
BUSY = QUIET + 7 * 3600


def vuln(cve, pkg, severity, fixed="2.0"):
    return {"VulnerabilityID": cve, "PkgName": pkg, "InstalledVersion": "1.0", "FixedVersion": fixed, "Severity": severity}


def fake_bin(folder, name, body):
    path = f"{folder}/{name}"
    with open(path, "w") as f:
        f.write(f"#!{sys.executable}\nimport json, os, sys\nD = {os.path.dirname(folder)!r}\na = sys.argv[1:]\n{body}\n")
    os.chmod(path, 0o755)


class VulnTest(Base):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cls.fake = tempfile.TemporaryDirectory()
        root = cls.fake.name
        for d in ("bin", "trivybin", "trivy"):
            os.makedirs(f"{root}/{d}")
        fake_bin(f"{root}/bin", "docker", "cs = json.load(open(D + '/containers.json'))\n"
                 "if a[:1] == ['ps']: print('\\n'.join(c['Id'] for c in cs))\n"
                 "if a[:1] == ['inspect']: print(json.dumps([c for c in cs if c['Id'] in a[1:]]))")
        fake_bin(f"{root}/bin", "needrestart", "print('NEEDRESTART-VER: 3.6\\nNEEDRESTART-KSTA: 3\\nNEEDRESTART-UCSTA: 1')")
        fake_bin(f"{root}/bin", "unattended-upgrade", "")
        fake_bin(f"{root}/bin", "apt-config", "print('APT::Periodic::Unattended-Upgrade \"1\";')")
        fake_bin(f"{root}/bin", "apt", "")
        fake_bin(f"{root}/bin", "apt-mark", "")
        fake_bin(f"{root}/trivybin", "trivy", "open(D + '/calls.jsonl', 'a').write(json.dumps({'args': a, 'env': sorted(os.environ)}) + '\\n')\n"
                 "if a == ['--version']: print('Version: 0.0.0-test'); sys.exit(0)\n"
                 "if '--download-db-only' in a: sys.exit(0)\n"
                 "name = 'host' if a[0] == 'rootfs' else a[-1].replace(':', '_')\n"
                 "p = D + '/trivy/' + name + '.json'\n"
                 "sys.exit(print(open(p).read()) if os.path.exists(p) else 'unknown image')")
        for name, vulns in (("host", [vuln("CVE-H", "openssl", "HIGH")]), ("web_1", [vuln("CVE-W", "libfoo", "CRITICAL"), vuln("CVE-X", "libbar", "HIGH", "")]),
                            ("postgres_16", [vuln("CVE-P", "libpq", "CRITICAL")])):
            with open(f"{root}/trivy/{name}.json", "w") as f:
                json.dump({"Results": [{"Target": name, "Vulnerabilities": vulns}]}, f)
        cls.path = f"{root}/trivybin:{root}/bin:{os.environ['PATH']}"
        cls.calls, cls.deployments, cls.after = [], [], "healthy"
        test = cls

        class Dokploy(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                route = self.path.split("?")[0].rsplit("/", 1)[1]
                body = {"project.all": PROJECTS, "deployment.all": test.deployments}.get(route, [])
                self.answer(body)

            def do_POST(self):
                route = self.path.rsplit("/", 1)[1]
                test.calls.append(route)
                if route == "application.redeploy":
                    test.deployments.append({"deploymentId": "d1", "status": "done", "createdAt": datetime.now(timezone.utc).isoformat()})
                    test.containers([{**WEB, "State": {"Running": test.after == "healthy", "Restarting": test.after != "healthy"}}, DB])
                if route == "rollback.rollback":
                    test.containers([WEB, DB])
                self.answer({})

            def answer(self, body):
                data = json.dumps(body).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        cls.dokploy = ThreadingHTTPServer(("127.0.0.1", 0), Dokploy)
        threading.Thread(target=cls.dokploy.serve_forever, daemon=True).start()
        agent.DOKPLOY = f"http://127.0.0.1:{cls.dokploy.server_port}/api/"
        agent.UU_LOG = f"{root}/uu.log"
        agent.AUTOFIX_POLL, agent.AUTOFIX_HEALTH_TIMEOUT = 0.01, 0.3

    @classmethod
    def tearDownClass(cls):
        cls.dokploy.shutdown()
        cls.fake.cleanup()
        super().tearDownClass()

    @classmethod
    def containers(cls, rows):
        with open(f"{cls.fake.name}/containers.json", "w") as f:
            json.dump(rows, f)

    def setUp(self):
        self.env = mock.patch.dict(os.environ, {"PATH": self.path, "HELM_DOKPLOY_KEY": "test"})
        self.env.start()
        self.dpkg = mock.patch.object(agent, "apt_locked", return_value=False)
        self.dpkg.start()
        agent.cache.clear()
        type(self).calls.clear()
        type(self).deployments[:] = [{"deploymentId": "d0", "status": "done", "createdAt": "2026-01-01T00:00:00.000Z", "rollbackId": "rb0"}]
        type(self).after = "healthy"
        self.containers([WEB, DB])
        for name in ("audit.json", "autofix.json"):
            if os.path.exists(f"{agent.STATE}/{name}"):
                os.remove(f"{agent.STATE}/{name}")

    def tearDown(self):
        self.dpkg.stop()
        self.env.stop()

    def enable(self, kind, sid):
        return self.call("PUT", "/vulns/autofix", body=json.dumps({"platform": "dokploy", "kind": kind, "id": sid, "enabled": True}).encode())

    def test_patch_status_in_updates(self):
        with open(agent.UU_LOG, "w") as f:
            f.write("2026-11-25 06:10:00,100 INFO Starting unattended upgrades script\n2026-11-25 06:10:05,000 INFO Packages that will be upgraded: libssl3 openssl\n"
                    "2026-11-25 06:10:30,000 INFO All upgrades installed\n")
        status, u = self.call("GET", "/updates", "member")
        self.assertEqual(status, 200)
        self.assertEqual((u["unattended"]["enabled"], u["unattended"]["upgraded"], u["unattended"]["ok"]), (True, ["libssl3", "openssl"], True))
        self.assertTrue(u["kernelPending"])
        self.assertFalse(u["microcodePending"])

    def test_scan_endpoints_and_guards(self):
        with mock.patch.dict(os.environ, {"PATH": f"{self.fake.name}/bin:/usr/bin:/bin"}):
            self.assertFalse(self.call("GET", "/vulns")[1]["installed"])
            self.assertEqual(self.call("POST", "/vulns/run"), (400, {"error": "Trivy is not installed on the server"}))
        self.assertEqual(self.call("POST", "/vulns/run", "member")[0], 403)
        with mock.patch.dict(os.environ, {"HELM_NO_AUTOFIX": "1"}):
            self.assertEqual(self.call("POST", "/vulns/run"), (202, {"started": True}))
            self.assertEqual(self.call("POST", "/vulns/run")[0], 400)
            for _ in range(200):
                if os.path.exists(f"{agent.STATE}/audit.json") and not agent.audit_lock.locked():
                    break
                time.sleep(0.05)
        status, report = self.call("GET", "/vulns", "member")
        self.assertEqual(status, 200)
        self.assertEqual((report["counts"], report["fixable"]), ({"critical": 2, "high": 2}, {"critical": 2, "high": 1}))
        web = next(t for t in report["targets"] if t["target"] == "web:1")
        db = next(t for t in report["targets"] if t["target"] == "postgres:16")
        self.assertEqual((web["service"]["id"], web["database"], web["stateful"]), ("app1", False, False))
        self.assertEqual((db["service"]["kind"], db["database"], db["stateful"]), ("compose", True, True))
        self.assertTrue(any(e.get("kind") == "vuln_critical" for e in agent.history))
        with open(f"{self.fake.name}/calls.jsonl") as f:
            calls = [json.loads(l) for l in f]
        self.assertIn(["--pkg-types", "os"], [c["args"][i:i + 2] for c in calls for i in range(len(c["args"]))])
        self.assertFalse(any(k.startswith("HELM_") for c in calls for k in c["env"]))
        self.assertEqual(self.enable("compose", "cmp1"), (400, {"error": "Databases are never fixed automatically"}))
        self.assertEqual(self.enable("application", "nope")[0], 400)
        self.assertEqual(self.call("PUT", "/vulns/autofix", "member", json.dumps({"platform": "dokploy", "kind": "application", "id": "app1", "enabled": True}).encode())[0], 403)

    def test_autofix_tiers(self):
        agent.audit_run("test", now=BUSY)
        self.assertEqual(self.enable("application", "app1")[0], 200)
        agent.autofix_save("dokploy:compose:cmp1", platform="dokploy", kind="compose", id="cmp1", name="db", enabled=True, allowStateful=True)
        agent.audit_run("test", now=BUSY)
        self.assertEqual(self.calls, [])
        with mock.patch.dict(os.environ, {"HELM_NO_AUTOFIX": "1"}):
            agent.audit_run("test", now=QUIET)
        self.assertEqual(self.calls, [])
        agent.audit_run("test", now=QUIET)
        self.assertEqual(self.calls, ["application.redeploy"])
        settings = agent.load_json("autofix.json", {})
        self.assertEqual(settings["dokploy:application:app1"]["last"]["result"], "done")
        self.assertEqual(settings["dokploy:compose:cmp1"]["last"]["result"], "skipped:database")
        self.assertTrue(any(e.get("kind") == "autofix_done" for e in agent.history))
        agent.audit_run("test", now=QUIET + 600)
        self.assertEqual(self.calls, ["application.redeploy"])
        with agent.db() as con:
            self.assertTrue(con.execute("SELECT 1 FROM audit WHERE action='autofix_redeploy' AND detail LIKE 'dokploy:application:app1 CVE-W%'").fetchone())

    def test_autofix_failed_health_rolls_back(self):
        type(self).after = "broken"
        agent.audit_run("test", now=BUSY)
        self.assertEqual(self.enable("application", "app1")[0], 200)
        agent.audit_run("test", now=QUIET)
        self.assertEqual(self.calls, ["application.redeploy", "rollback.rollback"])
        self.assertEqual(agent.load_json("autofix.json", {})["dokploy:application:app1"]["last"]["result"], "failed:unhealthy:rolled_back")
        failed = [e for e in agent.history if e.get("kind") == "autofix_failed"]
        self.assertTrue(failed and "Rolled back" in failed[-1]["message"])


if __name__ == "__main__":
    unittest.main()
