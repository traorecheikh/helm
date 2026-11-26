package com.orvlabs.helm.ml

import com.orvlabs.helm.data.LogLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogsTest {
    private val clone = listOf(
        "Initializing deployment",
        "Cloning into '/etc/dokploy/applications/api-x7f2/code'...",
        "Cloned github.com/acme/api: ✅",
        "Build nixpacks: ✅",
        "#8 [stage-0  5/9] RUN npm ci",
    )

    private fun cause(vararg tail: String, error: String? = null) = failureCause(clone + tail, error)

    @Test fun outOfMemory() {
        val c = cause(
            "#12 41.2 <--- Last few GCs --->",
            "#12 41.3 FATAL ERROR: Reached heap limit Allocation failed - JavaScript heap out of memory",
            "#12 ERROR: process \"/bin/sh -c npm run build\" did not complete successfully: exit code: 137",
        )!!
        assertEquals(CauseKind.Oom, c.kind)
        assertEquals(Fix.Resources, c.kind.fix)
        assertTrue(c.confidence > 0.6)
    }

    @Test fun missingEnv() {
        val c = cause("❌ Invalid environment variables: { DATABASE_URL: [ 'Required' ] }", "Error: Invalid environment variables", "DATABASE_URL is not set")!!
        assertEquals(CauseKind.MissingEnv, c.kind)
        assertEquals(Fix.Env, c.kind.fix)
    }

    @Test fun portInUse() {
        assertEquals(CauseKind.PortInUse, cause("Error: listen EADDRINUSE: address already in use :::3000", "    at Server.setupListenHandle [as _listen2] (node:net:1817:16)")!!.kind)
    }

    @Test fun dependencyInstall() {
        val c = cause("#8 3.1 npm ERR! code ERESOLVE", "#8 3.1 npm ERR! ERESOLVE unable to resolve dependency tree", "#8 3.1 npm ERR! Could not resolve dependency:")!!
        assertEquals(CauseKind.Dependency, c.kind)
    }

    @Test fun compileError() {
        val c = cause(
            "#12 18.4 ./src/app/page.tsx:12:5",
            "#12 18.4 Type error: Type 'string' is not assignable to type 'number'.",
            "#12 18.5 Failed to compile.",
        )!!
        assertEquals(CauseKind.Build, c.kind)
        assertNull(c.kind.fix)
    }

    @Test fun diskFullWinsOverGenericBuildFailure() {
        val c = cause("#9 ERROR: failed to copy: write /var/lib/docker/overlay2/abc/diff/app/node_modules/x: no space left on device", "Build failed")!!
        assertEquals(CauseKind.DiskFull, c.kind)
    }

    @Test fun registryAuth() {
        val c = cause(error = "Error response from daemon: pull access denied for acme/api, repository does not exist or may require 'docker login': denied: requested access to the resource is denied")!!
        assertEquals(CauseKind.Registry, c.kind)
    }

    @Test fun healthCheck() {
        assertEquals(CauseKind.HealthTimeout, cause("Waiting for service api to become healthy", "container api-x7f2-1 is unhealthy", "deploy failed")!!.kind)
    }

    @Test fun cleanLogHasNoCause() {
        assertNull(failureCause(clone + listOf("#12 DONE 31.4s", "Deployment completed successfully")))
    }

    @Test fun mineCollapsesVaryingNumbersAndIds() {
        val lines = (1..120).map { LogLine("2026-11-25T10:00:${(it % 60).toString().padStart(2, '0')}Z", "GET /api/items/$it 200 ${it % 17}ms user=u$it") } +
            (1..30).map { LogLine("2026-11-25T10:01:00Z", "2026-11-25T10:01:00.123Z ERROR db timeout after ${it}ms on 10.0.1.$it") } +
            LogLine("2026-11-25T10:02:00Z", "Server listening on port 3000")
        val c = mine(lines)
        assertEquals(3, c.size)
        assertEquals(120, c[0].count)
        assertEquals("GET <*> <*> <*> <*>", c[0].template)
        assertEquals(30, c[1].count)
        assertTrue(c[1].template.startsWith("ERROR db timeout"))
    }
}
