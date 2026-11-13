package com.anonimo00.flixcorn

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FlixCornPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FlixCornProvider())
    }
}
