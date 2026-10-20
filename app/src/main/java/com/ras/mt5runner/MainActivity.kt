package com.ras.mt5runner

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var logsView: TextView
    private lateinit var progressView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var keepScreenOn: CheckBox
    private lateinit var launchMt5Button: Button
    private lateinit var runtimeManager: RuntimeManager
    private var selectedInstaller: java.io.File? = null
    private val logs = mutableListOf<String>()
    private val installerRequest = 5105
    private val exportLogsRequest = 5106
    private val runtimeBundleRequest = 5107
    private val exportLogsZipRequest = 5108
    private val preferences by lazy { getSharedPreferences("runner_settings", MODE_PRIVATE) }
    @Volatile private var safeAutoStartRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSavedLogs()
        runtimeManager = RuntimeManager(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(0xFFF6F7FB.toInt())
        }
        root.addView(TextView(this).apply {
            text = "RAS"; textSize = 34f; gravity = Gravity.CENTER
            setTextColor(0xFF172554.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, params())
        root.addView(TextView(this).apply {
            text = "MT5 RAS Runner"; textSize = 21f; gravity = Gravity.CENTER
            setTextColor(0xFF111827.toInt())
        }, params())
        status = TextView(this).apply {
            text = "অবস্থা: MT5 রানটাইম সংযুক্ত নয়"
            textSize = 14f; setPadding(0, 20, 0, 14)
            setTextColor(0xFF374151.toInt())
        }
        root.addView(status, params())
        launchMt5Button = Button(this).apply {
            text = "Windows চালু করুন"
            isEnabled = true
            setOnClickListener { launchWindowsOneTap() }
        }
        root.addView(launchMt5Button, params())
        root.addView(Button(this).apply {
            text = "Runtime Download / Install"
            setOnClickListener { downloadVerifiedRuntime() }
        }, params())
        root.addView(Button(this).apply {
            text = "Log ফাইল Download (TXT)"
            setOnClickListener { exportLogs() }
        }, params())
        root.addView(Button(this).apply {
            text = "সব Log Download (ZIP)"
            setOnClickListener { exportLogsZip() }
        }, params())
        progressView = TextView(this).apply {
            text = "ডাউনলোড/ইনস্টলেশন: এখনো শুরু হয়নি"
            textSize = 13f
            setPadding(0, 8, 0, 4)
            setTextColor(0xFF374151.toInt())
        }
        root.addView(progressView, params())
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        root.addView(progressBar, params())

        root.addView(TextView(this).apply {
            text = "ডায়াগনস্টিক লগ"; textSize = 18f
            setPadding(0, 16, 0, 8); setTextColor(0xFF111827.toInt())
        }, params())
        logsView = TextView(this).apply { textSize = 12f; setTextColor(0xFF374151.toInt()) }
        root.addView(ScrollView(this).apply { addView(logsView) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        addLog("অ্যাপ চালু হয়েছে; নিরাপদ runtime auto-start শুরু হচ্ছে। MT5 স্বয়ংক্রিয়ভাবে চালু হবে শুধু runtime ও PRoot test সফল হলে।")
        startSafeAutoStart()
    }


    /**
     * Safe startup: inspect current state, install a verified runtime if needed,
     * execute-test Android PRoot, and launch MT5 only if it is already installed.
     * Runtime download/install never launches an installer or MT5 as a side effect.
     */
    private fun startSafeAutoStart() {
        if (safeAutoStartRunning) return
        safeAutoStartRunning = true
        launchMt5Button.isEnabled = false
        setProgress("অ্যাপ startup preflight চলছে…", null)
        Thread({
            try {
                var check = runtimeManager.preflight()
                runOnUiThread { addLog("Startup preflight: ${check.message}") }

                if (!check.ready) {
                    var installed: java.io.File? = null
                    var lastFailure: Exception? = null
                    for (attempt in 1..3) {
                        runOnUiThread {
                            setProgress("Verified runtime install চেষ্টা $attempt/3…", null)
                            addLog("Runtime auto-install attempt $attempt/3")
                        }
                        try {
                            installed = RuntimeAutoSetup(this).downloadAndInstall { message ->
                                runOnUiThread {
                                    addLog("Runtime setup: $message")
                                    val percent = Regex("(?:Download|ডাউনলোড|Extraction|Install):\\s*(\\d{1,3})%")
                                        .find(message)?.groupValues?.get(1)?.toIntOrNull()
                                    setProgress(message, percent)
                                }
                            }
                            lastFailure = null
                            break
                        } catch (e: Exception) {
                            lastFailure = e
                            runOnUiThread {
                                addLog("Runtime auto-install attempt $attempt failed: ${e.message ?: e.javaClass.simpleName}")
                            }
                            if (attempt < 3) {
                                try { Thread.sleep(1000L * attempt) } catch (_: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                    throw IllegalStateException("Runtime setup was interrupted")
                                }
                            }
                        }
                    }
                    if (lastFailure != null) {
                        throw IllegalStateException(
                            "Verified runtime auto-install failed after 3 attempts. " +
                                (lastFailure.message ?: lastFailure.javaClass.simpleName)
                        )
                    }
                    if (installed != null) {
                        runOnUiThread { addLog("Verified runtime private staging complete: ${installed.absolutePath}") }
                    }
                    runOnUiThread { setProgress("Runtime integrity verified; Android PRoot execution test চলছে…", null) }
                    check = runtimeManager.preflight()
                    runOnUiThread { addLog("Post-install PRoot/runtime preflight: ${check.message}") }
                }

                if (!check.ready) {
                    val message = check.message
                    val prootFailure = message.contains("cannot execute", true) ||
                        message.contains("execution probe failed", true) ||
                        message.contains("permission denied", true) ||
                        message.contains("error=13", true) ||
                        message.contains("EACCES", true)
                    if (prootFailure) {
                        throw IllegalStateException(
                            "PRoot_EXECUTION_BLOCKED: Android cannot execute the native PRoot binary. " +
                                "No MT5 launch attempted. Diagnostic: $message"
                        )
                    }
                    throw IllegalStateException("Runtime/PRoot preflight failed; MT5 launch blocked: $message")
                }

                val mt5 = try { runtimeManager.mt5Executable() } catch (_: Exception) { null }
                runOnUiThread {
                    setProgress(
                        if (mt5 == null) "Runtime প্রস্তুত; MT5 ইনস্টল করা নেই" else "Runtime প্রস্তুত; MT5 launch ম্যানুয়াল",
                        100
                    )
                    if (mt5 == null) {
                        addLog("Runtime ও PRoot test সফল, কিন্তু Wine prefix-এ MT5 ইনস্টল নেই।")
                        status.text = "অবস্থা: runtime প্রস্তুত; MT5 ইনস্টল করা নেই"
                    } else {
                        addLog("Runtime ও PRoot test সফল। নির্দেশ অনুযায়ী MT5 স্বয়ংক্রিয়ভাবে চালু করা হয়নি।")
                        status.text = "অবস্থা: runtime প্রস্তুত; MT5 ম্যানুয়ালি চালু করুন"
                    }
                }
            } catch (e: Exception) {
                val detail = e.message ?: e.javaClass.simpleName
                runOnUiThread {
                    val prootBlocked = detail.contains("PRoot_EXECUTION_BLOCKED", true)
                    setProgress(if (prootBlocked) "PRoot execution blocked — MT5 চালু করা হয়নি" else "Auto-start ব্যর্থ", null)
                    addLog(if (prootBlocked) "নিরাপত্তার কারণে MT5 launch বন্ধ। $detail" else "নিরাপদ auto-start ব্যর্থ: $detail")
                    status.text = if (prootBlocked) {
                        "অবস্থা: Android PRoot execution blocked — লগ পরীক্ষা করুন"
                    } else {
                        "অবস্থা: runtime প্রস্তুত নয় — লগ পরীক্ষা করে পুনরায় চেষ্টা করুন"
                    }
                }
            } finally {
                safeAutoStartRunning = false
                runOnUiThread { launchMt5Button.isEnabled = true }
            }
        }, "safe-runtime-auto-start").start()
    }

    /**
     * Single-button flow: install the embedded verified runtime if absent; once ready,
     * open the installer picker if MT5 is not installed; otherwise launch MT5.
     */
    private fun launchWindowsOneTap() {
        launchMt5Button.isEnabled = false
        Thread({
            try {
                val check = runtimeManager.preflight()
                if (!check.ready) {
                    runOnUiThread { addLog("Windows চালুর আগে runtime প্রস্তুত করা হচ্ছে…") }
                    val installed = RuntimeBundleInstaller(this).let {
                        val available = assets.list("runtime")?.contains("runtime-bundle.tar.xz") == true
                        if (!available) throw IllegalStateException(
                            "এই APK-তে verified runtime bundle নেই। runtime_run_id সহ All-in-one APK পুনরায় build করতে হবে।"
                        )
                        val archive = java.io.File(cacheDir, "runtime-bundle.tar.xz")
                        val expectedSha256 = assets.open("runtime/runtime-bundle.tar.xz.sha256")
                            .bufferedReader(Charsets.UTF_8).use { it.readText().trim().split(Regex("\\s+")).first() }
                        assets.open("runtime/runtime-bundle.tar.xz").use { input ->
                            archive.outputStream().use { output -> input.copyTo(output) }
                        }
                        try { it.install(archive, expectedSha256) } finally { archive.delete() }
                    }
                    runOnUiThread {
                        addLog("Runtime checksum যাচাই ও private staging সম্পন্ন: ${installed.absolutePath}")
                        refreshRuntimeStatus()
                        addLog("এখন আবার ‘Windows চালু করুন’ চাপুন; MT5 ইনস্টল না থাকলে installer নির্বাচন খুলবে।")
                    }
                    return@Thread
                }

                val mt5 = try { runtimeManager.mt5Executable() } catch (_: Exception) { null }
                if (mt5 == null) {
                    runOnUiThread {
                        addLog("Runtime প্রস্তুত। Windows/MT5 প্রথমবার ইনস্টল করতে MT5 installer (.exe) নির্বাচন করুন।")
                        launchMt5Button.isEnabled = true
                        chooseInstaller()
                    }
                } else {
                    val process = runtimeManager.launchMt5 { line -> runOnUiThread { addLog("Wine: $line") } }
                    runOnUiThread {
                        addLog("MT5 process শুরু হয়েছে; GUI/display compatibility বাস্তব ডিভাইসে যাচাই বাকি।")
                        launchMt5Button.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    addLog("Windows চালু করা যায়নি: ${e.message ?: e.javaClass.simpleName}")
                    refreshRuntimeStatus()
                    launchMt5Button.isEnabled = true
                }
            }
        }, "one-tap-windows-launch").start()
    }

    private fun installBundledRuntime() {
        Thread({
            try {
                val assetPath = "runtime/runtime-bundle.tar.xz"
                val available = assets.list("runtime")?.contains("runtime-bundle.tar.xz") == true
                if (!available) throw IllegalStateException(
                    "এই APK-তে runtime bundle embed করা নেই; verified bundle পাওয়ার পর all-in-one packaging পুনরায় build করতে হবে"
                )
                val archive = java.io.File(cacheDir, "runtime-bundle.tar.xz")
                val expectedSha256 = assets.open("runtime/runtime-bundle.tar.xz.sha256")
                    .bufferedReader(Charsets.UTF_8).use { it.readText().trim().split(Regex("\\s+")).first() }
                assets.open(assetPath).use { input ->
                    archive.outputStream().use { output -> input.copyTo(output) }
                }
                val installed = RuntimeBundleInstaller(this).install(archive, expectedSha256)
                runOnUiThread {
                    addLog("Bundled runtime private storage-এ install হয়েছে: ${installed.absolutePath}")
                    refreshRuntimeStatus()
                }
                archive.delete()
            } catch (e: Exception) {
                runOnUiThread { addLog("All-in-one runtime install ব্যর্থ: ${e.message ?: e.javaClass.simpleName}") }
            }
        }, "bundled-runtime-install").start()
    }

    private fun downloadVerifiedRuntime() {
        launchMt5Button.isEnabled = false
        setProgress("Runtime manifest যাচাই হচ্ছে…", 0)
        addLog("Verified runtime manifest পরীক্ষা ও download শুরু")
        Thread({
            try {
                val installed = RuntimeAutoSetup(this).downloadAndInstall { message ->
                    runOnUiThread {
                        addLog("Runtime setup: $message")
                        val percent = Regex("(?:Download|ডাউনলোড|Extraction|Install):\\s*(\\d{1,3})%").find(message)?.groupValues?.get(1)?.toIntOrNull()
                        setProgress(message, percent)
                    }
                }
                runOnUiThread {
                    setProgress("Runtime install সম্পন্ন", 100)
                    addLog("Runtime install সম্পন্ন: ${installed.absolutePath}")
                    refreshRuntimeStatus()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setProgress("ব্যর্থ: ${e.message ?: e.javaClass.simpleName}", null)
                    addLog("Automatic runtime setup ব্যর্থ: ${e.message ?: e.javaClass.simpleName}")
                }
            } finally {
                runOnUiThread { launchMt5Button.isEnabled = true }
            }
        }, "runtime-auto-setup").start()
    }

    private fun setProgress(message: String, percent: Int?) {
        if (!::progressView.isInitialized) return
        progressView.text = "ডাউনলোড/ইনস্টলেশন: $message"
        progressBar.isIndeterminate = percent == null
        if (percent != null) progressBar.progress = percent.coerceIn(0, 100)
    }

    private fun exportLogsZip() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "mt5-ras-runner-logs.zip")
        }
        startActivityForResult(intent, exportLogsZipRequest)
    }

    private fun chooseRuntimeBundle() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, runtimeBundleRequest)
    }

    private fun chooseInstaller() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }
        startActivityForResult(intent, installerRequest)
    }

    private fun exportLogs() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "mt5-ras-runner-logs.txt")
        }
        startActivityForResult(intent, exportLogsRequest)
    }

    @Deprecated("Retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            installerRequest -> {
                try {
                    val destination = java.io.File(filesDir, "mt5-installer.exe")
                    contentResolver.openInputStream(uri)?.use { input ->
                        destination.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("ইনস্টলার ফাইল পড়া যায়নি")
                    selectedInstaller = destination
                    addLog("ইনস্টলার app-private storage-এ কপি হয়েছে: ${destination.name}")
                    val check = runtimeManager.preflight()
                    if (check.ready) {
                        val process = runtimeManager.launchInstaller(destination) { line -> runOnUiThread { addLog("Wine: $line") } }
                        addLog("Wine installer process শুরু হয়েছে; GUI/display এখনো যাচাই হয়নি")
                    } else {
                        addLog("ইনস্টলার এখনো চালানো হয়নি: ${check.message}")
                    }
                    refreshRuntimeStatus()
                } catch (e: Exception) {
                    addLog("ইনস্টলার প্রস্তুত করা ব্যর্থ: ${e.message}")
                }
            }
            runtimeBundleRequest -> {
                val archive = java.io.File(filesDir, "runtime-bundle-candidate.tar.xz")
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        archive.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("Runtime archive পড়া যায়নি")
                    addLog("Runtime archive কপি হয়েছে; নিরাপদ extraction শুরু")
                    val installed = RuntimeBundleInstaller(this).install(archive)
                    addLog("Runtime bundle app-private storage-এ extract হয়েছে: ${installed.absolutePath}")
                    addLog("নোট: bundle extraction সফল হওয়া Android/Wine/MT5 compatibility প্রমাণ করে না")
                    refreshRuntimeStatus()
                } catch (e: Exception) {
                    addLog("Runtime bundle install ব্যর্থ: ${e.message}")
                } finally {
                    archive.delete()
                }
            }
            exportLogsRequest -> {
                try {
                    contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                        it.write(logs.joinToString("\n"))
                        it.write("\n")
                    } ?: throw IllegalStateException("ফাইল লেখা যায়নি")
                    addLog("ডায়াগনস্টিক লগ TXT এক্সপোর্ট করা হয়েছে")
                } catch (e: Exception) {
                    addLog("লগ এক্সপোর্ট ব্যর্থ: ${e.message}")
                }
            }
            exportLogsZipRequest -> {
                try {
                    val snapshot = logs.joinToString("\n", postfix = "\n")
                    val output = contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("ZIP ফাইল লেখা যায়নি")
                    ZipOutputStream(output).use { zip ->
                        zip.putNextEntry(ZipEntry("diagnostics.log"))
                        zip.write(snapshot.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                    addLog("ডায়াগনস্টিক লগ ZIP এক্সপোর্ট করা হয়েছে")
                } catch (e: Exception) {
                    addLog("ZIP লগ এক্সপোর্ট ব্যর্থ: ${e.message}")
                }
            }
        }
    }


    private fun refreshRuntimeStatus() {
        val check = runtimeManager.preflight()
        val mt5Exists = try { runtimeManager.mt5Executable().isFile } catch (_: Exception) { false }
        status.text = if (check.ready) {
            if (mt5Exists) "অবস্থা: Windows/MT5 চালুর জন্য প্রস্তুত" else "অবস্থা: runtime প্রস্তুত; প্রথমবার Windows ইনস্টল করতে Launch চাপুন"
        } else {
            "অবস্থা: runtime প্রস্তুত নয় — Launch চাপলে স্বয়ংক্রিয়ভাবে runtime ইনস্টল হবে: ${check.message}"
        }
        launchMt5Button.isEnabled = true
        if (!check.ready) addLog("Runtime preflight: ${check.message}")
        else if (!mt5Exists) addLog("Runtime preflight: MT5 terminal.exe এখনো Wine prefix-এ পাওয়া যায়নি")
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun loadSavedLogs() {
        try {
            val file = java.io.File(filesDir, "diagnostics.log")
            if (file.exists()) logs.addAll(file.readLines(Charsets.UTF_8).takeLast(100))
        } catch (_: Exception) { /* Logging must never prevent app startup. */ }
    }

    private fun addLog(message: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        logs.add("[$time] $message")
        while (logs.size > 100) logs.removeAt(0)
        try { java.io.File(filesDir, "diagnostics.log").writeText(logs.joinToString("\n"), Charsets.UTF_8) }
        catch (_: Exception) { /* Keep the UI usable if storage fails. */ }
        if (::logsView.isInitialized) logsView.text = logs.joinToString("\n")
    }

    private fun params() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
