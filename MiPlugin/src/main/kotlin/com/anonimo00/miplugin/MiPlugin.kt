package com.anonimo00.miplugin

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MiPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MiProvider())
    }
}