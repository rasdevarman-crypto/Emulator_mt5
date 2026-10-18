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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var logsView: TextView
    private lateinit var keepScreenOn: CheckBox
    private lateinit var launchMt5Button: Button
    private lateinit var runtimeManager: RuntimeManager
    private var selectedInstaller: java.io.File? = null
    private val logs = mutableListOf<String>()
    private val installerRequest = 5105
    private val exportLogsRequest = 5106
    private val preferences by lazy { getSharedPreferences("runner_settings", MODE_PRIVATE) }

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
        root.addView(Button(this).apply {
            text = "MT5 ইনস্টলার নির্বাচন করুন"
            setOnClickListener { chooseInstaller() }
        }, params())
        root.addView(Button(this).apply {
            text = "অফিসিয়াল MT5 ডাউনলোড পেজ খুলুন"
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.metatrader5.com/en/download")))
                    addLog("অফিসিয়াল MT5 ডাউনলোড পেজ খোলা হয়েছে")
                } catch (e: Exception) { addLog("ডাউনলোড পেজ খোলা যায়নি: ${e.message}") }
            }
        }, params())
        root.addView(Button(this).apply {
            text = "রানটাইম পরীক্ষা করুন"
            setOnClickListener { refreshRuntimeStatus() }
        }, params())
        launchMt5Button = Button(this).apply {
            text = "MT5 চালু করুন"
            isEnabled = false
            setOnClickListener {
                try {
                    val process = runtimeManager.launchMt5 { line -> runOnUiThread { addLog("Wine: $line") } }
                    addLog("MT5 process শুরু হয়েছে; GUI/ডিভাইস সামঞ্জস্য এখনো যাচাই হয়নি (PID ${process.pid()})")
                } catch (e: Exception) {
                    addLog("MT5 চালু করা যায়নি: ${e.message}")
                    refreshRuntimeStatus()
                }
            }
        }
        root.addView(launchMt5Button, params())

        root.addView(TextView(this).apply {
            text = "সেটিংস"; textSize = 18f
            setPadding(0, 16, 0, 4); setTextColor(0xFF111827.toInt())
        }, params())
        keepScreenOn = CheckBox(this).apply {
            text = "অ্যাপ খোলা থাকলে স্ক্রিন জাগ্রত রাখুন"
            isChecked = preferences.getBoolean("keep_screen_on", false)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean("keep_screen_on", checked).apply()
                applyKeepScreenOn(checked)
                addLog(if (checked) "স্ক্রিন জাগ্রত রাখার সেটিং চালু" else "স্ক্রিন জাগ্রত রাখার সেটিং বন্ধ")
            }
        }
        root.addView(keepScreenOn, params())
        applyKeepScreenOn(keepScreenOn.isChecked)

        root.addView(TextView(this).apply {
            text = "ডায়াগনস্টিক লগ"; textSize = 18f
            setPadding(0, 16, 0, 8); setTextColor(0xFF111827.toInt())
        }, params())
        root.addView(Button(this).apply {
            text = "লগ ফাইল এক্সপোর্ট করুন"
            setOnClickListener { exportLogs() }
        }, params())
        logsView = TextView(this).apply { textSize = 12f; setTextColor(0xFF374151.toInt()) }
        root.addView(ScrollView(this).apply { addView(logsView) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        addLog("অ্যাপ চালু হয়েছে; প্রাইভেট runtime staging এবং Android-native PRoot এখনো প্রয়োজন।")
        refreshRuntimeStatus()
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
                        addLog("Wine installer process শুরু হয়েছে (PID ${process.pid()}); GUI/display এখনো যাচাই হয়নি")
                    } else {
                        addLog("ইনস্টলার এখনো চালানো হয়নি: ${check.message}")
                    }
                    refreshRuntimeStatus()
                } catch (e: Exception) {
                    addLog("ইনস্টলার প্রস্তুত করা ব্যর্থ: ${e.message}")
                }
            }
            exportLogsRequest -> {
                try {
                    contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                        it.write(logs.joinToString("\n"))
                        it.write("\n")
                    } ?: throw IllegalStateException("ফাইল লেখা যায়নি")
                    addLog("ডায়াগনস্টিক লগ এক্সপোর্ট করা হয়েছে")
                } catch (e: Exception) {
                    addLog("লগ এক্সপোর্ট ব্যর্থ: ${e.message}")
                }
            }
        }
    }


    private fun refreshRuntimeStatus() {
        val check = runtimeManager.preflight()
        status.text = if (check.ready) {
            "অবস্থা: runtime ফাইল পাওয়া গেছে; Android GUI/MT5 যাচাই বাকি"
        } else {
            "অবস্থা: runtime প্রস্তুত নয় — ${check.message}"
        }
        val mt5Exists = try { runtimeManager.mt5Executable().isFile } catch (_: Exception) { false }
        launchMt5Button.isEnabled = check.ready && mt5Exists
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
