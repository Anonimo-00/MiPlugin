package com.anonimo00.pelisplushd

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PelisPlusHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PelisPlusHDProvider())
    }
}
