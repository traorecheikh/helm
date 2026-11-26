package com.orvlabs.helm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.orvlabs.helm.data.ApiError
import com.orvlabs.helm.data.LogLine
import com.orvlabs.helm.data.parseTime
import com.orvlabs.helm.ui.theme.GeistMono
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

class LogStream(val lines: List<LogLine>, val live: Boolean, val error: String?)

@Composable
fun rememberLogStream(key: Any?, open: () -> Flow<LogLine>?): LogStream {
    val lines = remember(key) { mutableStateListOf<LogLine>() }
    var live by remember(key) { mutableStateOf(true) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        live = true
        error = null
        try {
            open()?.collect {
                lines.add(it)
                if (lines.size > 1500) lines.removeRange(0, 300)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            error = e.message
        } finally {
            live = false
        }
    }
    return LogStream(lines, live, error)
}

private val T = Color(0xFF5C5A56)
private val Body = Color(0xFFBDB9B2)
private val Warn = Color(0xFFFFB547)
private val Err = Color(0xFFFF5A1F)

fun logLevel(line: String): Int {
    val l = line.lowercase()
    return when {
        "error" in l || "failed" in l || "fatal" in l || "exception" in l -> 2
        "warn" in l -> 1
        else -> 0
    }
}

@Composable
fun LogBox(lines: List<LogLine>, modifier: Modifier = Modifier, follow: Boolean = true, tags: Map<String, Color> = emptyMap()) {
    val state = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val li = state.layoutInfo
            li.totalItemsCount == 0 || (li.visibleItemsInfo.lastOrNull()?.index ?: 0) >= li.totalItemsCount - 3
        }
    }
    LaunchedEffect(lines.size) { if (follow && nearEnd && lines.isNotEmpty()) state.scrollToItem(lines.lastIndex) }
    LazyColumn(
        modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black),
        state,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        items(lines) { l ->
            val time = parseTime(l.t)?.let { clockSeconds(it) }
            val color = when (logLevel(l.line)) { 2 -> Err; 1 -> Warn; else -> Body }
            BasicText(
                buildAnnotatedString {
                    if (l.src.isNotEmpty()) withStyle(SpanStyle(color = tags[l.src] ?: Body, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)) { append("${l.src} ") }
                    if (time != null) withStyle(SpanStyle(color = T)) { append("$time ") }
                    withStyle(SpanStyle(color = color)) { append(l.line) }
                },
                Modifier.fillMaxWidth(),
                style = TextStyle(fontFamily = GeistMono, fontSize = 11.sp, lineHeight = 1.75.em),
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
