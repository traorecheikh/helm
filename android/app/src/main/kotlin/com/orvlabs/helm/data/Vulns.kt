package com.orvlabs.helm.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable data class Unattended(
    val installed: Boolean = false,
    val enabled: Boolean = false,
    val lastRun: Long? = null,
    val upgraded: List<String> = emptyList(),
    val keptBack: List<String> = emptyList(),
    val ok: Boolean? = null,
    val error: String? = null,
)

@Serializable data class SevCounts(val critical: Int = 0, val high: Int = 0)
@Serializable data class VulnFinding(
    val id: String = "",
    @SerialName("package") val pkg: String = "",
    val installed: String = "",
    val fixed: String? = null,
    val severity: String = "high",
    val fixable: Boolean = false,
)
@Serializable data class VulnService(val platform: String = "", val kind: String = "", val id: String = "", val name: String = "") {
    val key get() = "$platform:$kind:$id"
}
@Serializable data class VulnTarget(
    val kind: String = "image",
    val target: String = "",
    val containers: List<String> = emptyList(),
    val service: VulnService? = null,
    val database: Boolean = false,
    val stateful: Boolean = false,
    val counts: SevCounts = SevCounts(),
    val fixable: SevCounts = SevCounts(),
    val findings: List<VulnFinding> = emptyList(),
    val error: String? = null,
)
@Serializable data class VulnDay(val day: String = "", val critical: Int = 0, val high: Int = 0)
@Serializable data class AutofixRun(val at: Long = 0, val result: String = "")
@Serializable data class Autofix(val key: String = "", val enabled: Boolean = false, val allowStateful: Boolean = false, val last: AutofixRun? = null)
@Serializable data class Vulns(
    val installed: Boolean = false,
    val running: Boolean = false,
    val autofixOff: Boolean = false,
    val at: Long? = null,
    val dbError: String? = null,
    val counts: SevCounts = SevCounts(),
    val fixable: SevCounts = SevCounts(),
    val targets: List<VulnTarget> = emptyList(),
    val history: List<VulnDay> = emptyList(),
    val autofix: List<Autofix> = emptyList(),
)

object VulnsApi {
    suspend fun get(): Vulns = apiGet("vulns")
    suspend fun scan() = apiPostIgnore("vulns/run")
    suspend fun setAutofix(s: VulnService, on: Boolean, stateful: Boolean): JsonElement = apiPut(
        "vulns/autofix",
        buildJsonObject { put("platform", s.platform); put("kind", s.kind); put("id", s.id); put("enabled", on); put("allowStateful", stateful) },
    )
    suspend fun redeploy(s: VulnService) {
        if (s.platform == "dokploy") Dokploy.act(s.kind, s.id, "redeploy") else Net.call("GET", "coolify/deploy", listOf("uuid" to s.id), null)
    }
}
