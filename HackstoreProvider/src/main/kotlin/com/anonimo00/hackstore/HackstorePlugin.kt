package com.anonimo00.hackstore

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HackstorePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HackstoreProvider())
    }
}
