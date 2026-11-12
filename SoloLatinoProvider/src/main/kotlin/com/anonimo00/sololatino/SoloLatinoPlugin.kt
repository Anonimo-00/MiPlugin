package com.anonimo00.sololatino

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SoloLatinoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SoloLatinoProvider())
    }
}
