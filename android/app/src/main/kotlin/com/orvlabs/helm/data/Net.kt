package com.orvlabs.helm.data

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okio.Buffer
import com.orvlabs.helm.BuildConfig
import com.orvlabs.helm.R
import com.orvlabs.helm.tr
import com.orvlabs.helm.appLocale

val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

class ApiError(message: String, val kind: Kind, val code: Int = 0) : Exception(message) {
    enum class Kind { Auth, Revoked, Offline, Unreachable, Server, Queued, Other }

    val transient get() = kind == Kind.Offline || kind == Kind.Unreachable || code in 502..504
}

class StaleProbe : AbstractCoroutineContextElement(Key) {
    @Volatile var stale = false
    companion object Key : CoroutineContext.Key<StaleProbe>
}

class SseEvent(val type: String, val data: String)

object Net {
    private lateinit var app: Context
    private val JSON_TYPE = "application/json".toMediaType()
    private val random = SecureRandom()
    var onRevoked: (Int?) -> Unit = {}

    private fun serverFor(url: String) = Store.config.value?.servers?.firstOrNull { url.startsWith(it.url.trimEnd('/') + "/") }

    private val signer = Interceptor { chain ->
        val req = chain.request().newBuilder().header("Accept-Language", appLocale.toLanguageTag()).build()
        val server = serverFor(req.url.toString())
        val prefix = server?.url?.toHttpUrlOrNull()?.encodedPath?.trimEnd('/').orEmpty()
        val rel = req.url.encodedPath.removePrefix(prefix)
        if (server == null || server.deviceId.isEmpty() || rel == "/health" || rel.startsWith("/pair/")) return@Interceptor chain.proceed(req)
        val query = req.url.encodedQuery?.let { "?$it" }.orEmpty()
        val bodyHash = Buffer().also { req.body?.writeTo(it) }.sha256().hex()
        val time = (System.currentTimeMillis() / 1000).toString()
        val nonce = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val message = "${req.method}\n$rel$query\n$time\n$nonce\n$bodyHash"
        chain.proceed(
            req.newBuilder()
                .header("X-Helm-Device", server.deviceId)
                .header("X-Helm-Time", time)
                .header("X-Helm-Nonce", nonce)
                .header("X-Helm-Signature", Device.sign(server.id, message.toByteArray()))
                .build(),
        )
    }

    @Volatile private var mutatedAt = 0L
    private val uncached = Regex("""/(pair|health|logs|env|secrets|db)(/|$)|\.one$|/coolify/(deployments|(applications|services|databases)/[^/]+$)""")

    private fun cacheable(url: okhttp3.HttpUrl) = !uncached.containsMatchIn(url.encodedPath)

