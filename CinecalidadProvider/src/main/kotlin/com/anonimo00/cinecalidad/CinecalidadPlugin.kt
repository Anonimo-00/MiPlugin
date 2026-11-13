package com.anonimo00.cinecalidad

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CinecalidadPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CinecalidadProvider())
    }
}
