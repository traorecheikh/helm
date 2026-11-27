package com.orvlabs.helm.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class Caps(val platform: String = "dokploy", val dokploy: Boolean = true, val coolify: Boolean = false, val docker: Boolean = true, val stalwart: Boolean = false) {
    fun pick(chosen: String?) = chosen?.takeIf { it == "dokploy" && dokploy || it == "coolify" && coolify } ?: platform
}

interface Platform {
    suspend fun projects(): List<Project>
    suspend fun one(kind: String, id: String): Detail
    suspend fun deployments(): List<Deployment>
    suspend fun deployments(kind: String, id: String): List<Deployment>
    suspend fun deployment(id: String): Deployment?
    suspend fun act(kind: String, id: String, action: String)
    suspend fun restart(kind: String, id: String, containers: List<Container>)
    suspend fun cancelDeploy(kind: String, id: String)
}

val Plat: Platform get() = if (Repo.state.value.platform == "coolify") Coolify else Dokploy
val dokployActive get() = Repo.state.value.platform == "dokploy"
val mailActive get() = Repo.state.value.snap?.caps?.stalwart == true

@Serializable internal data class CProject(val uuid: String = "", val name: String = "", val description: String? = null)

@Serializable internal data class CEnv(val id: String = "", val name: String? = null)

@Serializable
internal data class CResource(
    val uuid: String = "",
    val name: String = "",
    val description: String? = null,
    val type: String = "",
    val status: String? = null,
    @SerialName("environment_id") val environmentId: String? = null,
)

@Serializable
internal data class CApp(
    val name: String = "",
    val description: String? = null,
    val status: String? = null,
    @SerialName("git_repository") val repository: String? = null,
    @SerialName("git_branch") val branch: String? = null,
    @SerialName("docker_registry_image_name") val image: String? = null,
)

@Serializable
internal data class CDeployment(
    @SerialName("deployment_uuid") val uuid: String = "",
    val status: String? = null,
    val commit: String? = null,
    @SerialName("commit_message") val message: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val logs: String? = null,
    @SerialName("application_name") val appName: String? = null,
    @SerialName("deployment_url") val url: String? = null,
)

/** Maps Coolify "state:health" to the Dokploy words the UI already reads: done ok, running in progress, error failing, idle stopped. */
internal fun coolifyStatus(raw: String?): String {
    val s = raw.orEmpty().lowercase()
    return when {
        s.startsWith("restarting") || s.startsWith("starting") -> "running"
        s.startsWith("running") && !s.endsWith("unhealthy") -> "done"
        s.endsWith("unhealthy") || s.startsWith("degraded") -> "error"
        else -> "idle"
    }
}

internal fun coolifySvc(r: CResource): Svc {
    val s = Svc(r.name, appName = r.uuid, description = r.description, applicationStatus = coolifyStatus(r.status))
    return when (r.type.removePrefix("standalone-")) {
        "application" -> s.copy(applicationId = r.uuid)
        "service" -> s.copy(composeId = r.uuid)
        "mysql" -> s.copy(mysqlId = r.uuid)
        "mariadb" -> s.copy(mariadbId = r.uuid)
        "mongodb" -> s.copy(mongoId = r.uuid)
        "redis", "keydb", "dragonfly" -> s.copy(redisId = r.uuid)
        else -> s.copy(postgresId = r.uuid)
    }
}

internal fun coolifyProjects(projects: List<CProject>, envs: Map<String, List<CEnv>>, resources: List<CResource>): List<Project> {
    val byEnv = resources.groupBy { it.environmentId }
    return projects.map { p ->
        Project(p.uuid, p.name, p.description, envs[p.uuid].orEmpty().map { e -> DokEnv(e.name, byEnv[e.id].orEmpty().map(::coolifySvc)) })
    }
}

