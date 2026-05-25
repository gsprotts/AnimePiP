package com.gprotts.animepip

import java.net.URI

object CrunchyrollUrlPolicy {
    fun isAllowedCrunchyrollUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false

        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }

        if (!uri.scheme.equals("https", ignoreCase = true)) return false

        val host = uri.host?.lowercase() ?: return false

        return host == "crunchyroll.com" || host.endsWith(".crunchyroll.com")
    }
}
