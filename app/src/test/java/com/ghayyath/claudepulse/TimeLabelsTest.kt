package com.ghayyath.claudepulse

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TimeLabelsTest {
    private val now = Instant.parse("2026-11-01T00:00:00Z")

    @Test fun futureReset() {
        assertEquals("2 h 30 min",
            TimeLabels.untilReset("2026-11-01T02:30:00Z", now))
    }
    @Test fun offsetReset() {
        assertEquals("30 min",
            TimeLabels.untilReset("2026-11-01T01:30:00+01:00", now))
    }
    @Test fun pastResetIsNotClaimedToBeFresh() {
        assertEquals("à vérifier",
            TimeLabels.untilReset("2026-10-31T23:59:00Z", now))
    }
    @Test fun missingReset() {
        assertEquals("—", TimeLabels.untilReset(null, now))
    }
    @Test fun ageIsVisible() {
        assertEquals("il y a 15 min", TimeLabels.age("2026-10-31T23:45:00Z", now))
    }
}
