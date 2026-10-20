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
    // Execute the Android/Bionic ELF from PackageManager's native-library directory, not
    // writable filesDir. Android may reject execve() from app-writable storage with EACCES
    // even after chmod(0755).
    private val nativeLibDir = File(appContext.applicationInfo.nativeLibraryDir)
    private val proot = File(nativeLibDir, "libproot.so")
    private val prootLoader = File(nativeLibDir, "libproot_loader.so")
    private val prootLoader32 = File(nativeLibDir, "libproot_loader32.so")
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
                missing += "Android-native PRoot cannot execute on this device: ${e.message ?: e.javaClass.simpleName}; path=${proot.absolutePath}; exists=${proot.isFile}; readable=${proot.canRead()}; executable=${proot.canExecute()}; appFilesDir=${appContext.filesDir.absolutePath}"
            } finally {
                probeFile.delete()
            }
        }
        if (!box64.isFile || !box64.canExecute()) missing += "Box64 binary missing or not executable: ${box64.path}"
        if (!File(hostRoot, "etc/os-release").isFile) missing += "ARM64 host rootfs incomplete"
        if (!File(hostRoot, "usr/local/bin/box64").canExecute()) missing += "ARM64 host rootfs Box64 executable missing"
        if (!File(wineRoot, "etc/os-release").isFile) missing += "x86_64 Wine rootfs incomplete"
        if (!wine.isFile || !wine.canExecute()) {
            missing += "Wine executable missing or not executable: ${wine.path}; exists=${wine.isFile}; readable=${wine.canRead()}; executable=${wine.canExecute()}; bytes=${wine.length()}"
        }

        // Dependencies explicitly observed missing in ARM64 CI smoke logs.
        // Check both standard multiarch library locations in the x86_64 Wine rootfs.
        val libraryCandidates = mapOf(
            "libfontconfig.so.1" to listOf(
                File(wineRoot, "lib/x86_64-linux-gnu/libfontconfig.so.1"),
                File(wineRoot, "usr/lib/x86_64-linux-gnu/libfontconfig.so.1")
            ),
            "libfreetype.so.6" to listOf(
                File(wineRoot, "lib/x86_64-linux-gnu/libfreetype.so.6"),
                File(wineRoot, "usr/lib/x86_64-linux-gnu/libfreetype.so.6")
            ),
            "libbrotlidec.so.1" to listOf(
                File(wineRoot, "lib/x86_64-linux-gnu/libbrotlidec.so.1"),
                File(wineRoot, "usr/lib/x86_64-linux-gnu/libbrotlidec.so.1")
            )
        )
        libraryCandidates.forEach { (library, candidates) ->
            if (candidates.none { it.isFile }) {
                missing += "Wine rootfs dependency missing: $library; checked=${candidates.joinToString { it.path }}"
            }
        }

        val manifestFile = File(bundle, "manifest.json")
        val manifestStatus = if (!manifestFile.isFile) {
            "missing"
        } else {
            runCatching {
                org.json.JSONObject(manifestFile.readText()).optString("status", "unspecified")
            }.getOrDefault("invalid-json")
        }
        if (missing.isNotEmpty()) {
            return Preflight(false, missing.joinToString("; ") + "; embedded runtime manifest status=$manifestStatus (manifest status alone does not override archive checksum verification)")
        }
        return Preflight(true, "Runtime files and observed Wine dependencies found; embedded runtime manifest status=$manifestStatus. Android GUI/display compatibility is not validated.")
    }

    private fun startProot(command: List<String>): Process {
        val tempDir = File(appContext.cacheDir, "proot-tmp")
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw IllegalStateException("Could not create PRoot temporary directory: ${tempDir.absolutePath}")
        }
        return ProcessBuilder(command).apply {
            environment()["PROOT_LOADER"] = prootLoader.absolutePath
            if (prootLoader32.isFile && prootLoader32.canExecute()) {
                environment()["PROOT_LOADER_32"] = prootLoader32.absolutePath
            }
            environment()["PROOT_TMP_DIR"] = tempDir.absolutePath
        }.redirectErrorStream(true).start()
    }

    /**
     * Explicitly initialize the app-private Wine prefix. This never starts an installer or MT5.
     * Call verifyWinePrefix() after the returned wineboot process exits with code 0.
     */
    fun initializeWinePrefix(onLine: (String) -> Unit): Process {
        val preflightResult = preflight()
        check(preflightResult.ready) { preflightResult.message }
        if (!prefix.exists() && !prefix.mkdirs()) {
            throw IllegalStateException("Could not create private Wine prefix directory: ${prefix.absolutePath}")
        }
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
            "BOX64_DYNAREC=0",
            "/usr/local/bin/box64",
            "/x86root/opt/wine-11.0-wow64/bin/wineboot",
            "--init"
        )
        return startProot(command).also { process ->
            Thread({
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> onLine(line.take(2000)) }
                }
            }, "wine-prefix-init-log").apply { isDaemon = true; start() }
        }
    }

    /** Verify on-disk evidence produced by wineboot; directory existence alone is insufficient. */
    fun verifyWinePrefix(): String {
        val requiredFiles = listOf(File(prefix, "system.reg"), File(prefix, "user.reg"))
        val requiredDirectories = listOf(File(prefix, "drive_c"), File(prefix, "dosdevices"))
        val missing = requiredFiles.filterNot { it.isFile } + requiredDirectories.filterNot { it.isDirectory }
        check(missing.isEmpty()) {
            "Wine prefix initialization evidence missing or wrong type: ${missing.joinToString { it.name }}; prefix=${prefix.absolutePath}"
        }
        return "Wine prefix initialized and verified: system.reg, user.reg, drive_c, dosdevices; path=${prefix.absolutePath}"
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
            // PRoot intercepts signals used by Box64 dynarec; disable dynarec in this
            // experimental Android path to avoid the observed PRoot SIGSEGV crash.
            "BOX64_DYNAREC=0",
            "/usr/local/bin/box64",
            "/x86root/opt/wine-11.0-wow64/bin/wine",
            "/installer.exe"
        )
        return startProot(command).also { process ->
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
            // PRoot intercepts signals used by Box64 dynarec; disable dynarec in this
            // experimental Android path to avoid the observed PRoot SIGSEGV crash.
            "BOX64_DYNAREC=0",
            "/usr/local/bin/box64",
            "/x86root/opt/wine-11.0-wow64/bin/wine",
            "/wineprefix/drive_c/Program Files/MetaTrader 5/${executable.name}"
        )
        return startProot(command).also { process ->
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
        if (!proot.isFile || !proot.canExecute()) {
            throw IllegalStateException(
                "Android-native PRoot is missing or not executable in nativeLibraryDir: " +
                    "${proot.absolutePath}; exists=${proot.isFile}; readable=${proot.canRead()}; " +
                    "executable=${proot.canExecute()}. APK must include an ABI-matched libproot.so."
            )
        }
        if (!prootLoader.isFile || !prootLoader.canExecute()) {
            throw IllegalStateException(
                "PRoot guest loader is missing or not executable in nativeLibraryDir: ${prootLoader.absolutePath}"
            )
        }
        // PRoot guest-loader helpers are data assets. Keep the main executable in
        // nativeLibraryDir rather than executing it from writable app-private storage.
        val destination = File(runtimeDir, "proot")
        val marker = File(destination, ".asset-installed")
        val loader = File(destination, "libexec/proot/loader")
        if (marker.isFile && loader.isFile) return
        val entries = appContext.assets.list("runtime/proot")
            ?: throw IllegalStateException("APK has no runtime/proot assets")
        if (entries.isEmpty()) throw IllegalStateException("APK has no bundled PRoot helper assets")
        if (destination.exists()) destination.deleteRecursively()
        copyAssetTree("runtime/proot", destination)
        marker.writeText("source=APK assets; executable=${proot.absolutePath}; main ELF in nativeLibraryDir")
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
