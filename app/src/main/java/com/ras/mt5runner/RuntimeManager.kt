package com.ras.mt5runner

import android.content.Context
import java.io.File

/**
 * Experimental launcher for a manually staged Android-compatible PRoot and CI runtime bundle.
 *
 * Expected private layout:
 * filesDir/runtime/bin/proot        (Android arm64-v8a executable, not Linux/glibc proot)
 * filesDir/runtime/bundle/bin/box64
 * filesDir/runtime/bundle/rootfs-arm64
 * filesDir/runtime/bundle/rootfs
 *
 * The CI bundle alone is insufficient: PRoot must be built for Android/Bionic and GUI rendering
 * must be provided separately before a Windows GUI application can be considered usable.
 */
class RuntimeManager(context: Context) {
    private val appContext = context.applicationContext
    private val runtimeDir = File(appContext.filesDir, "runtime")
    private val proot = File(runtimeDir, "proot/bin/proot")
    private val bundle = File(runtimeDir, "bundle")
    private val hostRoot = File(bundle, "rootfs-arm64")
    private val wineRoot = File(bundle, "rootfs")
    private val box64 = File(bundle, "bin/box64")
    private val wine = File(wineRoot, "opt/wine-11.0-wow64/bin/wine")
    private val prefix = File(runtimeDir, "wineprefix")

    data class Preflight(val ready: Boolean, val message: String)

    fun preflight(): Preflight {
        val missing = mutableListOf<String>()
        try {
            ensureBundledProot()
        } catch (e: Exception) {
            missing += "Bundled Android PRoot could not be installed: ${e.message}"
        }
        if (!proot.isFile || !proot.canExecute()) {
            missing += "Android-native PRoot executable missing or not executable: ${proot.path}"
        } else {
            val probeFile = File(appContext.cacheDir, "proot-preflight.txt")
            try {
                val probe = ProcessBuilder(proot.absolutePath, "--version")
                    .redirectErrorStream(true)
                    .redirectOutput(probeFile)
                    .start()
                if (!probe.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) {
                    probe.destroyForcibly()
                    missing += "Android-native PRoot did not respond to --version within 4 seconds"
                } else if (probe.exitValue() != 0 || !probeFile.isFile || probeFile.length() == 0L) {
                    val detail = if (probeFile.isFile) probeFile.readText().take(300) else "no output"
                    missing += "Android-native PRoot execution probe failed (exit=${probe.exitValue()}): $detail"
                }
            } catch (e: Exception) {
                missing += "Android-native PRoot cannot execute on this device: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                probeFile.delete()
            }
        }
        if (!box64.isFile || !box64.canExecute()) missing += "Box64 binary missing or not executable: ${box64.path}"
        if (!File(hostRoot, "etc/os-release").isFile) missing += "ARM64 host rootfs incomplete"
        if (!File(hostRoot, "usr/local/bin/box64").canExecute()) missing += "ARM64 host rootfs Box64 executable missing"
        if (!File(wineRoot, "etc/os-release").isFile) missing += "x86_64 Wine rootfs incomplete"
        if (!wine.isFile || !wine.canExecute()) missing += "Wine executable missing or not executable: ${wine.path}"
        if (missing.isNotEmpty()) return Preflight(false, missing.joinToString("; "))
        return Preflight(true, "Runtime files found. Android GUI/display compatibility is not validated.")
    }

