package com.orvlabs.helm.ml

import com.orvlabs.helm.Str
import com.orvlabs.helm.data.AppJson
import com.orvlabs.helm.data.Event
import com.orvlabs.helm.data.isFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File

private val CRITICAL = setOf("build_error", "service_down", "container_died", "oom", "panic", "panic_on", "secret_leak")
private const val MAX_KEYS = 200
private const val MAX_SEEN = 400

fun critical(e: Event) = e.isFailure() || e.kind in CRITICAL

@Serializable data class Tally(val opens: Int = 0, val shows: Int = 0) {
    val rate get() = (opens + 1.0) / (shows + 2.0)
}

@Serializable data class TriageState(val tallies: Map<String, Tally> = emptyMap(), val seen: List<String> = emptyList())

class Triage(state: TriageState = TriageState()) {
    private val tallies = LinkedHashMap(state.tallies)
    private val seen = LinkedHashSet(state.seen)

    private fun kindKey(e: Event) = "k:${e.type}/${e.kind.orEmpty()}"
    private fun keys(e: Event) = listOfNotNull(kindKey(e), e.container?.takeIf { it.isNotBlank() }?.let { "s:${it.substringBefore('.')}" })

    fun score(e: Event): Double = keys(e).mapNotNull { tallies[it]?.rate }.ifEmpty { listOf(0.5) }.average()

    fun quiet(e: Event, muted: Boolean): Boolean {
        if (critical(e)) return false
        val shows = tallies[kindKey(e)]?.shows ?: 0
        return muted || (shows >= 10 && score(e) < 0.12)
    }

    fun order(list: List<Event>): List<Event> =
        list.sortedWith(compareByDescending<Event> { critical(it) }.thenByDescending { score(it) }.thenByDescending { it.at })

    @Synchronized fun shown(list: List<Event>): Boolean {
        val fresh = list.filter { it.id.isNotEmpty() && seen.add(it.id) }
        fresh.forEach { e -> keys(e).forEach { k -> tallies[k] = (tallies[k] ?: Tally()).let { it.copy(shows = it.shows + 1) } } }
        return fresh.isNotEmpty()
    }

    @Synchronized fun opened(list: List<Event>) {
        list.flatMap(::keys).distinct().forEach { k -> tallies[k] = (tallies[k] ?: Tally()).let { it.copy(opens = it.opens + 1) } }
    }

    @Synchronized fun state(): TriageState {
        while (tallies.size > MAX_KEYS) tallies.remove(tallies.minByOrNull { it.value.shows }!!.key)
        return TriageState(tallies.toMap(), seen.toList().takeLast(MAX_SEEN))
    }
}

object TriageStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file get() = File(Str.app.filesDir, "ml/triage.json")

    val model: Triage by lazy {
        Triage(runCatching { AppJson.decodeFromString<TriageState>(file.readText()) }.getOrDefault(TriageState()))
    }

    fun save() {
        scope.launch {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.writeText(AppJson.encodeToString(TriageState.serializer(), model.state()))
                tmp.renameTo(file)
            }
        }
    }
}
