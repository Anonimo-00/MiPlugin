package com.anonimo00.hdfull

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * hdfull necesita una cuenta, asi que la extension expone un panel de ajustes
 * (el boton que CloudStream muestra junto a la extension) para escribir el
 * usuario y la contrasena.
 */
@CloudstreamPlugin
class HdfullPlugin : Plugin() {
    override fun load(context: Context) {
        val provider = HdfullProvider(context)
        registerMainAPI(provider)
        openSettings = { ctx -> provider.openSettings(ctx) }
    }
}
