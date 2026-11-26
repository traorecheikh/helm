package com.orvlabs.helm.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Lock
import com.adamglin.phosphoricons.regular.Moon
import com.adamglin.phosphoricons.regular.Play
import com.adamglin.phosphoricons.regular.Sun
import com.adamglin.phosphoricons.regular.Trash
import com.orvlabs.helm.R
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.Card
import com.orvlabs.helm.ui.components.Feel
import com.orvlabs.helm.ui.components.Haptics
import com.orvlabs.helm.ui.components.IconTile
import com.orvlabs.helm.ui.components.Li
import com.orvlabs.helm.ui.components.Ph
import com.orvlabs.helm.ui.components.Toggle

internal object SI {
    val Moon: ImageVector get() = PhosphorIcons.Regular.Moon
    val Sun: ImageVector get() = PhosphorIcons.Regular.Sun
    val Lock: ImageVector get() = PhosphorIcons.Regular.Lock
    val Play: ImageVector get() = PhosphorIcons.Regular.Play
    val Trash: ImageVector get() = PhosphorIcons.Regular.Trash
}

internal fun alertRows(): List<Triple<String, ImageVector, String>> = listOf(
    Triple("build", Ph.Rocket, tr(R.string.set_a_build)),
    Triple("server", Ph.Server, tr(R.string.set_a_server)),
    Triple("backup", Ph.Database, tr(R.string.set_a_backup)),
    Triple("ban", Ph.Shield, tr(R.string.set_a_ban)),
    Triple("activity", Ph.Pulse, tr(R.string.set_a_activity)),
)

@Composable
internal fun SwitchRow(title: String, checked: Boolean, icon: ImageVector? = null, rule: Boolean = false, onChange: (Boolean) -> Unit) {
    Box(Modifier.toggleable(checked, role = Role.Switch, onValueChange = { Haptics.fire(if (it) Feel.ToggleOn else Feel.ToggleOff); onChange(it) })) {
        Li(title, rule = rule, left = icon?.let { i -> @Composable { IconTile(i) } }, right = { Toggle(checked) })
    }
}

@Composable
internal fun RowsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Card(modifier, pad = 0) { Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), content = content) }

@Composable
internal fun SheetBody(content: @Composable ColumnScope.() -> Unit) {
    val max = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
    Column(Modifier.imePadding().heightIn(max = max).verticalScroll(rememberScrollState()), content = content)
}

fun openNotificationSettings(ctx: android.content.Context) {
    ctx.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
