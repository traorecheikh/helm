package com.orvlabs.helm.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Detail
import com.orvlabs.helm.data.Svc
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.*

@Composable
internal fun DataTab(kind: String, svc: Svc?, load: Load<Detail>, container: String?, owner: Boolean) {
    val d = load.value
    load.error?.let { ErrorLine(it) }
    if (owner && kind == "postgres" && container != null) DbTools(container, d?.databaseName.orEmpty())
    val host = d?.appName ?: svc?.appName.orEmpty()
    Card(Modifier.padding(top = 12.dp)) {
        val rows = listOfNotNull(
            tr(R.string.internal_host) to host,
            d?.databaseName?.let { tr(R.string.database) to it },
            d?.databaseUser?.let { tr(R.string.user) to it },
            d?.externalPort?.let { tr(R.string.external_port) to "$it" },
        )
        rows.forEachIndexed { i, (k, v) -> Stat(k, v, rule = i < rows.lastIndex) }
    }
}
