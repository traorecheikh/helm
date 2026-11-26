package com.orvlabs.helm.ml

import com.orvlabs.helm.data.Attacker
import com.orvlabs.helm.data.Ban
import com.orvlabs.helm.data.HitPath
import com.orvlabs.helm.data.IpDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbesTest {
    private fun sshFails(ip: String, n: Int) = List(n) { i ->
        if (i % 2 == 0) "2026-11-25T03:1$i:02+0000 helm sshd[4411]: Failed password for invalid user admin from $ip port ${40000 + i} ssh2"
        else "2026-11-25T03:1$i:05+0000 helm sshd[4411]: Invalid user oracle from $ip port ${40000 + i}"
    }

    private fun web(path: String, status: Int, host: String = "app.example.com") = "11-25 03:12  GET $host$path  $status"

    @Test fun ownerSshSuccessThenWeb404sIsTrusted() {
        val d = IpDetail(
            ip = "41.82.10.5", country = "SN",
            sshLines = listOf("2026-11-25T10:01:02+0000 helm sshd[1234]: Accepted publickey for root from 41.82.10.5 port 51234 ssh2: ED25519 SHA256:x"),
            webRequests = listOf(web("/api/v2/projects", 404), web("/favicon.ico", 404), web("/api/v2/health", 404)),
            webRequestCount = 3,
        )
        val v = classify(d.probe(null))
        assertEquals(Family.You, v.family)
        assertEquals(Severity.Noise, v.severity)
        assertTrue(v.trusted)
        assertFalse(v.doubt)
    }

    @Test fun trustedSetMarksPairedDeviceAsYou() {
        val a = Attacker(ip = "41.82.10.5", hits = 40, statuses = mapOf("404" to 30, "200" to 10), topPaths = listOf(HitPath("/admin", 30)))
        assertEquals(Family.Vuln, classify(a.probe()).family)
        assertEquals(Family.You, classify(a.probe(setOf("41.82.10.5"))).family)
        assertEquals(Level.Calm, readShield(listOf(a.probe(setOf("41.82.10.5")))).level)
    }

    @Test fun bruteForceWithOnlyFailuresIsAttacker() {
        val d = IpDetail(ip = "185.220.101.4", banned = true, source = "ssh", reason = "12 SSH attempts", evidence = sshFails("185.220.101.4", 12))
        val v = classify(d.probe(null))
        assertEquals(Family.Ssh, v.family)
        assertEquals(Severity.Low, v.severity)
        assertEquals("SSH · 12×", v.line)
        val heavy = classify(d.copy(reason = "140 SSH attempts").probe(null))
        assertEquals(Severity.Real, heavy.severity)
    }

    @Test fun successAfterManyFailuresIsTrustedButDoubtful() {
        val lines = sshFails("203.0.113.9", 14) + "2026-11-25T03:30:00+0000 helm sshd[4411]: Accepted password for deploy from 203.0.113.9 port 40100 ssh2"
        val v = classify(Probe("203.0.113.9", "ssh", lines = lines))
        assertTrue(v.trusted)
        assertTrue(v.doubt)
    }

    @Test fun attackerUsernameCannotFakeAnOwnerLogin() {
        val lines = sshFails("198.51.100.77", 12) + "2026-11-25T03:40:00+0000 helm sshd[4500]: Failed password for x Accepted password for root from 10.9.9.9 port 1 ssh2"
        val v = classify(Probe("198.51.100.77", "ssh", lines = lines))
        assertEquals(Family.Ssh, v.family)
        assertFalse(v.trusted)
    }

    @Test fun envAndGitHuntIsLowUntilSomethingLeaks() {
        val a = Attacker(ip = "45.148.10.7", country = "NL", hits = 9, statuses = mapOf("404" to 9), topPaths = listOf(HitPath("/.env", 6), HitPath("/.git/config", 3)))
        val v = classify(a.probe())
        assertEquals(Family.Secrets, v.family)
        assertEquals(Severity.Low, v.severity)
        assertEquals("/.env +1 · 9×", v.line)
        val leak = IpDetail(ip = "45.148.10.7", webRequests = listOf(web("/.env", 200), web("/.git/config", 404)), webRequestCount = 2)
        assertEquals(Severity.Real, classify(leak.probe(a)).severity)
    }

    @Test fun wordpressScanAndCrawlerNoise() {
        val wp = Attacker(ip = "193.32.162.11", hits = 25, statuses = mapOf("404" to 25), topPaths = listOf(HitPath("/wp-login.php", 15), HitPath("/xmlrpc.php", 10)))
        assertEquals(Family.Php, classify(wp.probe()).family)
        val bot = Attacker(ip = "66.249.66.1", hits = 12, statuses = mapOf("200" to 12), topPaths = listOf(HitPath("/", 8), HitPath("/robots.txt", 4)), agent = "Googlebot/2.1")
        val v = classify(bot.probe())
        assertEquals(Family.Visitor, v.family)
        assertEquals(Severity.Noise, v.severity)
        val miss = Attacker(ip = "66.249.66.2", hits = 11, statuses = mapOf("404" to 11), topPaths = listOf(HitPath("/favicon.ico", 11)), agent = "Mozilla/5.0")
        assertEquals(Severity.Noise, classify(miss.probe()).severity)
    }

    @Test fun webBanWithoutEvidenceNeverEchoesUselessReason() {
        val b = Ban(ip = "198.51.100.23", source = "web", jail = "traefik-scan", reason = "Probed a known attack path", bannedAt = 1_764_000_000)
        val v = classify(b.probe())
        assertEquals(Family.Vuln, v.family)
        assertFalse(v.line.contains("Probed"))
        val live = Attacker(ip = b.ip, hits = 7, statuses = mapOf("404" to 7), topPaths = listOf(HitPath("/.env", 7)))
        assertEquals("/.env · 7×", classify(b.probe(live)).line)
    }

    @Test fun oneSubnetIsOneCampaignNotAnAttack() {
        val wp = listOf(HitPath("/wp-login.php", 4))
        val same = (1..3).map { Attacker(ip = "193.32.162.$it", country = "RO", hits = 4, statuses = mapOf("404" to 4), topPaths = wp, lastSeen = 1_764_000_000L + it) }
        val read = readShield(same.map { it.probe() })
        assertEquals(Level.Probing, read.level)
        assertEquals(3, read.campaigns["193.32.162.1"])

        val distinct = listOf(
            Attacker(ip = "193.32.162.1", country = "RO", hits = 4, statuses = mapOf("404" to 4), topPaths = wp, lastSeen = 1_764_000_000),
            Attacker(ip = "45.148.10.7", country = "NL", hits = 3, statuses = mapOf("404" to 3), topPaths = listOf(HitPath("/.env", 3)), lastSeen = 1_764_000_100),
            Attacker(ip = "103.77.4.9", country = "VN", hits = 5, statuses = mapOf("404" to 5), topPaths = listOf(HitPath("/cgi-bin/luci", 5)), lastSeen = 1_764_000_200),
        )
        assertEquals(Level.Attack, readShield(distinct.map { it.probe() }).level)
    }

    @Test fun singleProbeOrProbablyYouIsNotUnderAttack() {
        val one = Attacker(ip = "45.148.10.7", hits = 2, statuses = mapOf("404" to 2), topPaths = listOf(HitPath("/.env", 2)))
        assertEquals(Level.Probing, readShield(listOf(one.probe())).level)
        assertEquals(Level.Calm, readShield(listOf(one.copy(probablyYou = true).probe())).level)
        assertEquals(Level.Calm, readShield(listOf(one.copy(banned = true).probe())).level)
    }
}
