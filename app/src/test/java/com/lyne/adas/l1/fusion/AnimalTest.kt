package com.lyne.adas.l1.fusion

import com.lyne.adas.l1.config.AdasConfig
import com.lyne.adas.l1.config.FeatureFlags
import com.lyne.adas.l1.inference.ObjectClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimalTest {
    private val vruOnly = FeatureFlags(fcw = false, vru = true, ldw = false, headway = false, tsr = false)

    @Test fun severityLadder() {
        // 12 m/s ego.
        assertEquals(Severity.CRITICAL, PathIntrusion.animalSeverity(20f, 12f, inEgo = true, inNear = true))   // 1.7 s
        assertEquals(Severity.CAUTION, PathIntrusion.animalSeverity(30f, 12f, inEgo = true, inNear = true))    // 2.5 s
        assertEquals(Severity.CAUTION, PathIntrusion.animalSeverity(20f, 12f, inEgo = false, inNear = true))   // near only
        assertEquals(Severity.INFO, PathIntrusion.animalSeverity(29f, 5f, inEgo = true, inNear = true))       // 5.8 s
        assertEquals(Severity.NONE, PathIntrusion.animalSeverity(60f, 12f, inEgo = true, inNear = true))
        assertEquals(Severity.NONE, PathIntrusion.animalSeverity(10f, 12f, inEgo = false, inNear = false))
        // Stopped / crawling: nothing at all.
        assertEquals(Severity.NONE, PathIntrusion.animalSeverity(3f, 2.0f, inEgo = true, inNear = true))
        // No GPS: assume 6 m/s but never CRITICAL.
        assertEquals(Severity.CAUTION, PathIntrusion.animalSeverity(8f, Float.NaN, inEgo = true, inNear = true))
        assertEquals(Severity.NONE, PathIntrusion.animalSeverity(Float.NaN, 12f, inEgo = true, inNear = true))
    }

    /** A cow standing in our lane at 70 m while we drive at 12 m/s: one caution, then one critical. */
    @Test fun cowInLaneEscalatesOncePerIncident() {
        for (fps in listOf(8f, 15f)) {
            val proj = Projector(seed = 21L + fps.toLong())
            val cow = SimObject(z = 70f, x = 0.1f, width = 0.8f, height = 1.4f, cls = ObjectClass.ANIMAL)
            val engine = FusionEngine(AdasConfig.DEFAULT, vruOnly, autoCalEnabled = false)
            var critAtZ = Float.NaN; var cautAtZ = Float.NaN
            val events = runSim(proj, listOf(cow), 5.3f, fps, engine = engine, step = { dt, _ -> cow.z -= 12f * dt; 12f }) { _, r ->
                if (cautAtZ.isNaN() && r.animal.ordinal >= Severity.CAUTION.ordinal) cautAtZ = cow.z
                if (critAtZ.isNaN() && r.animal == Severity.CRITICAL) critAtZ = cow.z
            }
            val animalEvents = events.filter { it.type == AlertType.ANIMAL }
            println("cow @${fps}fps: caution at ${cautAtZ}m critical at ${critAtZ}m events=${animalEvents.map { it.severity }}")
            assertTrue("caution fired", !cautAtZ.isNaN() && cautAtZ > 24f)
            assertTrue("critical fired with time to react", !critAtZ.isNaN() && critAtZ > 10f)
            assertTrue("one event per level", animalEvents.size <= 2)
            assertTrue(animalEvents.all { it.message == "ANIMAL AHEAD" })
            assertTrue("never treated as pedestrian", events.none { it.type == AlertType.VRU })
        }
    }

    /** Dog on the verge 4 m to the side: no warning (and never critical). */
    @Test fun roadsideAnimalIgnored() {
        val proj = Projector(seed = 5)
        val dog = SimObject(z = 50f, x = 4.0f, width = 0.6f, height = 0.6f, cls = ObjectClass.ANIMAL)
        val engine = FusionEngine(AdasConfig.DEFAULT, vruOnly, autoCalEnabled = false)
        val events = runSim(proj, listOf(dog), 3.5f, 15f, engine = engine, step = { dt, _ -> dog.z -= 12f * dt; 12f })
        assertEquals(0, events.count { it.type == AlertType.ANIMAL })
    }

    /** Crawling past a cow at 7 km/h: silent. */
    @Test fun slowEgoIsSilent() {
        val proj = Projector(seed = 6)
        val cow = SimObject(z = 12f, x = 0f, width = 0.8f, height = 1.4f, cls = ObjectClass.ANIMAL)
        val engine = FusionEngine(AdasConfig.DEFAULT, vruOnly, autoCalEnabled = false)
        val events = runSim(proj, listOf(cow), 4f, 15f, engine = engine, step = { dt, _ -> cow.z -= 2f * dt; 2f })
        assertEquals(0, events.count { it.type == AlertType.ANIMAL })
    }
}
