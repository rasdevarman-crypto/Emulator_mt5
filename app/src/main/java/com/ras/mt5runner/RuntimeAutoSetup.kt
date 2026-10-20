package com.ras.mt5runner

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Base64

/**
 * Downloads a version-pinned runtime manifest and archive, verifies SHA-256 before extraction,
 * and delegates path-safe installation to RuntimeBundleInstaller.
 * A pending/example manifest is intentionally refused.
 */
class RuntimeAutoSetup(context: Context) {
    private val appContext = context.applicationContext
    private val manifestUrl =
        "https://raw.githubusercontent.com/rasdevarman-crypto/Emulator_mt5/main/runtime/runtime-manifest.json"
    private val manifestApiUrl =
        "https://api.github.com/repos/rasdevarman-crypto/Emulator_mt5/contents/runtime/runtime-manifest.json?ref=main"
    private val maxArchiveBytes = 3L * 1024L * 1024L * 1024L

    fun downloadAndInstall(onProgress: (String) -> Unit): File {
        // Prefer the checksum-pinned archive already embedded in an all-in-one APK.
        // This avoids blocking setup on a remote manifest that is intentionally still pending.
        installEmbeddedRuntimeIfAvailable(onProgress)?.let { return it }
        val manifest = downloadManifest(onProgress)
        require(manifest.optInt("schema_version", -1) == 1) { "Unsupported runtime manifest schema" }
        require(manifest.optString("status") == "verified") {
            "Runtime manifest status is '${manifest.optString("status", "missing")}', not verified. Automatic setup is disabled until a successful bundle checksum and ARM64 smoke test are published."
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
            verifyXzHeader(staging)
            if (archive.exists() && !archive.delete()) throw IllegalStateException("Cannot replace staged runtime archive")
            if (!staging.renameTo(archive)) throw IllegalStateException("Cannot finalize downloaded runtime archive")
            onProgress("Checksum and XZ header verified; installing into app-private staging")
            return RuntimeBundleInstaller(appContext).install(archive, onProgress = onProgress)
        } finally {
            staging.delete()
            archive.delete()
        }
    }

