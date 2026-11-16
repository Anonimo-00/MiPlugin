package com.anonimo00.serieslan

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SeriesLanPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SeriesLanProvider())
    }
}
