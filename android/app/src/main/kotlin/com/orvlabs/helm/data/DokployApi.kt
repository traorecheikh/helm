package com.orvlabs.helm.data

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class Svc(
    val name: String = "",
    val appName: String? = null,
    val description: String? = null,
    val applicationStatus: String? = null,
    val composeStatus: String? = null,
    val applicationId: String? = null,
    val composeId: String? = null,
    val postgresId: String? = null,
    val redisId: String? = null,
    val mysqlId: String? = null,
    val mariadbId: String? = null,
    val mongoId: String? = null,
) {
    val kind get() = when {
        applicationId != null -> "application"
        composeId != null -> "compose"
        postgresId != null -> "postgres"
        redisId != null -> "redis"
        mysqlId != null -> "mysql"
        mariadbId != null -> "mariadb"
        mongoId != null -> "mongo"
        else -> ""
    }
    val id get() = applicationId ?: composeId ?: postgresId ?: redisId ?: mysqlId ?: mariadbId ?: mongoId ?: ""
    val status get() = applicationStatus ?: composeStatus ?: "idle"
    val isDb get() = kind != "application" && kind != "compose"
}

@Serializable
data class DokEnv(
    val name: String? = null,
    val applications: List<Svc> = emptyList(),
    val compose: List<Svc> = emptyList(),
    val postgres: List<Svc> = emptyList(),
    val redis: List<Svc> = emptyList(),
    val mysql: List<Svc> = emptyList(),
    val mariadb: List<Svc> = emptyList(),
    val mongo: List<Svc> = emptyList(),
) {
    val all get() = applications + compose + postgres + redis + mysql + mariadb + mongo
}

@Serializable
data class Project(
    val projectId: String = "",
    val name: String = "",
    val description: String? = null,
    val environments: List<DokEnv> = emptyList(),
) {
    val services get() = environments.flatMap { it.all }
}

@Serializable
data class Container(
    val containerId: String = "",
    val name: String = "",
    val image: String = "",
    val ports: String = "",
    val state: String = "",
    val status: String = "",
    val appName: String? = null,
)

@Serializable data class ContainerCount(val containerCount: Int = 0, val serviceCount: Int = 0)
@Serializable data class Resources(val memTotalBytes: Long = 0, val memUsedBytes: Long = 0, val cpuCount: Int = 0)
@Serializable data class DiskInfo(val totalBytes: Long = 0, val usedBytes: Long = 0)

@Serializable
data class Health(
    val checkedAt: String? = null,
    val containers: ContainerCount = ContainerCount(),
    val resources: Resources = Resources(),
    val disk: DiskInfo = DiskInfo(),
)

@Serializable
data class Deployment(
    val deploymentId: String = "",
    val title: String? = null,
    val description: String? = null,
    val status: String? = null,
    val logPath: String? = null,
    val applicationId: String? = null,
    val composeId: String? = null,
    val createdAt: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val errorMessage: String? = null,
    val log: List<LogLine>? = null,
) {
    val serviceId get() = applicationId ?: composeId ?: ""
    val kind get() = if (applicationId != null) "application" else "compose"
}

@Serializable
data class BackupCfg(
    val backupId: String = "",
    val schedule: String? = null,
    val database: String? = null,
    val prefix: String? = null,
    val destinationId: String? = null,
)

@Serializable
data class Detail(
    val name: String = "",
    val appName: String? = null,
    val description: String? = null,
    val env: String? = null,
    val buildArgs: String? = null,
    val buildSecrets: String? = null,
    val createEnvFile: Boolean? = null,
    val dockerImage: String? = null,
    val repository: String? = null,
    val owner: String? = null,
    val branch: String? = null,
    val customGitUrl: String? = null,
    val sourceType: String? = null,
    val applicationStatus: String? = null,
    val composeStatus: String? = null,
    val databaseName: String? = null,
    val databaseUser: String? = null,
    val externalPort: Int? = null,
    val rollbackActive: Boolean? = null,
    val replicas: Int? = null,
    val memoryLimit: String? = null,
    val cpuLimit: String? = null,
    val backups: List<BackupCfg> = emptyList(),
)

@Serializable
data class Domain(
    val domainId: String = "",
    val host: String = "",
    val https: Boolean? = null,
    val port: Int? = null,
    val path: String? = null,
    val certificateType: String? = null,
)

@Serializable
data class Destination(
    val destinationId: String = "",
    val name: String = "",
    val provider: String? = null,
    val bucket: String? = null,
)

@Serializable
data class BackupFile(
    @SerialName("Name") val name: String = "",
    @SerialName("Path") val path: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("ModTime") val modTime: String? = null,
    @SerialName("IsDir") val isDir: Boolean = false,
)

fun parseTime(s: String?): Long? = s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

object Dokploy : Platform {
    private const val P = "dokploy/"

    fun idParam(kind: String) = "${kind}Id"

    suspend fun version(): String {
        val text = Net.call("GET", P + "settings.getDokployVersion", emptyList(), null)
        return runCatching { (AppJson.parseToJsonElement(text) as JsonPrimitive).content }.getOrDefault(text).trim('"')
    }

