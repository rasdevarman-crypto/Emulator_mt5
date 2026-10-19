package com.ras.mt5runner

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads a version-pinned runtime manifest and archive, verifies SHA-256 before extraction,
 * and delegates path-safe installation to RuntimeBundleInstaller.
 *
 * The manifest must be hosted over HTTPS and contain:
 * status=verified, schema_version=1, archive_url, archive_sha256, archive_size_bytes.
 * This intentionally refuses the example manifest or an unverified runtime candidate.
 */
class RuntimeAutoSetup(context: Context) {
    private val appContext = context.applicationContext
    private val manifestUrl =
        "https://raw.githubusercontent.com/rasdevarman-crypto/Emulator_mt5/main/runtime/runtime-manifest.json"
    private val maxArchiveBytes = 3L * 1024L * 1024L * 1024L

    fun downloadAndInstall(onProgress: (String) -> Unit): File {
        val manifest = downloadManifest()
        require(manifest.optInt("schema_version", -1) == 1) { "Unsupported runtime manifest schema" }
        require(manifest.optString("status") == "verified") {
            "Published runtime manifest is not marked verified; automatic setup is disabled"
        }
        val archiveUrl = manifest.optString("archive_url")
        require(archiveUrl.startsWith("https://")) { "Runtime archive URL must use HTTPS" }
        val expectedHash = manifest.optString("archive_sha256").lowercase()
        require(expectedHash.matches(Regex("[a-f0-9]{64}"))) { "Runtime manifest SHA-256 is invalid" }
        val expectedSize = manifest.optLong("archive_size_bytes", -1)
        require(expectedSize in 1..maxArchiveBytes) { "Runtime archive size is missing or exceeds safety limit" }

        val staging = File(appContext.cacheDir, "runtime-download.part")
        val archive = File(appContext.cacheDir, "runtime-download.tar.xz")
        try {
            staging.delete()
            downloadToFile(archiveUrl, staging, expectedSize, onProgress)
            val actualHash = sha256(staging)
            require(actualHash == expectedHash) {
                "Runtime SHA-256 mismatch (expected $expectedHash, got $actualHash)"
            }
            require(staging.length() == expectedSize) { "Runtime archive size does not match manifest" }
            if (archive.exists() && !archive.delete()) throw IllegalStateException("Cannot replace staged runtime archive")
            if (!staging.renameTo(archive)) throw IllegalStateException("Cannot finalize downloaded runtime archive")
            onProgress("Checksum verified; installing runtime into private storage")
            return RuntimeBundleInstaller(appContext).install(archive)
        } finally {
            staging.delete()
            archive.delete()
        }
    }

    private fun downloadManifest(): JSONObject {
        val connection = (URL(manifestUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            requestMethod = "GET"
        }
        try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Runtime manifest unavailable (HTTP ${connection.responseCode}); verified bundle may not be published yet"
            }
            require(connection.contentLengthLong in -1L..1_048_576L) { "Runtime manifest is too large" }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadToFile(url: String, destination: File, expectedSize: Long, onProgress: (String) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Runtime download failed (HTTP ${connection.responseCode})"
            }
            val declared = connection.contentLengthLong
            require(declared == -1L || declared == expectedSize) { "Server archive size differs from manifest" }
            var received = 0L
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        require(received <= expectedSize && received <= maxArchiveBytes) {
                            "Runtime download exceeded the declared size"
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(received == expectedSize) { "Runtime download was incomplete ($received/$expectedSize bytes)" }
            onProgress("Downloaded ${received / (1024 * 1024)} MiB; verifying SHA-256")
        } finally {
            connection.disconnect()
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
