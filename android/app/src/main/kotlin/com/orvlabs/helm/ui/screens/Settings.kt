package com.orvlabs.helm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Device
import com.orvlabs.helm.data.Repo
import com.orvlabs.helm.data.Store
import com.orvlabs.helm.ml.AiRow
import com.orvlabs.helm.service.MorningWorker
import com.orvlabs.helm.service.WatchService
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.Btn
import com.orvlabs.helm.ui.components.BtnStyle
import com.orvlabs.helm.ui.components.Card
import com.orvlabs.helm.ui.components.Chips
import com.orvlabs.helm.ui.components.ConfirmSheet
import com.orvlabs.helm.ui.components.DotNum
import com.orvlabs.helm.ui.components.Feel
import com.orvlabs.helm.ui.components.H1
import com.orvlabs.helm.ui.components.H2
import com.orvlabs.helm.ui.components.Haptics
import com.orvlabs.helm.ui.components.Ico
import com.orvlabs.helm.ui.components.IconTile
import com.orvlabs.helm.ui.components.K
import com.orvlabs.helm.ui.components.Li
import com.orvlabs.helm.ui.components.Mk
import com.orvlabs.helm.ui.components.Ph
import com.orvlabs.helm.ui.components.Pill
import com.orvlabs.helm.ui.components.Screen
import com.orvlabs.helm.ui.components.SheetFrame
import com.orvlabs.helm.ui.components.Tone
import com.orvlabs.helm.ui.components.Txt
import com.orvlabs.helm.ui.components.press
import com.orvlabs.helm.ui.theme.H
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class Sheet { Devices, Activity, AddServer, Platform }

@Composable
fun SettingsScreen(nav: NavController) {
    val cfg = Store.config.collectAsStateWithLifecycle().value ?: return
    val st = Repo.state.collectAsStateWithLifecycle().value
    val pending = st.pending
    val platform = st.platform
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var signOut by remember { mutableStateOf(false) }
    val c = H.c
    val modes = listOf("system", "light", "dark")

    Screen(back = tr(R.string.tab_home), onBack = { nav.popBackStack() }, dock = false) {
        H1(tr(R.string.settings), Modifier.padding(top = 2.dp))

        Card(Modifier.padding(top = 16.dp), color = c.signal, pad = 20) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(if (platform == "none") c.signalInk.copy(alpha = 0.18f) else c.card), Alignment.Center) {
                    if (platform == "none") Ico(Ph.Server, size = 26, tint = c.signalInk) else PlatformLogo(platform, 28, platformName(platform))
                }
                Column(Modifier.weight(1f)) {
                    Txt(cfg.host, size = 20, weight = 700, color = c.signalInk, maxLines = 1)
                    Txt(tr(if (cfg.owner) R.string.set_owner else R.string.set_member), size = 13, weight = 600, color = c.signalInk.copy(alpha = 0.8f))
                }
            }
            DotNum(Device.fingerprint(cfg.activeId), 34, Modifier.padding(top = 18.dp), color = c.signalInk)
        }

        Group(tr(R.string.set_servers)) {
            if (cfg.servers.size > 1) cfg.servers.forEach { sv ->
                val active = sv.id == cfg.activeId
                Li(sv.host, rule = true, left = { Mk(if (active) Tone.Ok else Tone.Ring) },
                    onClick = if (active) null else ({ scope.launch { switchServer(sv.id) } }))
            }
            Li(tr(R.string.plat_title), rule = true, left = { Box(Modifier.size(40.dp), Alignment.Center) { PlatformLogo(platform, 24) } },
                right = { Ico(Ph.Next, size = 16, tint = c.muted) }, onClick = { sheet = Sheet.Platform })
            Li(tr(R.string.add_a_server), rule = cfg.servers.any { it.demo }, left = { IconTile(Ph.Plus) }, onClick = { sheet = Sheet.AddServer })
            if (cfg.servers.any { it.demo }) Li(tr(R.string.demo_exit), left = { IconTile(Ph.SignOut) }, onClick = {
                scope.launch {
                    Store.removeServerAsync(cfg.servers.first { it.demo }.id)
                    Store.config.first { c -> c?.servers?.none { it.demo } == true }
                    switchServer(Store.config.value?.activeId ?: 0)
                }
            })
        }

        if (cfg.owner) Group(null) {
            Li(tr(R.string.devices), rule = true, left = { IconTile(Ph.Devices) },
                right = {
                    if (pending > 0) Pill("$pending", Tone.Warn)
                    Ico(Ph.Next, size = 16, tint = c.muted)
                }, onClick = { sheet = Sheet.Devices })
            Li(tr(R.string.activity_log), left = { IconTile(Ph.History) }, right = { Ico(Ph.Next, size = 16, tint = c.muted) }, onClick = { sheet = Sheet.Activity })
        }

        Group(tr(R.string.alerts)) {
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
                Li(tr(R.string.notifications_are_off_for_helm), rule = true, left = { Mk(Tone.Signal) }, onClick = { openNotificationSettings(ctx) })
            }
            alertRows().forEach { (k, icon, title) ->
                SwitchRow(title, k in cfg.alerts, icon, rule = true) { on -> scope.launch { Store.setAlerts(if (on) cfg.alerts + k else cfg.alerts - k) } }
            }
            SwitchRow(tr(R.string.quiet_hours), cfg.quietOn, SI.Moon, rule = cfg.quietOn) { scope.launch { Store.setQuiet(it, cfg.quietStart, cfg.quietEnd) } }
            if (cfg.quietOn) {
                TimeRow(tr(R.string.starts), cfg.quietStart, rule = true) { scope.launch { Store.setQuiet(true, it, cfg.quietEnd) } }
                TimeRow(tr(R.string.ends), cfg.quietEnd, rule = true) { scope.launch { Store.setQuiet(true, cfg.quietStart, it) } }
            }
            SwitchRow(tr(R.string.morning_report), cfg.morningAt >= 0, SI.Sun, rule = cfg.morningAt >= 0) { on ->
                val m = if (on) 8 * 60 else -1
                scope.launch { Store.setMorning(m) }
                MorningWorker.schedule(ctx, m, replace = true)
            }
            if (cfg.morningAt >= 0) TimeRow(tr(R.string.sent_at), cfg.morningAt) { scope.launch { Store.setMorning(it) }; MorningWorker.schedule(ctx, it, replace = true) }
        }

        Group(tr(R.string.set_security)) {
            SwitchRow(tr(R.string.lock_helm_title), cfg.appLock, SI.Lock, rule = true) { scope.launch { Store.setAppLock(it) } }
            SwitchRow(tr(R.string.fingerprint_for_actions), cfg.biometric, Ph.Fingerprint) { scope.launch { Store.setBiometric(it) } }
        }

        Group(null) { AiRow() }

        Group(tr(R.string.set_look)) {
            Row(Modifier.padding(vertical = 12.dp), Arrangement.spacedBy(8.dp)) {
                listOf(Triple(Ph.Theme, R.string.system, "system"), Triple(SI.Sun, R.string.light, "light"), Triple(SI.Moon, R.string.dark, "dark")).forEach { (icon, label, mode) ->
                    ThemeTile(icon, tr(label), cfg.theme == mode, Modifier.weight(1f)) {
                        Haptics.fire(Feel.Tick)
                        scope.launch { Store.setTheme(mode); com.orvlabs.helm.widget.Widgets.updateAll(ctx) }
                    }
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val lm = ctx.getSystemService(android.app.LocaleManager::class.java)
                val cur = lm.applicationLocales.let { if (it.isEmpty) 0 else if (it[0].language == "fr") 1 else 2 }
                Box(Modifier.padding(bottom = 8.dp)) {
                    Chips(listOf(tr(R.string.lang_system), tr(R.string.lang_french), tr(R.string.lang_english)), cur, { i ->
                        lm.applicationLocales = when (i) {
                            1 -> android.os.LocaleList.forLanguageTags("fr")
                            2 -> android.os.LocaleList.forLanguageTags("en")
                            else -> android.os.LocaleList.getEmptyLocaleList()
                        }
                    })
                }
            }
        }

        SavedSection()

        Btn(tr(R.string.sign_out), { signOut = true }, Modifier.padding(top = 24.dp), BtnStyle.Danger)
    }

    when (sheet) {
        Sheet.Devices -> DevicesSheet { sheet = null }
        Sheet.Activity -> ActivitySheet { sheet = null }
        Sheet.AddServer -> AddServerSheet { sheet = null }
        Sheet.Platform -> SheetFrame(true, { sheet = null }) {
            H2(tr(R.string.plat_title))
            PlatformTiles(st.snap?.caps, platform, Modifier.padding(top = 16.dp))
        }
        null -> {}
    }
    ConfirmSheet(
        signOut, tr(R.string.sign_out_of_this_phone), tr(R.string.the_key_for_this_phone_is),
        tr(R.string.keep_holding_to_sign_out),
        onConfirm = {
            signOut = false
            WatchService.stop(ctx)
            Repo.reset()
            Store.wipe()
        },
        onDismiss = { signOut = false },
    )
}

