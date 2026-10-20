package com.ras.mt5runner

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.BufferedInputStream
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * Safely extracts the CI runtime candidate's bundle/ tree from a raw .tar.xz archive.
 * Provenance is not installed into the executable tree. Extraction does not imply runtime validation.
 */
class RuntimeBundleInstaller(context: Context) {
    private val context = context.applicationContext
    private val maxExpandedBytes = 8L * 1024L * 1024L * 1024L
    private val maxEntries = 300_000

    fun install(archive: File, expectedSha256: String? = null, onProgress: (String) -> Unit = {}): File {
        require(archive.isFile && archive.canRead()) { "Runtime archive is not readable" }
        expectedSha256?.let { expected ->
            require(expected.matches(Regex("[a-fA-F0-9]{64}"))) { "Expected runtime SHA-256 is malformed" }
            val actual = sha256(archive)
            require(actual.equals(expected, ignoreCase = true)) {
                "Runtime SHA-256 mismatch before extraction (expected $expected, got $actual)"
            }
        }
        require(archive.length() >= 6L) { "Runtime archive is empty or truncated" }
        val magic = ByteArray(6)
        FileInputStream(archive).use { input ->
            require(input.read(magic) == magic.size) { "Runtime archive header is truncated" }
        }
        val xzMagic = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)
        require(magic.contentEquals(xzMagic)) {
            val hint = if (magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()) {
                "This is a ZIP file. Extract the GitHub Actions artifact ZIP first, then select the inner runtime-bundle-candidate.tar.xz file."
            } else {
                "Expected a raw .tar.xz file, not a web page, ZIP, or incomplete download."
            }
            "Runtime archive is not in XZ format. $hint"
        }

        val runtimeDir = File(context.filesDir, "runtime")
        val target = File(runtimeDir, "bundle")
        val staging = File(runtimeDir, "bundle.installing")
        val backup = File(runtimeDir, "bundle.previous")
        if (!runtimeDir.exists() && !runtimeDir.mkdirs()) {
            throw IllegalStateException("Could not create app-private runtime directory")
        }
        if (staging.exists() && !staging.deleteRecursively()) {
            throw IllegalStateException("Could not clear incomplete runtime staging directory")
        }
        if (backup.exists() && !backup.deleteRecursively()) {
            throw IllegalStateException("Could not clear stale runtime backup")
        }
        if (!staging.mkdirs()) throw IllegalStateException("Could not create runtime staging directory")
        val rootPath = staging.absoluteFile.toPath().normalize()
        var totalBytes = 0L
        var entriesSeen = 0
        var sawBundleEntry = false

