package com.orvlabs.helm.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import com.orvlabs.helm.R
import com.orvlabs.helm.tr

private val Context.ds by preferencesDataStore("helm")

val ALERT_KEYS = listOf("build", "server", "backup", "ban", "activity")
private val DEFAULT_ALERTS = setOf("build", "server", "backup", "ban")

@Serializable
data class Server(val id: Int, val name: String, val url: String, val deviceId: String, val role: String) {
    val owner get() = role == "owner"
    val demo get() = url == DEMO_URL
    val host get() = if (demo) tr(R.string.demo_server) else url.removePrefix("https://").substringBefore('/')
}

data class Config(
    val url: String = "https://",
    val deviceId: String = "",
    val role: String = "member",
    val theme: String = "system",
    val biometric: Boolean = true,
    val appLock: Boolean = true,
    val alerts: Set<String> = DEFAULT_ALERTS,
    val onboarded: Boolean = false,
    val servers: List<Server> = emptyList(),
    val activeId: Int = 0,
    val quietOn: Boolean = true,
    val quietStart: Int = 23 * 60,
    val quietEnd: Int = 7 * 60,
    val morningAt: Int = 8 * 60,
) {
    val owner get() = role == "owner"
    val demo get() = url == DEMO_URL
    val host get() = if (demo) tr(R.string.demo_server) else url.removePrefix("https://").trimEnd('/').substringBefore('/')
}

object Store {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val kUrl = stringPreferencesKey("url")
    private val kDevice = stringPreferencesKey("device_id")
    private val kRole = stringPreferencesKey("role")
    private val kServers = stringPreferencesKey("servers")
    private val kActive = intPreferencesKey("active_server")
    private val kTheme = stringPreferencesKey("theme")
    private val kBio = booleanPreferencesKey("biometric")
    private val kLock = booleanPreferencesKey("app_lock")
    private val kAlerts = stringSetPreferencesKey("alerts")
    private val kOnboarded = booleanPreferencesKey("onboarded")
    private val kSummary = stringPreferencesKey("summary")
    private val kSaved = stringPreferencesKey("saved_actions")
    private val kQuietOn = booleanPreferencesKey("quiet_on")
    private val kQuietStart = intPreferencesKey("quiet_start")
    private val kQuietEnd = intPreferencesKey("quiet_end")
    private val kMorning = intPreferencesKey("morning_at")

    private val _config = MutableStateFlow<Config?>(null)
    val config: StateFlow<Config?> = _config

    fun init(context: Context) {
        app = context.applicationContext
        scope.launch {
            app.ds.data.map(::toConfig).collect { _config.value = it }
        }
    }

    private fun readServers(p: Preferences): List<Server> {
        p[kServers]?.let { json -> return runCatching { AppJson.decodeFromString<List<Server>>(json) }.getOrDefault(emptyList()) }
        val legacy = p[kDevice].orEmpty()
        val url = p[kUrl] ?: Config().url
        return if (legacy.isEmpty()) emptyList()
        else listOf(Server(0, url.removePrefix("https://").substringBefore('/'), url, legacy, p[kRole] ?: "member"))
    }

    private fun toConfig(p: Preferences): Config {
        val d = Config()
        val servers = readServers(p)
        val active = servers.firstOrNull { it.id == p[kActive] } ?: servers.firstOrNull()
        return Config(
            url = active?.url ?: p[kUrl] ?: d.url,
            deviceId = active?.deviceId.orEmpty(),
            role = active?.role ?: d.role,
            theme = p[kTheme] ?: d.theme,
            biometric = p[kBio] ?: d.biometric,
            appLock = p[kLock] ?: d.appLock,
            alerts = p[kAlerts] ?: d.alerts,
            onboarded = p[kOnboarded] ?: false,
            servers = servers,
            activeId = active?.id ?: 0,
            quietOn = p[kQuietOn] ?: d.quietOn,
            quietStart = p[kQuietStart] ?: d.quietStart,
            quietEnd = p[kQuietEnd] ?: d.quietEnd,
            morningAt = p[kMorning] ?: d.morningAt,
        )
    }

    private suspend fun edit(block: MutablePreferences.() -> Unit) {
        app.ds.edit(block)
    }

    fun nextServerId(): Int = (_config.value?.servers?.maxOfOrNull { it.id } ?: -1) + 1

    suspend fun savePaired(id: Int, url: String, deviceId: String, role: String) = edit {
        val clean = url.trim().trimEnd('/')
        val list = readServers(this).filter { it.id != id } + Server(id, clean.removePrefix("https://").substringBefore('/'), clean, deviceId, role)
        this[kServers] = AppJson.encodeToString(list.sortedBy { it.id })
        this[kActive] = id
        remove(kUrl)
        remove(kDevice)
        remove(kRole)
    }

    suspend fun setActive(id: Int) = edit { this[kActive] = id }

    fun removeServerAsync(id: Int) {
        scope.launch {
            Device.reset(id)
            val left = readServers(app.ds.data.first()).filter { it.id != id }
            if (left.isEmpty()) signOut() else edit { this[kServers] = AppJson.encodeToString(left) }
        }
    }

    fun wipe() {
        scope.launch { signOut() }
    }

    suspend fun setTheme(v: String) = edit { this[kTheme] = v }
    suspend fun setBiometric(v: Boolean) = edit { this[kBio] = v }
    suspend fun setAppLock(v: Boolean) = edit { this[kLock] = v }
    suspend fun setAlerts(v: Set<String>) = edit { this[kAlerts] = v }
    suspend fun setOnboarded(v: Boolean) = edit { this[kOnboarded] = v }
    suspend fun setQuiet(on: Boolean, start: Int, end: Int) = edit { this[kQuietOn] = on; this[kQuietStart] = start; this[kQuietEnd] = end }
    suspend fun setMorning(minutes: Int) = edit { this[kMorning] = minutes }

    suspend fun signOut() {
        val ids = readServers(app.ds.data.first()).map { it.id }.ifEmpty { listOf(0) }
        ids.forEach { Device.reset(it) }
        edit { clear() }
    }

    private fun cacheKey(p: Preferences) = stringPreferencesKey("cache_${p[kActive] ?: readServers(p).firstOrNull()?.id ?: 0}")

    suspend fun readCache(): String? = app.ds.data.first().let { it[cacheKey(it)] }
    suspend fun writeCache(json: String) = edit { this[cacheKey(this)] = json }

    suspend fun readSummary(): Summary? =
        app.ds.data.first()[kSummary]?.let { runCatching { AppJson.decodeFromString<Summary>(it) }.getOrNull() }

    suspend fun writeSummary(json: String) = edit { this[kSummary] = json }

    suspend fun readSaved(): List<SavedAction> =
        app.ds.data.first()[kSaved]?.let { runCatching { AppJson.decodeFromString<List<SavedAction>>(it) }.getOrNull() }.orEmpty()

    suspend fun writeSaved(list: List<SavedAction>) = edit { this[kSaved] = AppJson.encodeToString(list) }
}
