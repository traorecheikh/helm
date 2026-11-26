package com.orvlabs.helm.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GH = "ghp" + "_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8"
private const val AWS = "AK" + "IAIOSFODNN7EXAMPLE"
private const val PEM_BEGIN = "-----BEGIN " + "PRIVATE KEY-----"
private const val PEM_END = "-----END " + "PRIVATE KEY-----"

private const val STRIPE = "sk" + "_live_51HxQvL2eZvKYlo2C"
private const val GITLAB = "glpat" + "-xY12zW34vU56tS78rQ90"

class RedactTest {
    private val secrets = listOf(
        "hunter2-prod", "s3cr3tP@ss", GH, AWS,
        "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJvd25lciJ9.c2lnbmF0dXJlLXZhbHVl", "${STRIPE}", "dbpass99",
        "${GITLAB}", "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7", "owner@acme.io",
    )

    private val log = """
        DATABASE_URL=postgres://app:dbpass99@db:5432/app
        export STRIPE_KEY=${STRIPE}
        #7 [build 3/6] RUN --mount=type=secret docker build --build-arg NPM_TOKEN=${GITLAB} .
        Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJvd25lciJ9.c2lnbmF0dXJlLXZhbHVl
        {"user":"owner","password":"hunter2-prod","retries":3}
        redis connection to redis://:s3cr3tP@ss@cache:6379 refused
        cloning https://${GH}@github.com/acme/api.git
        aws: using key ${AWS}
        ${PEM_BEGIN}
        MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7
        ${PEM_END}
        notify owner@acme.io on failure
        npm ERR! code ERESOLVE
        Error: listen EADDRINUSE: address already in use :::3000
    """.trimIndent()

    @Test fun stripsEverySecretFromARealisticBuildLog() {
        val out = redact(log)
        secrets.forEach { assertFalse("leaked $it in:\n$out", out.contains(it)) }
    }

    @Test fun keepsTheDiagnosticText() {
        val out = redact(log)
        assertTrue(out.contains("DATABASE_URL="))
        assertTrue(out.contains("npm ERR! code ERESOLVE"))
        assertTrue(out.contains("Error: listen EADDRINUSE: address already in use :::3000"))
        assertTrue(out.contains("github.com/acme/api.git"))
        assertEquals("Build failed at step 4/9", redact("Build failed at step 4/9"))
    }

    @Test fun promptIsRedactedAndBounded() {
        val clusters = List(200) { Cluster("line $it password=hunter2-prod token: ${GH} " + "x".repeat(300), 200 - it, "", "") }
        val p = prompt(Cause(CauseKind.Oom, 0.8, "AWS_SECRET_ACCESS_KEY=abc123 killed"), clusters, false)
        assertFalse(p.contains("hunter2-prod"))
        assertFalse(p.contains("ghp_"))
        assertFalse(p.contains("abc123"))
        assertTrue(p.length <= 7_000)
        assertTrue(p.contains("<logs>") && p.contains("</logs>"))
    }
}
