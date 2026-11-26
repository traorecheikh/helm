package com.orvlabs.helm.data

import android.content.Context
import android.os.Build
import com.orvlabs.helm.BuildConfig
import java.io.File

object Diag {
    private lateinit var file: File
    private val notes = ArrayDeque<String>()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { file.writeText(report(e)) }
            previous?.uncaughtException(t, e)
        }
    }

    fun note(line: String) = synchronized(notes) {
        if (BuildConfig.DEBUG) android.util.Log.w("HelmDiag", line)
        notes.addLast("${java.time.LocalTime.now().withNano(0)} $line")
        if (notes.size > 40) notes.removeFirst()
    }

    fun pendingCrash(): String? = file.takeIf { it.exists() }?.readText()

    fun clear() { file.delete() }

    private fun report(e: Throwable) = buildString {
        appendLine("Helm ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}")
        appendLine(e.stackTraceToString().take(6000))
        synchronized(notes) { notes.forEach(::appendLine) }
    }
}
