package org.futo.inputmethod.latin.uix

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.futo.inputmethod.latin.uix.actions.clipboard.ClipboardEntry
import org.futo.inputmethod.latin.uix.actions.clipboard.ClipboardImportMode
import org.futo.inputmethod.latin.uix.actions.clipboard.ClipboardHistoryEnabled
import org.futo.inputmethod.latin.uix.actions.clipboard.clipboardFile
import org.futo.inputmethod.latin.uix.actions.clipboard.createClipboardContentUri
import org.futo.inputmethod.latin.uix.actions.clipboard.decodeClipboardEntries
import org.futo.inputmethod.latin.uix.actions.clipboard.encodeClipboardEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BackupImportServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun settingsImport_continuesAfterActivityCloses() {
        runBlocking { context.setSetting(ClipboardHistoryEnabled, true) }
        val entry = ClipboardEntry(timestamp = 1L, pinned = true, text = "service restored", uri = null, mimeTypes = listOf("text/plain"))
        val backup = createBackup(entry, 128)
        val abandonedStage = File(context.filesDir, ".clipboard-store-stage-abandoned").apply { mkdirs() }
        File(abandonedStage, "partial-media").writeText("left by an interrupted import")
        val uri = createClipboardContentUri(backup, "application/octet-stream")
        try {
            ActivityScenario.launch<ImportResourceActivity>(progressIntent()).use { activity ->
                activity.onActivity { BackupImportService.start(it, uri) }
                await { BackupImportService.status.value.running }
            }
            await { !BackupImportService.status.value.running }
            assertEquals(BackupImportPhase.Complete, BackupImportService.status.value.phase)
            assertEquals(listOf(entry), context.clipboardFile.decodeClipboardEntries())
            assertFalse(abandonedStage.exists())
        } finally {
            backup.delete()
        }
    }

    @Test
    fun cancelPreparation_preservesExistingClipboard() {
        runBlocking { context.setSetting(ClipboardHistoryEnabled, true) }
        val entry = ClipboardEntry(timestamp = 2L, pinned = true, text = "do not install", uri = null, mimeTypes = listOf("text/plain"))
        val backup = createBackup(entry, 256)
        val uri = createClipboardContentUri(backup, "application/octet-stream")
        try {
            ActivityScenario.launch<ImportResourceActivity>(progressIntent()).use { activity ->
                val previous = context.clipboardFile.takeIf(File::exists)?.readText()
                activity.onActivity { BackupImportService.start(it, uri) }
                await { BackupImportService.status.value.phase == BackupImportPhase.Preparing }
                activity.onActivity { BackupImportService.cancel(it) }
                await { !BackupImportService.status.value.running }
                assertEquals(BackupImportPhase.Cancelled, BackupImportService.status.value.phase)
                assertEquals(previous, context.clipboardFile.takeIf(File::exists)?.readText())
                assertFalse(File(context.cacheDir, "settings_backup_import").exists())
            }
        } finally {
            backup.delete()
        }
    }

    @Test
    fun clipboardImport_respectsMergeAndReplace() {
        runBlocking { context.setSetting(ClipboardHistoryEnabled, true) }
        val original = ClipboardEntry(timestamp = 3L, pinned = true, text = "original", uri = null, mimeTypes = listOf("text/plain"))
        val incoming = original.copy(timestamp = 4L, text = "incoming")
        val backup = File(context.cacheDir, "clipboard-service-test.backup")
        try {
            ZipOutputStream(backup.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("{\"version\":1,\"createdAtEpochMs\":0}".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("clipboard.json"))
                zip.write(encodeClipboardEntries(listOf(incoming)).toByteArray())
                zip.closeEntry()
            }
            for(mode in ClipboardImportMode.entries) {
                context.clipboardFile.writeText(encodeClipboardEntries(listOf(original)))
                val uri = createClipboardContentUri(backup, "application/octet-stream")
                ActivityScenario.launch<ImportResourceActivity>(progressIntent()).use { activity ->
                    activity.onActivity { BackupImportService.start(it, uri, mode) }
                    // The tiny fixture can complete between polls; wait for its result on disk.
                    await { !BackupImportService.status.value.running && context.clipboardFile.isFile && context.clipboardFile.readText().contains("incoming") }
                    assertEquals(BackupImportPhase.Complete, BackupImportService.status.value.phase)
                    val expected = if(mode == ClipboardImportMode.Merge) setOf(original, incoming) else setOf(incoming)
                    assertEquals(expected, context.clipboardFile.decodeClipboardEntries().toSet())
                }
            }
        } finally {
            backup.delete()
        }
    }

    private fun progressIntent() = Intent(context, ImportResourceActivity::class.java)
        .putExtra(BackupImportService.ShowProgressExtra, true)

    private fun createBackup(entry: ClipboardEntry, mediaMiB: Int): File {
        val file = File(context.cacheDir, "service-test.backup")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            zip.putNextEntry(ZipEntry("FUTOKeyboardSettings_CfgExportVersion"))
            zip.write(ByteArray(9).apply { this[0] = 1 })
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("clipboard.json"))
            zip.write(encodeClipboardEntries(listOf(entry)).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("clipboard/media.jpg"))
            val buffer = ByteArray(1024 * 1024)
            repeat(mediaMiB) { zip.write(buffer) }
            zip.closeEntry()
        }
        return file
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 60_000_000_000L
        while(!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("Timed out: ${BackupImportService.status.value}", condition())
    }
}