    fun launchInstaller(installer: File, onLine: (String) -> Unit): Process {
        val preflightResult = preflight()
        check(preflightResult.ready) { preflightResult.message }
        require(installer.isFile && installer.canRead()) { "Installer file is not readable" }
        prefix.mkdirs()
        val ldPath = listOf(
            "/x86root/lib/x86_64-linux-gnu",
            "/x86root/usr/lib/x86_64-linux-gnu",
            "/x86root/lib64",
            "/x86root/opt/wine-11.0-wow64/lib"
        ).joinToString(":")
        val command = listOf(
            proot.absolutePath, "-r", hostRoot.absolutePath,
            "-b", "${wineRoot.absolutePath}:/x86root",
            "-b", "${prefix.absolutePath}:/wineprefix",
            "-b", "${installer.absolutePath}:/installer.exe",
            "-b", "/dev", "-b", "/proc", "--link2symlink", "-p", "-L",
            "/usr/bin/env",
            "BOX64_LD_LIBRARY_PATH=$ldPath",
            "WINEPREFIX=/wineprefix",
            "WINEDEBUG=-all",
            "/usr/local/bin/box64",
            "/x86root/opt/wine-11.0-wow64/bin/wine",
            "/installer.exe"
        )
        return ProcessBuilder(command).redirectErrorStream(true).start().also { process ->
            Thread({
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> onLine(line.take(2000)) }
                }
            }, "wine-installer-log").apply { isDaemon = true; start() }
        }
    }


    fun launchMt5(onLine: (String) -> Unit): Process {
        val executable = mt5Executable()
        val preflightResult = preflight()
        check(preflightResult.ready) { preflightResult.message }
        prefix.mkdirs()
        val ldPath = listOf(
            "/x86root/lib/x86_64-linux-gnu",
            "/x86root/usr/lib/x86_64-linux-gnu",
            "/x86root/lib64",
            "/x86root/opt/wine-11.0-wow64/lib"
        ).joinToString(":")
        val command = listOf(
            proot.absolutePath, "-r", hostRoot.absolutePath,
            "-b", "${wineRoot.absolutePath}:/x86root",
            "-b", "${prefix.absolutePath}:/wineprefix",
            "-b", "/dev", "-b", "/proc", "--link2symlink", "-p", "-L",
            "/usr/bin/env",
            "BOX64_LD_LIBRARY_PATH=$ldPath",
            "WINEPREFIX=/wineprefix",
            "WINEDEBUG=-all",
            "/usr/local/bin/box64",
            "/x86root/opt/wine-11.0-wow64/bin/wine",
            "/wineprefix/drive_c/Program Files/MetaTrader 5/${executable.name}"
        )
        return ProcessBuilder(command).redirectErrorStream(true).start().also { process ->
            Thread({
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> onLine(line.take(2000)) }
                }
            }, "wine-mt5-log").apply { isDaemon = true; start() }
        }
    }

    fun mt5Executable(): File {
        val candidates = listOf(
            File(prefix, "drive_c/Program Files/MetaTrader 5/terminal64.exe"),
            File(prefix, "drive_c/Program Files/MetaTrader 5/terminal.exe"),
            File(prefix, "drive_c/Program Files/MetaTrader 5/terminal64")
        )
        return candidates.firstOrNull { it.isFile }
            ?: throw IllegalStateException("MT5 executable not found in Wine prefix. Run the official installer first.")
    }


    private fun ensureBundledProot() {
        val destination = File(runtimeDir, "proot")
        val marker = File(destination, ".asset-installed")
        if (marker.isFile && proot.isFile && proot.canExecute()) return
        val entries = appContext.assets.list("runtime/proot")
            ?: throw IllegalStateException("APK has no runtime/proot assets")
        if (entries.isEmpty()) throw IllegalStateException("APK has no bundled PRoot files")
        if (destination.exists()) destination.deleteRecursively()
        copyAssetTree("runtime/proot", destination)
        if (!proot.setExecutable(true, false) && !proot.canExecute()) {
            throw IllegalStateException("Could not mark bundled PRoot executable")
        }
        marker.writeText("source=APK asset; Android arm64 PRoot candidate")
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = appContext.assets.list(assetPath) ?: emptyArray()
        if (children.isNotEmpty()) {
            if (!destination.exists() && !destination.mkdirs()) {
                throw IllegalStateException("Could not create ${destination.path}")
            }
            children.forEach { child ->
                copyAssetTree("$assetPath/$child", File(destination, child))
            }
        } else {
            destination.parentFile?.let { if (!it.exists() && !it.mkdirs()) {
                throw IllegalStateException("Could not create ${it.path}")
            } }
            appContext.assets.open(assetPath).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            if (destination.name in setOf("proot", "proot-userland", "loader", "loader32")) {
                destination.setExecutable(true, false)
            }
        }
    }

    fun pathsSummary(): String = listOf(
        "PRoot: ${proot.path}",
        "Bundle: ${bundle.path}",
        "Box64: ${box64.path}",
        "Wine: ${wine.path}",
        "Wine prefix: ${prefix.path}"
    ).joinToString("\n")
}