    override suspend fun projects(): List<Project> = apiGet(P + "project.all")
    suspend fun health(): Health = apiGet(P + "docker.getServerHealth")
    suspend fun containers(): List<Container> = apiGet(P + "docker.getContainers")
    override suspend fun deployments(): List<Deployment> = apiGet(P + "deployment.allCentralized")
    suspend fun destinations(): List<Destination> = apiGet(P + "destination.all")

    override suspend fun deployments(kind: String, id: String): List<Deployment> =
        if (kind == "compose") apiGet(P + "deployment.allByCompose", "composeId" to id)
        else apiGet(P + "deployment.all", "applicationId" to id)

    override suspend fun deployment(id: String) = deployments().firstOrNull { it.deploymentId == id }

    override suspend fun one(kind: String, id: String): Detail = apiGet(P + "$kind.one", idParam(kind) to id)

    suspend fun domains(kind: String, id: String): List<Domain> =
        if (kind == "compose") apiGet(P + "domain.byComposeId", "composeId" to id)
        else apiGet(P + "domain.byApplicationId", "applicationId" to id)

    suspend fun backupFiles(destinationId: String): List<BackupFile> =
        apiGet(P + "backup.listBackupFiles", "destinationId" to destinationId, "search" to "")

    override suspend fun act(kind: String, id: String, action: String) {
        apiPostIgnore(P + "$kind.$action", buildJsonObject { put(idParam(kind), id) })
        if (action == "deploy" || action == "redeploy") Live.deploys.tryEmit(DeployStart(Store.config.value?.activeId ?: 0, kind, id, System.currentTimeMillis()))
    }

    override suspend fun restart(kind: String, id: String, containers: List<Container>) = containers.forEach { restartContainer(it.containerId) }

    override suspend fun cancelDeploy(kind: String, id: String) = apiPostIgnore(P + "$kind.cancelDeployment", buildJsonObject { put(idParam(kind), id) })

    suspend fun restartContainer(containerId: String) =
        apiPostIgnore(P + "docker.restartContainer", buildJsonObject { put("containerId", containerId) })

    suspend fun backupNow(backupId: String) =
        apiPostIgnore(P + "backup.manualBackupPostgres", buildJsonObject { put("backupId", backupId) })

    private fun destinationBody(name: String, accessKey: String, secretKey: String, bucket: String, region: String, endpoint: String) =
        buildJsonObject {
            put("name", name)
            put("provider", JsonNull)
            put("accessKey", accessKey)
            put("secretAccessKey", secretKey)
            put("bucket", bucket)
            put("region", region)
            put("endpoint", endpoint)
            put("additionalFlags", JsonArray(emptyList()))
        }

    suspend fun testDestination(name: String, accessKey: String, secretKey: String, bucket: String, region: String, endpoint: String) =
        apiPostIgnore(P + "destination.testConnection", destinationBody(name, accessKey, secretKey, bucket, region, endpoint))

    suspend fun createDestination(name: String, accessKey: String, secretKey: String, bucket: String, region: String, endpoint: String) =
        apiPostIgnore(P + "destination.create", destinationBody(name, accessKey, secretKey, bucket, region, endpoint))

    suspend fun createBackup(postgresId: String, appName: String, destinationId: String, database: String) =
        apiPostIgnore(
            P + "backup.create",
            buildJsonObject {
                put("schedule", "0 3 * * *")
                put("prefix", appName)
                put("destinationId", destinationId)
                put("database", database)
                put("databaseType", "postgres")
                put("postgresId", postgresId)
                put("enabled", true)
                put("keepLatestCount", 7)
            },
        )

    suspend fun removeBackup(backupId: String) = apiPostIgnore(P + "backup.remove", buildJsonObject { put("backupId", backupId) })

    suspend fun setBranch(kind: String, id: String, branch: String) =
        apiPostIgnore(P + "$kind.update", buildJsonObject { put(idParam(kind), id); put("branch", branch) })

    suspend fun setResources(id: String, replicas: Int, memoryLimit: String?, cpuLimit: String?) =
        apiPostIgnore(
            P + "application.update",
            buildJsonObject {
                put("applicationId", id)
                put("replicas", replicas)
                memoryLimit?.let { put("memoryLimit", it) }
                cpuLimit?.let { put("cpuLimit", it) }
            },
        )

    suspend fun cleanDocker() = apiPostIgnore(P + "settings.cleanAll")
    suspend fun reloadTraefik() = apiPostIgnore(P + "settings.reloadTraefik")

    suspend fun saveEnv(kind: String, id: String, env: String, d: Detail) =
        apiPostIgnore(
            P + "$kind.saveEnvironment",
            buildJsonObject {
                put(idParam(kind), id)
                put("env", env)
                if (kind == "application") {
                    put("buildArgs", d.buildArgs ?: "")
                    put("buildSecrets", d.buildSecrets ?: "")
                    put("createEnvFile", d.createEnvFile ?: true)
                }
            },
        )
}
