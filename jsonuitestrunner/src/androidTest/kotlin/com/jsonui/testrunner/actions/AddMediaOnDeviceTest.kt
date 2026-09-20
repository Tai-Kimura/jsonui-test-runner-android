package com.jsonui.testrunner.actions

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.jsonui.testrunner.models.TestStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `addMedia` re-seeds instead of accumulating. Reported 2026-09-20: one
 * MediaStore row per run, respelled `name (n).png` by MediaStore, until
 * AOSP's unique-name builder gave up at n = 32 and every later run of the
 * test went red with "Failed to build unique file".
 *
 * The rows are this test package's own (targetContext of a library
 * androidTest is the test APK), inserted under a name no consumer uses,
 * and removed in @After — this arm must not itself leave a row behind.
 *
 * No CI lane runs androidTest here; execute locally against an emulator:
 *   ./gradlew :jsonuitestrunner:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.jsonui.testrunner.actions.AddMediaOnDeviceTest
 */
@RunWith(AndroidJUnit4::class)
class AddMediaOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val name = "jsonui_addmedia_probe.png"

    private fun rowsNamedLike(): List<String> =
        resolver.query(
            collection, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("jsonui_addmedia_probe%"), null
        )?.use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() } ?: emptyList()

    private fun purge() {
        resolver.delete(collection, "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("jsonui_addmedia_probe%"))
    }

    /** A 1x1 PNG in the fixtures dir the executor reads relative paths from. */
    private fun fixture(): File {
        val dir = File(context.filesDir, "media_probe").apply { mkdirs() }
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(), 0, 0, 0, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, 0x9C.toByte(), 0x63, 0, 1, 0, 0, 5, 0, 1, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
        )
        return File(dir, name).also { it.writeBytes(png) }
    }

    private fun executor(dir: File) = ActionExecutor(UiDevice.getInstance(instrumentation)).apply { mediaFixturesDir = dir }

    @After
    fun cleanUp() = purge()

    @Test
    fun theSameFixtureAddedTwiceLeavesOneRowAndNoRespelledCopy() {
        purge()
        val file = fixture()
        val ex = executor(file.parentFile!!)
        ex.execute(TestStep(action = "addMedia", paths = listOf(name)))
        ex.execute(TestStep(action = "addMedia", paths = listOf(name)))
        val rows = rowsNamedLike()
        assertEquals("one row after two adds: $rows", listOf(name), rows)
    }

    @Test
    fun earlierRespelledCopiesOfThisPackageAreRemovedBeforeTheInsert() {
        purge()
        // Stage what 1.15.5 left behind: the base name plus three `(n)` copies.
        for (spelling in listOf(name, "jsonui_addmedia_probe (1).png", "jsonui_addmedia_probe (2).png", "jsonui_addmedia_probe (3).png")) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, spelling)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            }
            resolver.insert(collection, v)
        }
        assertEquals(4, rowsNamedLike().size)
        val file = fixture()
        executor(file.parentFile!!).execute(TestStep(action = "addMedia", paths = listOf(name)))
        val rows = rowsNamedLike()
        assertEquals("the copies are gone and the base name is back: $rows", listOf(name), rows)
        assertTrue(rows.none { it.contains(" (") })
    }
}