    private fun installEmbeddedRuntimeIfAvailable(onProgress: (String) -> Unit): File? {
        val runtimeAssets = appContext.assets.list("runtime") ?: return null
        if (!runtimeAssets.contains("runtime-bundle.tar.xz")) return null

        val expectedHash = appContext.assets.open("runtime/runtime-bundle.tar.xz.sha256")
            .bufferedReader(Charsets.UTF_8).use { it.readText().trim().split(Regex("\\s+")).firstOrNull().orEmpty() }
            .lowercase()
        require(expectedHash.matches(Regex("[a-f0-9]{64}"))) {
            "Embedded runtime SHA-256 sidecar is missing or malformed"
        }

        val runtimeDir = File(appContext.filesDir, "runtime")
        val target = File(runtimeDir, "bundle")
        val marker = File(runtimeDir, ".embedded-runtime-sha256")
        val stagedFilesPresent = File(target, "manifest.json").isFile &&
            File(target, "bin/box64").canExecute() &&
            File(target, "rootfs-arm64/etc/os-release").isFile &&
            File(target, "rootfs/opt/wine-11.0-wow64/bin/wine").canExecute()
        if (marker.isFile && marker.readText().trim() == expectedHash && stagedFilesPresent) {
            onProgress("Embedded runtime checksum already verified; private staging is current")
            return target
        }

        val archive = File(appContext.cacheDir, "runtime-embedded.tar.xz")
        try {
            if (archive.exists() && !archive.delete()) {
                throw IllegalStateException("Cannot clear previous embedded runtime staging archive")
            }
            var received = 0L
            appContext.assets.open("runtime/runtime-bundle.tar.xz").use { input ->
                FileOutputStream(archive).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        require(received <= maxArchiveBytes) {
                            "Embedded runtime archive exceeds the safety size limit"
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(received > 0L && archive.length() == received) {
                "Embedded runtime archive copy was empty or incomplete"
            }
            val actualHash = sha256(archive)
            require(actualHash == expectedHash) {
                "Embedded runtime SHA-256 mismatch (expected $expectedHash, got $actualHash)"
            }
            onProgress("Embedded runtime SHA-256 verified; validating XZ and staging privately")
            val installed = RuntimeBundleInstaller(appContext).install(archive, expectedHash, onProgress)
            marker.parentFile?.mkdirs()
            marker.writeText(expectedHash)
            onProgress("Runtime checksum যাচাই ও private staging সম্পন্ন")
            return installed
        } finally {
            archive.delete()
        }
    }

    private fun downloadManifest(onProgress: (String) -> Unit): JSONObject {
        val failures = mutableListOf<String>()
        try {
            val raw = fetchText(manifestUrl, null)
            onProgress("Runtime manifest downloaded from raw.githubusercontent.com")
            return JSONObject(raw)
        } catch (e: Exception) {
            failures += "raw.githubusercontent.com: ${shortError(e)}"
            onProgress("Primary manifest host failed; trying GitHub API fallback")
        }

        try {
            val apiJson = JSONObject(fetchText(manifestApiUrl, "application/vnd.github+json"))
            require(apiJson.optString("encoding") == "base64") { "GitHub API manifest response was not base64 encoded" }
            val encoded = apiJson.optString("content").replace("\\s".toRegex(), "")
            val decoded = Base64.getDecoder().decode(encoded)
            onProgress("Runtime manifest downloaded through GitHub API fallback")
            return JSONObject(String(decoded, Charsets.UTF_8))
        } catch (e: Exception) {
            failures += "api.github.com: ${shortError(e)}"
        }
        val dns = failures.any { it.contains("UnknownHostException") || it.contains("resolve") || it.contains("hostname", true) }
        val advice = if (dns) {
            " Check internet access, VPN/proxy and Android Settings > Network > Private DNS; then retry on another network."
        } else ""
        throw IllegalStateException("Could not fetch runtime manifest. ${failures.joinToString(" | ")}.$advice")
    }

    private fun fetchText(url: String, accept: String?): String {
        var last: Exception? = null
        for (attempt in 1..2) {
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    if (accept != null) setRequestProperty("Accept", accept)
                    setRequestProperty("User-Agent", "MT5-RAS-Runner-Android")
                }
                try {
                    val code = connection.responseCode
                    require(code == HttpURLConnection.HTTP_OK) {
                        "HTTP $code from ${URL(url).host}"
                    }
                    require(connection.contentLengthLong in -1L..1_048_576L) { "Runtime manifest is too large" }
                    return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                last = e
                if (attempt < 2) Thread.sleep(500L * attempt)
            }
        }
        throw last ?: IllegalStateException("Manifest request failed")
    }

    private fun downloadToFile(url: String, destination: File, expectedSize: Long, onProgress: (String) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "MT5-RAS-Runner-Android")
        }
        try {
            val code = connection.responseCode
            require(code == HttpURLConnection.HTTP_OK) {
                val host = runCatching { URL(url).host }.getOrDefault("runtime host")
                "Runtime download failed (HTTP $code from $host)"
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
                        if (received == expectedSize || received % (4L * 1024L * 1024L) < count) {
                            val percent = ((received * 100L) / expectedSize).toInt().coerceIn(0, 100)
                            onProgress("Download: $percent% (${received / (1024 * 1024)} / ${expectedSize / (1024 * 1024)} MiB)")
                        }
                    }
                    output.fd.sync()
                }
            }
            require(received == expectedSize) { "Runtime download was incomplete ($received/$expectedSize bytes)" }
            onProgress("Download: 100% (${received / (1024 * 1024)} MiB); verifying SHA-256")
        } finally {
            connection.disconnect()
        }
    }

    private fun verifyXzHeader(file: File) {
        val expected = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)
        val actual = ByteArray(expected.size)
        file.inputStream().use { input ->
            require(input.read(actual) == actual.size && actual.contentEquals(expected)) {
                "Downloaded payload is not an XZ archive. The URL may return an HTML error page or an artifact ZIP."
            }
        }
    }

    private fun shortError(error: Exception): String = when (error) {
        is UnknownHostException -> "DNS lookup failed (UnknownHostException: ${error.message})"
        else -> "${error.javaClass.simpleName}: ${error.message ?: "no details"}"
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
