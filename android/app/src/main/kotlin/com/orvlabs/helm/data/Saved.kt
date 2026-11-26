package com.orvlabs.helm.data

import kotlinx.serialization.Serializable
import com.orvlabs.helm.R
import com.orvlabs.helm.tr

@Serializable
data class Step(val type: String, val kind: String = "", val id: String = "", val name: String = "") {
    val label get() = when (type) {
        "redeploy" -> tr(R.string.redeploy, name)
        "restart" -> tr(R.string.restart_2, name)
        "start" -> tr(R.string.start_2, name)
        "stop" -> tr(R.string.stop_3, name)
        "traefik" -> tr(R.string.reload_traefik)
        else -> tr(R.string.clean_docker)
    }
    val needsService get() = type in setOf("redeploy", "restart", "start", "stop")
}

@Serializable
data class SavedAction(val name: String, val steps: List<Step>)
