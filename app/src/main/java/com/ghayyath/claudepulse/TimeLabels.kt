package com.ghayyath.claudepulse

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** Countdown is derived from provider timestamps; never assumes a window has reset. */
object TimeLabels {
    fun untilReset(raw: String?, now: Instant = Instant.now()): String {
        if (raw.isNullOrBlank()) return "—"
        return try {
            val target = OffsetDateTime.parse(raw).toInstant()
            val minutes = Duration.between(now, target).toMinutes()
            if (minutes <= 0) return "à vérifier"
            if (minutes < 60) return minutes.toString() + " min"
            val hours = minutes / 60
            if (hours < 24) return hours.toString() + " h " + (minutes % 60) + " min"
            (hours / 24).toString() + " j " + (hours % 24) + " h"
        } catch (_: Exception) {
            "—"
        }
    }

    fun age(iso: String?, now: Instant = Instant.now()): String {
        if (iso.isNullOrBlank()) return "jamais"
        return try {
            val minutes = Duration.between(Instant.parse(iso), now).toMinutes().coerceAtLeast(0)
            when {
                minutes < 1 -> "à l'instant"
                minutes < 60 -> "il y a " + minutes + " min"
                else -> "il y a " + (minutes / 60) + " h"
            }
        } catch (_: Exception) {
            "inconnue"
        }
    }
}