        try {
            // Scan tar metadata once so extraction can report a real file-byte percentage.
            var totalFileBytes = 0L
            onProgress("Extraction: archive scan 0%")
            BufferedInputStream(FileInputStream(archive)).use { fileInput ->
                XZCompressorInputStream(fileInput).use { xz ->
                    TarArchiveInputStream(xz).use { tar ->
                        while (true) {
                            val entry = tar.nextTarEntry ?: break
                            if (entry.name == "bundle" || entry.name.startsWith("bundle/")) {
                                if (entry.isFile) {
                                    totalFileBytes += entry.size
                                    require(totalFileBytes <= maxExpandedBytes) { "Runtime archive exceeds expanded size limit" }
                                }
                            }
                        }
                    }
                }
            }
            require(totalFileBytes > 0L) { "Runtime archive has no regular bundle files" }
            onProgress("Extraction: 0% (private staging)")
            var lastReportedPercent = -1
            BufferedInputStream(FileInputStream(archive)).use { fileInput ->
                XZCompressorInputStream(fileInput).use { xz ->
                    TarArchiveInputStream(xz).use { tar ->
                        while (true) {
                            val entry = tar.nextTarEntry ?: break
                            entriesSeen++
                            require(entriesSeen <= maxEntries) { "Runtime archive has too many entries" }
                            val name = entry.name
                            if (name != "bundle" && !name.startsWith("bundle/")) continue
                            sawBundleEntry = true
                            val relative = name.removePrefix("bundle").trimStart('/')
                            if (relative.isEmpty()) continue
                            val relativePath = Paths.get(relative).normalize()
                            require(!relativePath.isAbsolute && !relativePath.startsWith("..")) {
                                "Unsafe path in runtime archive"
                            }
                            val destination = staging.toPath().resolve(relativePath).normalize()
                            require(destination.startsWith(rootPath)) { "Runtime entry escapes bundle directory" }
                            val output = destination.toFile()

                            if (entry.isDirectory) {
                                if (!output.exists() && !output.mkdirs()) throw IllegalStateException("Could not create directory: ${output.path}")
                            } else if (entry.isSymbolicLink) {
                                output.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IllegalStateException("Could not create symlink parent") }
                                val link = Paths.get(entry.linkName)
                                val resolved = if (link.isAbsolute) {
                                    rootPath.resolve(link.toString().trimStart('/')).normalize()
                                } else {
                                    output.toPath().parent.resolve(link).normalize()
                                }
                                require(resolved.startsWith(rootPath)) { "Symlink escapes runtime bundle" }
                                val safeLink = if (link.isAbsolute) {
                                    output.toPath().parent.relativize(resolved).toString()
                                } else {
                                    link.toString()
                                }
                                require(!output.exists() && !java.nio.file.Files.isSymbolicLink(output.toPath())) {
                                    "Duplicate runtime archive path: ${output.path}"
                                }
                                Os.symlink(safeLink, output.absolutePath)
                            } else if (entry.isFile) {
                                totalBytes += entry.size
                                require(totalBytes <= maxExpandedBytes) { "Runtime archive exceeds expanded size limit" }
                                output.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IllegalStateException("Could not create file parent") }
                                require(!output.exists()) { "Duplicate runtime archive path: ${output.path}" }
                                output.outputStream().use { out ->
                                    val buffer = ByteArray(128 * 1024)
                                    while (true) {
                                        val count = tar.read(buffer)
                                        if (count < 0) break
                                        out.write(buffer, 0, count)
                                        totalBytes += count
                                        val percent = ((totalBytes * 100L) / totalFileBytes).toInt().coerceIn(0, 100)
                                        if (percent != lastReportedPercent) {
                                            lastReportedPercent = percent
                                            onProgress("Extraction: $percent% (\${totalBytes / (1024 * 1024)} MiB)")
                                        }
                                    }
                                }
                                if ((entry.mode and 0x49) != 0) output.setExecutable(true, false)
                            }
                        }
                    }
                }
            }
            require(sawBundleEntry) { "Archive has no bundle/ directory; select the raw runtime-bundle-candidate.tar.xz, not the artifact ZIP" }
            require(File(staging, "manifest.json").isFile) { "Runtime bundle manifest is missing" }
            require(File(staging, "bin/box64").isFile) { "Box64 executable is missing" }
            require(File(staging, "rootfs-arm64/etc/os-release").isFile) { "ARM64 host rootfs is missing" }
            require(File(staging, "rootfs-arm64/usr/local/bin/box64").isFile) { "Box64 host-rootfs executable is missing" }
            require(File(staging, "rootfs/etc/os-release").isFile) { "Wine rootfs is missing" }
            require(File(staging, "rootfs/opt/wine-11.0-wow64/bin/wine").isFile) { "Wine executable is missing" }
            require(File(staging, "rootfs/opt/wine-11.0-wow64/bin/wine").canExecute()) { "Wine executable bit is missing" }

            if (target.exists() && !target.renameTo(backup)) {
                throw IllegalStateException("Could not preserve the existing runtime bundle")
            }
            if (!staging.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                throw IllegalStateException("Could not atomically activate the new runtime bundle")
            }
            backup.deleteRecursively()
            return target
        } catch (e: Exception) {
            staging.deleteRecursively()
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            val digest = runCatching { sha256(archive) }.getOrDefault("unavailable")
            throw IllegalStateException(
                "Runtime staging failed (archiveSha256=$digest): ${e.message ?: e.javaClass.simpleName}", e
            )
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
