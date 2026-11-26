package com.orvlabs.helm

import android.app.Application
import com.orvlabs.helm.data.Net
import com.orvlabs.helm.data.Repo
import com.orvlabs.helm.data.Store
import com.orvlabs.helm.service.Notifier
import com.orvlabs.helm.service.WearPush

class HelmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Str.app = this
        com.orvlabs.helm.ui.components.Haptics.init(this)
        com.orvlabs.helm.data.Diag.init(this)
        Store.init(this)
        Net.init(this)
        Notifier.channels(this)
        WearPush.init(this)
        kotlinx.coroutines.runBlocking { Repo.restore() }
        Net.onRevoked = { id ->
            if (id != null) Store.removeServerAsync(id) else Store.wipe()
            Repo.reset()
        }
    }
}