@Composable
private fun Group(title: String?, content: @Composable ColumnScope.() -> Unit) {
    if (title != null) K(title, Modifier.padding(top = 24.dp, bottom = 8.dp, start = 4.dp))
    RowsCard(if (title == null) Modifier.padding(top = 16.dp) else Modifier, content)
}

@Composable
internal fun AddServerSheet(onDismiss: () -> Unit) {
    val id = remember { Store.nextServerId() }
    var phase by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    SheetFrame(true, onDismiss) {
        SheetBody {
            ConnectBody(phase, { phase = it }, serverId = id, adding = true) {
                scope.launch { switchServer(id); onDismiss() }
            }
        }
    }
}

@Composable
private fun ThemeTile(icon: ImageVector, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = H.c
    Column(
        modifier.heightIn(min = 72.dp).clip(RoundedCornerShape(20.dp))
            .background(if (on) c.signal else c.raise).press(onClick, role = Role.RadioButton)
            .semantics { selected = on }.padding(vertical = 12.dp),
        Arrangement.spacedBy(6.dp, Alignment.CenterVertically), Alignment.CenterHorizontally,
    ) {
        Ico(icon, size = 22, tint = if (on) c.signalInk else c.muted)
        Txt(label, size = 13, weight = 600, color = if (on) c.signalInk else c.muted, maxLines = 1)
    }
}

@Composable
private fun TimeRow(title: String, minutes: Int, rule: Boolean = false, onChange: (Int) -> Unit) {
    fun step(d: Int) = onChange((minutes + d + 1440) % 1440)
    Li(title, rule = rule, left = { IconTile(Ph.History) }, right = {
        Row(Modifier.clip(CircleShape).background(H.c.raise), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).press({ step(-30) }, tr(R.string.n30_minutes_earlier, title)), Alignment.Center) { Txt("-", size = 22) }
            Txt("%02d:%02d".format(minutes / 60, minutes % 60), size = 15, mono = true, weight = 500)
            Box(Modifier.size(48.dp).press({ step(30) }, tr(R.string.n30_minutes_later, title)), Alignment.Center) { Txt("+", size = 22) }
        }
    })
}
