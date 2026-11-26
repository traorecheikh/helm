package com.orvlabs.helm.data

import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoAssetsTest {
    private val models: Map<String, KSerializer<*>> = mapOf(
        "audit" to serializer<List<AuditEntry>>(),
        "capabilities" to serializer<Caps>(),
        "certs" to serializer<List<CertRec>>(),
        "containers" to serializer<List<AgentContainer>>(),
        "containers_history" to serializer<List<HistPoint>>(),
        "db" to serializer<DbInfo>(),
        "db_query" to serializer<QueryResult>(),
        "devices" to serializer<Devices>(),
        "disk_clean" to serializer<DiskCleaned>(),
        "disk_preview" to serializer<DiskPreview>(),
        "dns" to serializer<List<DnsRec>>(),
        "dokploy_application.one" to serializer<Detail>(),
        "dokploy_backup.listBackupFiles" to serializer<List<BackupFile>>(),
        "dokploy_compose.one" to serializer<Detail>(),
        "dokploy_deployment.allCentralized" to serializer<List<Deployment>>(),
        "dokploy_destination.all" to serializer<List<Destination>>(),
        "dokploy_docker.getContainers" to serializer<List<Container>>(),
        "dokploy_docker.getServerHealth" to serializer<Health>(),
        "dokploy_domain" to serializer<List<Domain>>(),
        "dokploy_mysql.one" to serializer<Detail>(),
        "dokploy_postgres.one" to serializer<Detail>(),
        "dokploy_project.all" to serializer<List<Project>>(),
        "dokploy_redis.one" to serializer<Detail>(),
        "dokploy_settings.getDokployVersion" to serializer<String>(),
        "env_diff" to serializer<List<EnvDiffRow>>(),
        "env_history" to serializer<List<EnvHist>>(),
        "events" to serializer<List<Event>>(),
        "events_history" to serializer<List<Event>>(),
        "geo" to serializer<Geo>(),
        "host" to serializer<HostInfo>(),
        "incidents" to serializer<List<IncidentRec>>(),
        "logins" to serializer<List<Login>>(),
        "logs_container" to serializer<List<LogLine>>(),
        "logs_deployment" to serializer<List<LogLine>>(),
        "logwatch" to serializer<Logwatch>(),
        "mail" to serializer<MailInfo>(),
        "mail_activity" to serializer<MailActivity>(),
        "notes" to serializer<List<NoteRec>>(),
        "schedules" to serializer<List<ScheduleJob>>(),
        "secrets_findings" to serializer<List<SecretFinding>>(),
        "security" to serializer<Security>(),
        "security_live" to serializer<List<Attacker>>(),
        "security_log" to serializer<List<SecEvent>>(),
        "shield" to serializer<Shield>(),
        "shield_ip" to serializer<IpDetail>(),
        "shield_panic" to serializer<Panic>(),
        "stats" to serializer<Stats>(),
        "updates" to serializer<Updates>(),
        "uptime" to serializer<Uptime>(),
        "vulns" to serializer<Vulns>(),
        "watchdog" to serializer<List<WatchFile>>(),
        "watchdog_diff" to serializer<List<DiffLine>>(),
        "web" to serializer<List<WebHost>>(),
    )

    private fun dir() = File(javaClass.getResource("/demo")!!.toURI())
    private fun text(name: String) = Demo.fill(File(dir(), "$name.json").readText())

    @Test fun everyAssetDecodesIntoItsModel() {
        assertEquals(models.keys, dir().list()!!.map { it.removeSuffix(".json") }.toSet())
        models.forEach { (name, model) ->
            val raw = text(name)
            assertFalse("$name has an unresolved timestamp", Regex("""\{\{(ts|iso|day)""").containsMatchIn(raw))
            AppJson.decodeFromString(model, raw)
        }
    }

    @Test fun deploymentsPointAtExistingServices() {
        val ids = AppJson.decodeFromString<List<Project>>(text("dokploy_project.all")).flatMap { it.services }.map { it.id }.toSet()
        AppJson.decodeFromString<List<Deployment>>(text("dokploy_deployment.allCentralized")).forEach { assertTrue(it.serviceId in ids) }
        assertEquals(setOf("application", "compose", "postgres", "redis", "mysql"), AppJson.decodeFromString<List<Project>>(text("dokploy_project.all")).flatMap { it.services }.map { it.kind }.toSet())
    }
}
