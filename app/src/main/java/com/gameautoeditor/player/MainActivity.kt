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

    private val TAG = "GameAuto"
    private val PREFS_NAME = "GameAutoEditor"
    private val API_BOOT_URL = "https://game-auto-editor.vercel.app/api/device-boot"
    // private val API_BOOT_URL = "http://10.0.2.2:5001/api/client-boot" // Local Dev
    
    private var lastUpdateFile: File? = null
    private val REQUEST_CODE_INSTALL_PERMISSION = 999

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

        // Show Controls Button Logic
        findViewById<Button>(R.id.btnShowControls).setOnClickListener {
            val intent = Intent("com.gameautoeditor.SHOW_OVERLAY")
            intent.setPackage(packageName) // Explicit broadcast for security/reliability
            sendBroadcast(intent)
            Toast.makeText(this, "Sending command...", Toast.LENGTH_SHORT).show()
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
        checkInstallerEnvironment()
        bootstrap()
    }

    /**
     * Phase 2: Installer Environment Check
     * 檢查是否由官方 Installer 啟動，並建立數據連結。
     * 目前僅做 Log 記錄，未來將強制檢查。
     */
    private fun checkInstallerEnvironment() {
        Log.i(TAG, "🔍 Checking Installer Environment...")
        
        // 1. Check if Installer Package Exists (Soft Check)
        val installerPkg = "com.gameauto.installer"
        try {
            val info = packageManager.getPackageInfo(installerPkg, 0)
            Log.i(TAG, "✅ Installer found: ${info.versionName} (${info.versionCode})")
            
            // 2. Connect to ContentProvider (IPC Tether)
            val uri = Uri.parse("content://$installerPkg.provider")
            try {
                val cursor = contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val keyIndex = it.getColumnIndex("license_key")
                        if (keyIndex != -1) {
                            val remoteKey = it.getString(keyIndex)
                            if (!remoteKey.isNullOrEmpty()) {
                                Log.i(TAG, "🔗 Tether Successful! License Key retrieved from Installer.")
                                // Save to local prefs
                                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                    .edit().putString("license_key", remoteKey).apply()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Failed to query Installer Provider: ${e.message}")
            }
            
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "⚠️ Installer NOT found! Running in standalone/orphan mode.")
            // Future: Show Alert and finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        
        // Check if returning from "Install Unknown Apps" screen
        if (lastUpdateFile != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.canRequestPackageInstalls()) {
                Log.i(TAG, "Install permission granted in onResume, retrying install...")
                installApk(lastUpdateFile!!)
                lastUpdateFile = null // Clear
            }
        }
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
        // Explicitly wrap context to ensure Material Theme is present for LayoutInflater
        val materialContext = android.view.ContextThemeWrapper(this, R.style.Theme_GameAutoPlayer)
        val inflater = android.view.LayoutInflater.from(materialContext)
        val view = inflater.inflate(R.layout.dialog_license_input, null)
        val input = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputLicenseKey)
        
        // Pre-fill if exists (e.g. for editing)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedKey = prefs.getString("license_key", "")
        if (!savedKey.isNullOrEmpty()) {
            input.setText(savedKey)
        }
        
        AlertDialog.Builder(materialContext)
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
                val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                Log.i(TAG, "Device ID: $deviceId")

                val jsonBody = JSONObject().apply {
                    put("licenseKey", licenseKey)
                    put("currentVersionCode", BuildConfig.VERSION_CODE)
                    put("deviceId", deviceId)
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
        // Setup Progress Dialog
        val progressDialog = android.app.ProgressDialog(this)
        progressDialog.setTitle("Downloading Update")
        progressDialog.setMessage("Please wait...")
        progressDialog.isIndeterminate = false
        progressDialog.max = 100
        progressDialog.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
        progressDialog.setCancelable(false)
        progressDialog.show()
        
        thread {
            try {
                val url = URL(urlStr)
                val connection = url.openConnection() as HttpURLConnection
                connection.connect()

                val fileLength = connection.contentLength
                val hasLength = fileLength > 0

                val file = File(getExternalFilesDir(null), "update.apk")
                if (file.exists()) file.delete()

                val input = connection.inputStream
                val output = file.outputStream()
                
                val data = ByteArray(4096)
                var total: Long = 0
                var count: Int
                
                while (input.read(data).also { count = it } != -1) {
                    total += count.toLong()
                    output.write(data, 0, count)
                    
                    if (hasLength) {
                        val progress = (total * 100 / fileLength).toInt()
                        runOnUiThread {
                            progressDialog.progress = progress
                            progressDialog.setMessage("Downloading: $progress%")
                        }
                    } else {
                        // Indeterminate fallback
                         runOnUiThread {
                             if (!progressDialog.isIndeterminate) {
                                 progressDialog.isIndeterminate = true
                                 progressDialog.setMessage("Downloading...")
                             }
                         }
                    }
                }
                
                output.close()
                input.close()

                runOnUiThread {
                    progressDialog.dismiss()
                    installApk(file)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressDialog.dismiss()
                    setStatus("Update Failed: ${e.message}", false)
                     AlertDialog.Builder(this)
                        .setTitle("Update Failed")
                        .setMessage(e.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun installApk(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                Log.w(TAG, "Requesting install packages permission")
                lastUpdateFile = file // Save for onResume
                
                Toast.makeText(this, "請允許安裝未知來源應用程式以進行更新", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
                return
            }
        }

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
        val btnActivate = findViewById<Button>(R.id.btnActivate)
        val btnShowControls = findViewById<Button>(R.id.btnShowControls)

        if (serviceStatus) {
            statusText.text = "Service Running 🟢"
            statusText.setTextColor(0xFF4CAF50.toInt())
            btnActivate.isEnabled = false
            btnActivate.text = "Service Active"
            btnShowControls.visibility = android.view.View.VISIBLE
        } else {
            statusText.text = "Service Inactive 🔴"
            statusText.setTextColor(0xFFF44336.toInt())
            btnActivate.isEnabled = true
            btnActivate.text = "Enable Service"
            btnShowControls.visibility = android.view.View.GONE
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
