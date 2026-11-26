package com.orvlabs.helm.data

import com.orvlabs.helm.ui.components.Tone
import com.orvlabs.helm.ui.screens.svcTone
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoolifyFixturesTest {
    private fun text(name: String) = javaClass.getResource("/coolify/$name.json")!!.readText()

    @Test fun capabilities() {
        val caps = AppJson.decodeFromString<Caps>(text("capabilities"))
        assertEquals("coolify", caps.platform)
        assertTrue(caps.coolify && caps.docker && !caps.dokploy)
        assertEquals("coolify", caps.pick("dokploy"))
        assertEquals("dokploy", Caps().pick(null))
    }

    @Test fun projectsGroupResourcesByEnvironment() {
        val projects = coolifyProjects(
            AppJson.decodeFromString(text("projects")),
            mapOf("p1abc" to AppJson.decodeFromString(text("environments"))),
            AppJson.decodeFromString(text("resources")),
        )
        assertEquals(listOf("Shop", "Blog"), projects.map { it.name })
        val shop = projects[0].services.associateBy { it.name }
        assertEquals(setOf("storefront", "plausible", "shop-db", "cache", "worker"), shop.keys)
        assertEquals("application" to "app1", shop.getValue("storefront").let { it.kind to it.id })
        assertEquals("compose", shop.getValue("plausible").kind)
        assertEquals("postgres", shop.getValue("shop-db").kind)
        assertEquals("redis", shop.getValue("cache").kind)
        assertEquals("app1", shop.getValue("storefront").appName)
        assertTrue(projects[1].services.isEmpty())
    }

    @Test fun statusMapsToTone() {
        assertEquals(Tone.Ok, svcTone(coolifyStatus("running:healthy")))
        assertEquals(Tone.Ok, svcTone(coolifyStatus("running:unknown")))
        assertEquals(Tone.Signal, svcTone(coolifyStatus("exited:unhealthy")))
        assertEquals(Tone.Signal, svcTone(coolifyStatus("running:unhealthy")))
        assertEquals(Tone.Warn, svcTone(coolifyStatus("restarting")))
        assertEquals(Tone.Ring, svcTone(coolifyStatus("exited")))
        assertEquals(Tone.Ring, svcTone(coolifyStatus(null)))
    }

    @Test fun application() {
        val app = AppJson.decodeFromString<CApp>(text("application"))
        assertEquals("acme/storefront" to "main", app.repository to app.branch)
    }

    @Test fun deploymentPageDropsHiddenLogEntries() {
        val page = AppJson.parseToJsonElement(text("deployments.app")) as JsonObject
        val list = page.getValue("deployments").jsonArray.map { AppJson.decodeFromJsonElement<CDeployment>(it).toDeployment("app1") }
        val failed = list[0]
        assertEquals("error", failed.status)
        assertEquals("application" to "app1", failed.kind to failed.serviceId)
        assertEquals("2026-11-20T09:03:10.000000Z", failed.finishedAt)
        val lines = failed.log.orEmpty().map { it.line }
        assertEquals(listOf("Starting deployment", "error: build failed", "exit code 1"), lines)
        assertFalse(lines.any { "secret" in it })
        assertEquals("done", list[1].status)
        assertNull(list[1].log)
    }

    @Test fun singleDeploymentWithPlainLogs() {
        val d = AppJson.decodeFromString<CDeployment>(text("deployment")).toDeployment()
        assertEquals("running" to "app1", d.status to d.applicationId)
        assertNull(d.finishedAt)
        assertEquals(listOf("Cloning repository", "Building image"), d.log?.map { it.line })
    }

    @Test fun runningDeployments() {
        val list = AppJson.decodeFromString<List<CDeployment>>(text("deployments.running")).map { it.toDeployment() }
        assertEquals("running" to "app1", list.single().let { it.status to it.serviceId })
    }
}
