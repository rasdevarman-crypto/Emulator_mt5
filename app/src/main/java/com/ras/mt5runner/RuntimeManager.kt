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
    private val proot = File(runtimeDir, "bin/proot")
    private val bundle = File(runtimeDir, "bundle")
    private val hostRoot = File(bundle, "rootfs-arm64")
    private val wineRoot = File(bundle, "rootfs")
    private val box64 = File(bundle, "bin/box64")
    private val wine = File(wineRoot, "opt/wine-11.0-wow64/bin/wine")
    private val prefix = File(runtimeDir, "wineprefix")

    data class Preflight(val ready: Boolean, val message: String)

    fun preflight(): Preflight {
        val missing = mutableListOf<String>()
        if (!proot.isFile || !proot.canExecute()) missing += "Android-native PRoot executable missing: ${proot.path}"
        if (!box64.isFile || !box64.canExecute()) missing += "Box64 binary missing: ${box64.path}"
        if (!File(hostRoot, "etc/os-release").isFile) missing += "ARM64 host rootfs incomplete"
        if (!File(wineRoot, "etc/os-release").isFile) missing += "x86_64 Wine rootfs incomplete"
        if (!wine.isFile || !wine.canExecute()) missing += "Wine executable missing: ${wine.path}"
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

    fun pathsSummary(): String = listOf(
        "PRoot: ${proot.path}",
        "Bundle: ${bundle.path}",
        "Box64: ${box64.path}",
        "Wine: ${wine.path}",
        "Wine prefix: ${prefix.path}"
    ).joinToString("\n")
}
