package com.orvlabs.helm.data

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VulnsFixturesTest {
    private fun text(name: String) = javaClass.getResource("/agent/$name.json")!!.readText()

    @Test fun vulns() {
        val v = AppJson.decodeFromString<Vulns>(text("vulns"))
        assertEquals(SevCounts(1, 1), v.fixable)
        val web = v.targets.first { it.target == "web:1" }
        assertEquals("dokploy:application:app1", web.service?.key)
        assertEquals("libfoo", web.findings.first().pkg)
        assertTrue(v.targets.last().database)
        assertEquals(web.service?.key, v.autofix.single().key)
    }

    @Test fun emptyAgentAnswer() {
        val v = AppJson.decodeFromString<Vulns>("""{"installed": false, "running": false, "autofixOff": true, "autofix": []}""")
        assertEquals(null, v.at)
        assertTrue(v.targets.isEmpty())
    }

    @Test fun updatesWithPatching() {
        val u = AppJson.decodeFromString<Updates>(
            """{"pending": [], "held": [], "unattended": {"installed": true, "enabled": true, "lastRun": 1795600000, "upgraded": ["openssl"], "keptBack": [], "ok": true, "error": null},
               "kernelPending": true, "microcodePending": false, "autoReboot": false}""",
        )
        assertTrue(u.kernelPending)
        assertEquals(listOf("openssl"), u.unattended?.upgraded)
        assertEquals(null, AppJson.decodeFromString<Updates>("{}").unattended)
    }
}
