package net.ridgelight.app

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanStoreTest {
    private val prefs = FakePreferences()
    private val dir: File = Files.createTempDirectory("plan").toFile()
    private val store = PlanStore(prefs, dir)
    private val zurich = ZoneId.of("Europe/Zurich")
    private val plan = Settings(
        date = LocalDate.of(2026, 12, 3),
        start = LocalTime.of(8, 30),
        zone = zurich,
        paceFactor = 1.2,
        breaks = listOf(PlannedBreak(1234.5, 30), PlannedBreak(5000.0, 15)),
    )

    @Test
    fun planComesBackAsSaved() {
        store.save(plan)
        assertEquals(plan, store.load { LocalDate.of(2026, 11, 25) })
    }

    @Test
    fun aPastDateBecomesToday() {
        store.save(plan)
        val today = LocalDate.of(2026, 12, 10)
        assertEquals(plan.copy(date = today), store.load { today })
    }

    @Test
    fun paceComesBackOnTheSlidersSteps() {
        // Stored as a float, 1.15 would come back as 1.149999976158142.
        store.save(plan.copy(paceFactor = 1.15))
        assertEquals(1.15, store.load { plan.date }!!.paceFactor)
    }

    @Test
    fun nothingSavedOrUnreadableGivesNoPlan() {
        assertNull(store.load())
        store.save(plan)
        prefs.values["plan_start"] = "half past eight"
        assertNull(store.load())
    }

    @Test
    fun breaksRoundTrip() {
        assertEquals("1234.5:30;5000.0:15", PlanStore.encodeBreaks(plan.breaks))
        assertEquals(plan.breaks, PlanStore.decodeBreaks(PlanStore.encodeBreaks(plan.breaks)))
        assertEquals(emptyList(), PlanStore.decodeBreaks(""))
    }

    @Test
    fun switchesAndLastTrack() {
        assertFalse(store.cloudsEnabled)
        assertFalse(store.cloudsExplained)
        store.cloudsEnabled = true
        store.cloudsExplained = true
        store.lastName = "Lauterbrunnen"
        val again = PlanStore(prefs, dir)
        assertTrue(again.cloudsEnabled)
        assertTrue(again.cloudsExplained)
        assertEquals("Lauterbrunnen", again.lastName)
        assertEquals(File(dir, "last.gpx"), again.lastTrack)
    }
}