/** Coolify stores deploy logs as a JSON-encoded list of {output, hidden}; hidden entries are internal commands and may hold secrets. */
internal fun coolifyLog(raw: String?): List<LogLine>? {
    if (raw.isNullOrBlank()) return null
    val entries = runCatching { AppJson.parseToJsonElement(raw) as? JsonArray }.getOrNull()
        ?: return raw.lines().filter { it.isNotBlank() }.map { LogLine(line = it) }
    return entries.flatMap { e ->
        val o = e as? JsonObject
        if (o?.get("hidden")?.jsonPrimitive?.booleanOrNull == true) return@flatMap emptyList()
        val text = o?.get("output")?.jsonPrimitive?.contentOrNull ?: (e as? JsonPrimitive)?.contentOrNull.orEmpty()
        val at = o?.get("timestamp")?.jsonPrimitive?.contentOrNull.orEmpty()
        text.lines().filter { it.isNotBlank() }.map { LogLine(at, it) }
    }
}

internal fun CDeployment.toDeployment(appUuid: String? = null): Deployment {
    val ended = status == "finished" || status == "failed" || status?.startsWith("cancelled") == true
    return Deployment(
        deploymentId = uuid,
        title = message ?: appName,
        description = commit,
        status = when (status) { "queued", "in_progress" -> "running"; "finished" -> "done"; "failed" -> "error"; else -> status },
        applicationId = appUuid ?: url?.substringAfter("/application/", "")?.substringBefore('/')?.ifEmpty { null },
        createdAt = createdAt,
        startedAt = createdAt,
        finishedAt = updatedAt.takeIf { ended },
        log = coolifyLog(logs),
    )
}

object Coolify : Platform {
    private const val P = "coolify/"

    private fun segment(kind: String) = when (kind) {
        "application" -> "applications"
        "compose" -> "services"
        else -> "databases"
    }

    override suspend fun projects(): List<Project> = coroutineScope {
        val resources = async { apiGet<List<CResource>>(P + "resources") }
        val projects = apiGet<List<CProject>>(P + "projects")
        val envs = projects.map { p -> async { p.uuid to apiGet<List<CEnv>>(P + "projects/${p.uuid}/environments") } }.awaitAll().toMap()
        coolifyProjects(projects, envs, resources.await())
    }

    override suspend fun one(kind: String, id: String): Detail {
        val a = apiGet<CApp>(P + "${segment(kind)}/$id")
        return Detail(
            name = a.name, appName = id, description = a.description, applicationStatus = coolifyStatus(a.status),
            repository = a.repository, branch = a.branch, dockerImage = a.image,
        )
    }

    override suspend fun deployments(): List<Deployment> = apiGet<List<CDeployment>>(P + "deployments").map { it.toDeployment().copy(log = null) }

    override suspend fun deployments(kind: String, id: String): List<Deployment> {
        if (kind != "application") return emptyList()
        val body = apiGet<JsonElement>(P + "deployments/applications/$id", "skip" to "0", "take" to "20")
        val list = body as? JsonArray ?: (body as? JsonObject)?.get("deployments") as? JsonArray ?: return emptyList()
        return list.mapNotNull { runCatching { AppJson.decodeFromJsonElement<CDeployment>(it) }.getOrNull()?.toDeployment(id) }
    }

    override suspend fun deployment(id: String): Deployment = apiGet<CDeployment>(P + "deployments/$id").toDeployment()

    override suspend fun act(kind: String, id: String, action: String) {
        if (action != "deploy" && action != "redeploy") {
            Net.call("POST", P + "${segment(kind)}/$id/$action", emptyList(), null)
            return
        }
        Net.call("POST", P + "deploy?uuid=$id", emptyList(), null)
        if (kind == "application") Live.deploys.tryEmit(DeployStart(Store.config.value?.activeId ?: 0, kind, id, System.currentTimeMillis()))
    }

    override suspend fun restart(kind: String, id: String, containers: List<Container>) = act(kind, id, "restart")

    override suspend fun cancelDeploy(kind: String, id: String) {
        val running = deployments(kind, id).firstOrNull { it.status == "running" } ?: return
        Net.call("POST", P + "deployments/${running.deploymentId}/cancel", emptyList(), null)
    }
}
