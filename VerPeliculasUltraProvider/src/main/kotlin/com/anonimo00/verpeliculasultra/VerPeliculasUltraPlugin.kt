package com.anonimo00.verpeliculasultra

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VerPeliculasUltraPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(VerPeliculasUltraProvider())
    }
}
