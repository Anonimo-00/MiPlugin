package com.anonimo00.sololatino

import java.security.MessageDigest

/**
 * Primitivas criptograficas que exige el sitio sololatino.net.
 *
 * La API del sitio (`POST /api/player-url`) exige una cabecera `X-SL-Render`
 * calculada sobre el token del reproductor. El sitio la genera asi:
 *
 * ```
 * sha256(token + ":" + floor(ahora_en_milis / 60000) + ":sololatino_render_shield_2026")
 * ```
 *
 * y se queda con los 16 primeros caracteres en hexadecimal. Como el minuto
 * forma parte del calculo, el valor caduca cada minuto.
 *
 * Ademas, el reproductor `player.pelisserieshoy.com` exige una segunda firma
 * (`tk`): un hash FNV-1a de 32 bits sobre el token firmado que el propio
 * sitio incrusta en la pagina del reproductor.
 *
 * Referencia: `build/assets/app-*.js` de sololatino.net (funciones `_slH`,
 * `_slP`, `_qh` y `_q9`).
 */
internal object SoloLatinoShield {
    private const val SALT = "sololatino_render_shield_2026"

    /** Valor de la cabecera `X-SL-Render` para un token de reproductor. */
    fun render(token: String, nowMillis: Long = System.currentTimeMillis()): String {
        val payload = "$token:${nowMillis / 60000}:$SALT"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) {
            hex.append(String.format("%02x", byte))
        }
        return hex.substring(0, 16)
    }

    /** FNV-1a de 32 bits en hexadecimal, tal y como lo calcula el sitio. */
    fun fnv1a(value: String): String {
        var hash = 2166136261u
        for (char in value) {
            hash = hash xor char.code.toUInt()
            hash = hash * 16777619u
        }
        return hash.toString(16)
    }
}
