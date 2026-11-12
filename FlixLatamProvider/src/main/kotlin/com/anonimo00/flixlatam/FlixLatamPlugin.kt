package com.anonimo00.flixlatam

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FlixLatamPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FlixLatamProvider())
    }
}
