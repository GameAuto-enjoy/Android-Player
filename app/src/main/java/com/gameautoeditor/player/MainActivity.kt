package com.gameautoeditor.player

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private val PREFS_NAME = "GameAutoEditor"
    private val API_BOOT_URL = "https://game-auto-editor.vercel.app/api/client-boot"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // UI Init
        findViewById<TextView>(R.id.textVersion).text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        
        findViewById<Button>(R.id.btnChangeLicense).setOnClickListener {
            showLicenseDialog()
        }

        findViewById<Button>(R.id.btnActivate).setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        checkOverlayPermission()
        
        try {
            if (!org.opencv.android.OpenCVLoader.initDebug()) {
                Log.e(TAG, "OpenCV init failed in Activity")
            }
        } catch (e: Exception) {
             Log.e(TAG, "OpenCV init error", e)
        }

        // Start Boot Process
        bootstrap()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun bootstrap() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val licenseKey = prefs.getString("license_key", null)

        if (licenseKey.isNullOrEmpty()) {
            showLicenseDialog()
        } else {
            setStatus("Connecting to server...", true)
            performBootCheck(licenseKey)
        }
    }

    private fun showLicenseDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_license_input, null)
        val input = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputLicenseKey)
        
        // Pre-fill if exists (e.g. for editing)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedKey = prefs.getString("license_key", "")
        if (!savedKey.isNullOrEmpty()) {
            input.setText(savedKey)
        }
        
        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Activate") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) {
                    prefs.edit().putString("license_key", key).apply()
                    // If service not running, warn but allow? 
                    // Bootstrap will handle check
                    bootstrap()
                }
            }
            .setCancelable(false) // Force input
            .show()
    }

    private fun performBootCheck(licenseKey: String) {
        thread {
            try {
                val jsonBody = JSONObject().apply {
                    put("licenseKey", licenseKey)
                    put("currentVersionCode", BuildConfig.VERSION_CODE)
                }

                val conn = URL(API_BOOT_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(jsonBody.toString().toByteArray()) }

                if (conn.responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(resp)
                    
                    if (json.optBoolean("authorized")) {
                        // 1. Check Update
                        val update = json.optJSONObject("update")
                        if (update != null) {
                            val dlUrl = update.getString("downloadUrl")
                            val version = update.getString("versionName")
                            runOnUiThread {
                                showUpdateDialog(version, dlUrl)
                            }
                            return@thread
                        }

                        // 2. Set Script
                        val script = json.optJSONObject("script")
                        if (script != null) {
                            val scriptUrl = script.getString("url")
                            val scriptName = script.getString("name")
                            
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                .edit().putString("script_id", scriptUrl).apply()

                            val license = json.optJSONObject("license")
                            val expiry = license?.optString("expiry", "Unknown") ?: "Unknown"

                            runOnUiThread {
                                setStatus("Active", false)
                                findViewById<TextView>(R.id.textCurrentScript).text = "Task: $scriptName"
                                findViewById<TextView>(R.id.textLicenseStatus).text = "Active ✅"
                                findViewById<TextView>(R.id.textLicenseStatus).setTextColor(0xFF4CAF50.toInt())
                                findViewById<TextView>(R.id.textExpiry).text = "Valid until: $expiry"
                            }
                        }

                    } else {
                         val msg = json.optString("message", "Authorization failed")
                         runOnUiThread {
                             setStatus("Auth Failed", false)
                             Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                             showLicenseDialog() // Retry
                         }
                    }
                } else {
                    runOnUiThread {
                        setStatus("Server Error ${conn.responseCode}", false)
                        Toast.makeText(this, "Connection Error", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setStatus("Error: ${e.message}", false)
                    Log.e(TAG, "Boot error", e)
                }
            }
        }
    }

    private fun showUpdateDialog(version: String, url: String) {
        AlertDialog.Builder(this)
            .setTitle("New Version Available")
            .setMessage("Version $version is ready to download.")
            .setPositiveButton("Update Now") { _, _ ->
                downloadAndInstallApk(url)
            }
            .setCancelable(false)
            .show()
    }

    private fun downloadAndInstallApk(urlStr: String) {
        setStatus("Downloading Update...", true)
        
        thread {
            try {
                val url = URL(urlStr)
                val connection = url.openConnection() as HttpURLConnection
                connection.connect()

                val file = File(getExternalFilesDir(null), "update.apk")
                if (file.exists()) file.delete()

                val input = connection.inputStream
                val output = file.outputStream()
                
                input.copyTo(output)
                output.close()
                input.close()

                runOnUiThread {
                    installApk(file)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setStatus("Update Failed: ${e.message}", false)
                }
            }
        }
    }

    private fun installApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Install Failed: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Install error", e)
        }
    }

    private fun setStatus(msg: String, loading: Boolean) {
        findViewById<TextView>(R.id.textCurrentScript).text = msg
        val pb = findViewById<ProgressBar>(R.id.progressBar)
        pb.visibility = if (loading) android.view.View.VISIBLE else android.view.View.INVISIBLE
    }

    private fun updateServiceStatus() {
        val serviceStatus = isAccessibilityServiceEnabled(AutomationService::class.java)
        val statusText = findViewById<TextView>(R.id.textServiceStatus)
        val btn = findViewById<Button>(R.id.btnActivate)

        if (serviceStatus) {
            statusText.text = "Service Running 🟢"
            statusText.setTextColor(0xFF4CAF50.toInt())
            btn.isEnabled = false
            btn.text = "Service Active"
        } else {
            statusText.text = "Service Inactive 🔴"
            statusText.setTextColor(0xFFF44336.toInt())
            btn.isEnabled = true
            btn.text = "Enable Service"
        }
    }

    private fun isAccessibilityServiceEnabled(service: Class<out android.accessibilityservice.AccessibilityService>): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (enabledService in enabledServices) {
            val serviceInfo = enabledService.resolveInfo.serviceInfo
            if (serviceInfo.packageName == packageName && serviceInfo.name == service.name) {
                return true
            }
        }
        return false
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, 1234)
                Toast.makeText(this, "Permission required for floating controls", Toast.LENGTH_LONG).show()
            }
        }
    }
}
