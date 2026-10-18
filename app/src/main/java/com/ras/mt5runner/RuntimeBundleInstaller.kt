package com.ras.mt5runner

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.BufferedInputStream
import java.nio.file.Paths

/**
 * Safely extracts the CI runtime candidate's bundle/ tree from a .tar.xz artifact.
 * Provenance is not installed into the executable tree. Extraction does not imply runtime validation.
 */
class RuntimeBundleInstaller(context: Context) {
    private val context = context.applicationContext
    private val maxExpandedBytes = 8L * 1024L * 1024L * 1024L
    private val maxEntries = 300_000

    fun install(archive: File): File {
        require(archive.isFile && archive.canRead()) { "Runtime archive is not readable" }
        val runtimeDir = File(context.filesDir, "runtime")
        val target = File(runtimeDir, "bundle")
        runtimeDir.mkdirs()
        if (target.exists() && !target.deleteRecursively()) {
            throw IllegalStateException("Could not clear old runtime bundle")
        }
        if (!target.mkdirs()) throw IllegalStateException("Could not create runtime directory")
        val rootPath = target.absoluteFile.toPath().normalize()
        var totalBytes = 0L
        var entriesSeen = 0

        try {
            BufferedInputStream(FileInputStream(archive)).use { fileInput ->
                XZCompressorInputStream(fileInput).use { xz ->
                    TarArchiveInputStream(xz).use { tar ->
                        while (true) {
                            val entry = tar.nextTarEntry ?: break
                            entriesSeen++
                            require(entriesSeen <= maxEntries) { "Runtime archive has too many entries" }
                            val name = entry.name
                            if (name != "bundle" && !name.startsWith("bundle/")) continue
                            val relative = name.removePrefix("bundle").trimStart('/')
                            if (relative.isEmpty()) continue
                            val relativePath = Paths.get(relative).normalize()
                            require(!relativePath.isAbsolute && !relativePath.startsWith("..")) {
                                "Unsafe path in runtime archive"
                            }
                            val destination = target.toPath().resolve(relativePath).normalize()
                            require(destination.startsWith(rootPath)) { "Runtime entry escapes bundle directory" }
                            val output = destination.toFile()

                            if (entry.isDirectory) {
                                if (!output.exists() && !output.mkdirs()) throw IllegalStateException("Could not create directory")
                            } else if (entry.isSymbolicLink) {
                                output.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IllegalStateException("Could not create symlink parent") }
                                val link = Paths.get(entry.linkName)
                                require(!link.isAbsolute) { "Absolute symlink is not allowed in runtime bundle" }
                                val resolved = output.toPath().parent.resolve(link).normalize()
                                require(resolved.startsWith(rootPath)) { "Symlink escapes runtime bundle" }
                                Os.symlink(entry.linkName, output.absolutePath)
                            } else if (entry.isFile) {
                                totalBytes += entry.size
                                require(totalBytes <= maxExpandedBytes) { "Runtime archive exceeds expanded size limit" }
                                output.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IllegalStateException("Could not create file parent") }
                                output.outputStream().use { out -> tar.copyTo(out) }
                                if ((entry.mode and 0x49) != 0) output.setExecutable(true, false)
                            }
                        }
                    }
                }
            }
            require(File(target, "manifest.json").isFile) { "Runtime bundle manifest is missing" }
            require(File(target, "bin/box64").isFile) { "Box64 executable is missing" }
            require(File(target, "rootfs-arm64/etc/os-release").isFile) { "ARM64 host rootfs is missing" }
            require(File(target, "rootfs/etc/os-release").isFile) { "Wine rootfs is missing" }
            require(File(target, "rootfs/opt/wine-11.0-wow64/bin/wine").isFile) { "Wine executable is missing" }
            return target
        } catch (e: Exception) {
            target.deleteRecursively()
            throw e
        }
    }
}
