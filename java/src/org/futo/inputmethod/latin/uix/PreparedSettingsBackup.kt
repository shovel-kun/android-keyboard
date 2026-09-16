package org.futo.inputmethod.latin.uix

import org.futo.inputmethod.latin.uix.actions.clipboard.ClipboardArchiveFileName
import org.futo.inputmethod.latin.uix.actions.clipboard.ClipboardFileName
import org.futo.inputmethod.latin.uix.actions.clipboard.decodeClipboardEntries
import org.futo.inputmethod.latin.uix.actions.clipboard.decodeLegacyClipboardArchives
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Extracts into private staging storage; never writes to the live settings or clipboard. */
internal fun prepareSettingsBackup(input: InputStream, directory: File): List<File> {
    directory.mkdirs()
    val root = directory.canonicalFile
    val files = mutableListOf<File>()
    val names = mutableSetOf<String>()
    try {
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while(entry != null) {
                val name = entry.name
                val parts = name.split('/')
                require(!name.startsWith('/') && parts.none { it == ".." || it == "." }) {
                    "Invalid backup path"
                }
                val file = File(root, name).canonicalFile
                require(file.path.startsWith(root.path + File.separator)) { "Invalid backup path" }
                require(names.add(name)) { "Duplicate backup entry: $name" }
                if(!entry.isDirectory) {
                    file.parentFile!!.mkdirs()
                    file.outputStream().buffered(64 * 1024).use { zip.copyTo(it, 64 * 1024) }
                    files += file
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val header = File(root, "FUTOKeyboardSettings_CfgExportVersion")
        require(header.isFile && header.length() == 9L) { "Invalid settings backup header" }
        File(root, ClipboardFileName).takeIf(File::isFile)?.decodeClipboardEntries()
        File(root, ClipboardArchiveFileName).takeIf(File::isFile)?.readText()?.let(::decodeLegacyClipboardArchives)
        return files
    } catch(e: Exception) {
        directory.deleteRecursively()
        throw e
    }
}
