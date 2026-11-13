package com.anonimo00.latanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LatanimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(LatanimeProvider())
    }
}