    // Keys are method+URL only: signature, nonce and time headers never reach the cache, and a hit sends nothing to replay.
    private val freshness = Interceptor { chain ->
        val r = chain.proceed(chain.request())
        if (r.isSuccessful && r.request.method == "GET" && cacheable(r.request.url)) {
            r.newBuilder().header("Cache-Control", "max-age=5").removeHeader("Vary").removeHeader("Pragma").removeHeader("Expires").build()
        } else {
            r
        }
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cache(Cache(File(app.cacheDir, "http"), 20L * 1024 * 1024))
            .addInterceptor(signer)
            .addNetworkInterceptor(freshness)
            .retryOnConnectionFailure(true)
            .connectTimeout(6, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    private val actionClient by lazy { client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build() }
    private val slowClient by lazy { client.newBuilder().callTimeout(15, TimeUnit.MINUTES).readTimeout(15, TimeUnit.MINUTES).build() }
    private val streaming by lazy { client.newBuilder().cache(null).callTimeout(0, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build() }

    fun init(context: Context) {
        app = context.applicationContext
        Outbox.init(app)
    }

    fun online(): Boolean = app.getSystemService(ConnectivityManager::class.java).activeNetwork != null

    private fun base() = checkNotNull(Store.config.value).url.trimEnd('/')

    private fun request(base: String, path: String, query: List<Pair<String, String>>): Request.Builder {
        val url = (base.trimEnd('/') + "/" + path).toHttpUrl().newBuilder()
            .apply { query.forEach { addQueryParameter(it.first, it.second) } }.build()
        return Request.Builder().url(url)
    }

    private fun failure() = if (!online()) {
        ApiError(tr(R.string.no_internet_on_this_phone_check), ApiError.Kind.Offline)
    } else {
        ApiError(tr(R.string.the_server_did_not_answer_check), ApiError.Kind.Unreachable)
    }

    private fun httpError(r: Response, body: String): ApiError {
        val code = r.code
        val detail = runCatching {
            val o = AppJson.parseToJsonElement(body).jsonObject
            (o["error"] ?: o["message"])?.jsonPrimitive?.content
        }.getOrNull()
        return when {
            code == 401 && detail == "revoked" -> {
                client.cache?.evictAll()
                onRevoked(serverFor(r.request.url.toString())?.id)
                ApiError(tr(R.string.this_phone_was_removed_from_the), ApiError.Kind.Revoked)
            }
            code == 401 || code == 403 -> ApiError(
                detail?.let { tr(R.string.the_server_refused_this_phone_pair, it) }
                    ?: tr(R.string.the_server_refused_this_phone_pair_2),
                ApiError.Kind.Auth,
            )
            detail != null && code in 400..499 -> ApiError(detail, ApiError.Kind.Other)
            code >= 500 -> ApiError(tr(R.string.the_server_had_a_problem_code, code), ApiError.Kind.Server, code)
            else -> ApiError(tr(R.string.err_other, code), ApiError.Kind.Other)
        }
    }

    suspend fun call(method: String, path: String, query: List<Pair<String, String>>, body: String?, base: String = base(), slow: Boolean = false, queue: Boolean = true): String {
        if (Demo.owns(base)) return Demo.call(method, path, query, body)
        val job = currentCoroutineContext()[Job]
        val probe = currentCoroutineContext()[StaleProbe]
        val t0 = SystemClock.elapsedRealtime()
        try {
            return send(method, path, query, body, base, slow)
        } catch (e: ApiError) {
            if (job?.isActive == false) throw e
            if (method == "GET" && (e.transient || e.kind == ApiError.Kind.Server)) {
                lastGood(path, query, base)?.let {
                    probe?.stale = true
                    trace(method, path, t0, "stale after ${e.kind}")
                    return it
                }
            }
            if (queue && e.transient && Outbox.eligible(method, path)) {
                Outbox.enqueue(base, path, body)
                trace(method, path, t0, "queued after ${e.kind}")
                throw ApiError(tr(R.string.net_queued), ApiError.Kind.Queued)
            }
            trace(method, path, t0, "failed ${e.kind}")
            throw e
        }
    }

    private suspend fun send(method: String, path: String, query: List<Pair<String, String>>, body: String?, base: String, slow: Boolean): String =
        runInterruptible(Dispatchers.IO) {
            val t0 = SystemClock.elapsedRealtime()
            val req = request(base, path, query).apply {
                if (method == "POST") post((body ?: "{}").toRequestBody(JSON_TYPE))
                else if (method == "PUT") put((body ?: "{}").toRequestBody(JSON_TYPE))
                else {
                    get()
                    if (t0 - mutatedAt < 5_000) cacheControl(CacheControl.Builder().maxAge(0, TimeUnit.SECONDS).build())
                }
            }.build()
            try {
                (if (slow) slowClient else if (method == "GET") client else actionClient).newCall(req).execute().use { r ->
                    val text = r.body.string()
                    if (!r.isSuccessful) throw httpError(r, text)
                    if (method != "GET") mutatedAt = SystemClock.elapsedRealtime()
                    trace(method, path, t0, if (method != "GET") "write" else if (r.networkResponse == null) "hit" else "miss")
                    text
                }
            } catch (e: IOException) {
                throw failure()
            }
        }

    private suspend fun lastGood(path: String, query: List<Pair<String, String>>, base: String): String? =
        runInterruptible(Dispatchers.IO) {
            val req = request(base, path, query).get()
                .cacheControl(CacheControl.Builder().onlyIfCached().maxStale(7, TimeUnit.DAYS).build()).build()
            if (!cacheable(req.url)) return@runInterruptible null
            try {
                client.newCall(req).execute().use { r -> if (r.isSuccessful && r.cacheResponse != null) r.body.string() else null }
            } catch (e: IOException) {
                null
            }
        }

    private fun trace(method: String, path: String, since: Long, what: String) {
        if (BuildConfig.DEBUG) Log.d("HelmNet", "$method $path ${SystemClock.elapsedRealtime() - since}ms $what")
    }

    fun sse(path: String, query: List<Pair<String, String>> = emptyList(), server: Server? = null): Flow<SseEvent> {
        val url = server?.url?.trimEnd('/') ?: base()
        return if (Demo.owns(url)) Demo.sse(path, query) else realSse(url, path, query)
    }

    private fun realSse(url: String, path: String, query: List<Pair<String, String>>): Flow<SseEvent> = callbackFlow {
        val req = request(url, path, query).header(tr(R.string.accept), "text/event-stream").build()
        val source = EventSources.createFactory(streaming).newEventSource(req, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                trySend(SseEvent("open", ""))
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                trySend(SseEvent(type ?: "message", data))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(
                    when {
                        response != null && !response.isSuccessful ->
                            httpError(response, runCatching { response.body.string() }.getOrDefault(""))
                        t is IOException -> failure()
                        else -> ApiError(tr(R.string.the_server_closed_the_connection), ApiError.Kind.Other)
                    },
                )
            }
        })
        awaitClose { source.cancel() }
    }
}

suspend inline fun <reified T> apiGet(path: String, vararg query: Pair<String, String>, base: String? = null): T {
    val text = if (base == null) Net.call("GET", path, query.toList(), null) else Net.call("GET", path, query.toList(), null, base)
    return try {
        withContext(Dispatchers.Default) { AppJson.decodeFromString<T>(text) }
    } catch (e: SerializationException) {
        Diag.note("decode GET $path: ${e.message?.take(300)}")
        throw ApiError(tr(R.string.the_server_answered_with_something_helm), ApiError.Kind.Other)
    }
}

suspend inline fun <reified T> apiPost(path: String, body: JsonObject = JsonObject(emptyMap()), base: String? = null): T {
    val text = if (base == null) Net.call("POST", path, emptyList(), body.toString()) else Net.call("POST", path, emptyList(), body.toString(), base)
    return try {
        withContext(Dispatchers.Default) { AppJson.decodeFromString<T>(text) }
    } catch (e: SerializationException) {
        Diag.note("decode POST $path: ${e.message?.take(300)}")
        throw ApiError(tr(R.string.the_server_answered_with_something_helm), ApiError.Kind.Other)
    }
}

suspend fun apiPostIgnore(path: String, body: JsonObject = JsonObject(emptyMap())) {
    Net.call("POST", path, emptyList(), body.toString())
}

suspend inline fun <reified T> apiPut(path: String, body: JsonObject = JsonObject(emptyMap())): T {
    val text = Net.call("PUT", path, emptyList(), body.toString())
    return try { withContext(Dispatchers.Default) { AppJson.decodeFromString<T>(text) } } catch (e: SerializationException) { Diag.note("decode PUT $path: ${e.message?.take(300)}"); throw ApiError(tr(R.string.the_server_answered_with_something_helm), ApiError.Kind.Other) }
}
