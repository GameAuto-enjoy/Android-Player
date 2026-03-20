package com.gameautoeditor.player

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.net.HttpURLConnection
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val TAG = "GameAuto"
    private val PREFS_NAME = "GameAutoEditor"
    private val API_BOOT_URL = "https://game-auto-ai.vercel.app/api/device-boot"
    
    private var lastUpdateFile: File? = null
    private val REQUEST_CODE_INSTALL_PERMISSION = 999
    
    // Dialog reference to dismiss on successful login
    private var licenseDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // UI Init - Traditional Chinese
        findViewById<TextView>(R.id.textVersion).text = "版本 v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        
        findViewById<Button>(R.id.btnChangeLicense).setOnClickListener {
            showLicenseDialog()
        }

        findViewById<Button>(R.id.btnActivate).setOnClickListener {
            val serviceEnabled = isAccessibilityServiceEnabled(AutomationService::class.java)
            if (!serviceEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Show Guidance for Android 13+ only if service is NOT enabled
                showRestrictedSettingsGuide()
            } else {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }
        
        // Show Controls Button Logic
        findViewById<Button>(R.id.btnShowControls).setOnClickListener {
            val intent = Intent("com.gameautoeditor.SHOW_OVERLAY")
            intent.setPackage(packageName) // Explicit broadcast for security/reliability
            sendBroadcast(intent)
            Toast.makeText(this, getString(R.string.opening_dashboard), Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnTaskConfig).setOnClickListener {
            showTaskConfigDialog()
        }

        findViewById<Button>(R.id.btnSelectScript).setOnClickListener {
            val token = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString("user_token", null)
            if (!token.isNullOrEmpty()) {
                fetchUserScripts(token)
            }
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
        if (checkInstallerEnvironment()) {
            bootstrap()
        }

        // Handle Deep Link (Cold Start)
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data
        if (data != null && data.scheme == "gameauto" && data.host == "auth_callback") {
            val accessToken = data.getQueryParameter("access_token")
            val refreshToken = data.getQueryParameter("refresh_token")
            val email = data.getQueryParameter("email")
            
            if (!accessToken.isNullOrEmpty()) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString("user_token", accessToken)
                    .putString("refresh_token", refreshToken)
                    .putString("user_email", email)
                    .apply()
                    
                Toast.makeText(this, "登入成功: $email", Toast.LENGTH_LONG).show()
                
                // Close dialog if open
                licenseDialog?.dismiss()
                licenseDialog = null
                
                // Refresh UI or Bootstrap
                bootstrap()
            }
        }
    }

    /**
     * Phase 2: Installer Environment Check
     * 檢查是否由官方 Installer 啟動，並建立數據連結。
     * @return true if environment is safe, false if blocked.
     */
    private fun checkInstallerEnvironment(): Boolean {
        Log.i(TAG, "🔍 Checking Installer Environment...")
        
        val installerPkg = "com.gameauto.installer"
        try {
            val info = packageManager.getPackageInfo(installerPkg, 0)
            Log.i(TAG, "✅ Installer found: ${info.versionName} (${info.versionCode})")
            
            val uri = Uri.parse("content://$installerPkg.provider")
            try {
                val cursor = contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val keyIndex = it.getColumnIndex("license_key")
                        val idIndex = it.getColumnIndex("device_id")
                        
                        if (keyIndex != -1) {
                            val remoteKey = it.getString(keyIndex)
                            var remoteId: String? = null
                            
                            if (idIndex != -1) {
                                remoteId = it.getString(idIndex)
                            }
                            
                            var inherited = false
                            val editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()

                            if (!remoteKey.isNullOrEmpty()) {
                                editor.putString("license_key", remoteKey)
                                inherited = true
                            }
                            
                            if (!remoteId.isNullOrEmpty()) {
                                editor.putString("device_id", remoteId)
                                inherited = true
                            }

                            if (inherited) {
                                Log.i(TAG, "🔗 Tether Successful! Key: ${remoteKey?.take(4)}***, ID: $remoteId")
                                editor.apply()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Failed to query Installer Provider: ${e.message}")
            }
            
            
            return true // Environment OK
            
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "⚠️ Installer NOT found! Execution Blocked.")
            // CRITICAL: Block Execution if Installer is missing
            AlertDialog.Builder(this)
                .setTitle(R.string.security_error_title)
                .setMessage(getString(R.string.installer_missing_message))
                .setPositiveButton(R.string.btn_close) { _, _ -> 
                    finishAffinity() // Force Close App
                    System.exit(0)
                }
                .setCancelable(false)
                .show()
            
            return false // Environment Failed
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
        val userToken = prefs.getString("user_token", null)
        val licenseKey = prefs.getString("license_key", null)

        if (!userToken.isNullOrEmpty()) {
            // User Mode
            setStatus(getString(R.string.loading_user_data), true)
            licenseDialog?.dismiss()
            setupUserModeUI()
            performBootCheck(null, userToken)
        } else if (!licenseKey.isNullOrEmpty()) {
            // License Key Mode
            setStatus(getString(R.string.verifying_key), true)
            licenseDialog?.dismiss()
            setupLicenseModeUI()
            performBootCheck(licenseKey, null)
        } else {
            // Not logged in
            setupGuestUI()
            showLicenseDialog()
        }
    }

    private fun setupUserModeUI() {
        val btnSelect = findViewById<Button>(R.id.btnSelectScript)
        val btnChange = findViewById<Button>(R.id.btnChangeLicense)
        val btnTask = findViewById<Button>(R.id.btnTaskConfig)
        
        runOnUiThread {
            btnSelect.visibility = android.view.View.VISIBLE
            btnChange.text = getString(R.string.btn_switch_account)
            btnTask.visibility = android.view.View.VISIBLE
        }
    }

    private fun setupLicenseModeUI() {
        val btnSelect = findViewById<Button>(R.id.btnSelectScript)
        val btnChange = findViewById<Button>(R.id.btnChangeLicense)
        
        runOnUiThread {
            btnSelect.visibility = android.view.View.GONE
            btnChange.text = getString(R.string.btn_change_key)
        }
    }

    private fun setupGuestUI() {
        val btnSelect = findViewById<Button>(R.id.btnSelectScript)
        val btnChange = findViewById<Button>(R.id.btnChangeLicense)
        val textLicense = findViewById<TextView>(R.id.textLicenseStatus)
        val textExpiry = findViewById<TextView>(R.id.textExpiry)
        
        runOnUiThread {
            btnSelect.visibility = android.view.View.GONE
            btnChange.text = getString(R.string.btn_login_guest)
            textLicense.text = getString(R.string.guest_label)
            textLicense.setTextColor(0xFF9CA3AF.toInt()) // Gray-400
            textExpiry.text = getString(R.string.please_login)
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
        
        val builder = AlertDialog.Builder(materialContext)
            .setTitle(R.string.dialog_enter_license_title)
            .setView(view)
            .setPositiveButton(R.string.btn_activate) { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) {
                    prefs.edit()
                         .putString("license_key", key)
                         .remove("user_token") // MUST clear stale Google Login token
                         .apply()
                    bootstrap()
                }
            }

        // Web Login
        builder.setNeutralButton("Google 登入") { _, _ ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://game-auto-ai.vercel.app/login?platform=android&force_relogin=true"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            // Note: Dialog will remain open until onNewIntent dismisses it
        }

        licenseDialog = builder.setCancelable(false).show()
    }

    // Android 13+ Restricted Settings Guide - User Preferred Style
    private fun showRestrictedSettingsGuide() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_android13_title))
            .setMessage(getString(R.string.dialog_android13_message))
            .setPositiveButton(getString(R.string.btn_go_accessibility)) { _, _ ->
                 val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                 startActivity(intent)
            }
            .show()
    }

    private fun performBootCheck(licenseKey: String?, userToken: String?) {
        thread {
            try {
                // Priority: Saved Stable ID (from Installer) > Local Volatile ID
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val savedId = prefs.getString("device_id", null)
                val systemId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                
                val deviceId = if (!savedId.isNullOrEmpty()) {
                    Log.i(TAG, "Using Stable Device ID from Installer/Prefs: $savedId")
                    savedId
                } else {
                    val fallbackId = systemId ?: java.util.UUID.randomUUID().toString()
                    Log.i(TAG, "Using Local Volatile Device ID, saving to Prefs: $fallbackId")
                    prefs.edit().putString("device_id", fallbackId).apply()
                    fallbackId
                }

                val jsonBody = JSONObject().apply {
                    if (licenseKey != null) put("licenseKey", licenseKey)
                    put("currentVersionCode", BuildConfig.VERSION_CODE)
                    put("deviceId", deviceId)
                }

                val conn = URL(API_BOOT_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                if (userToken != null) {
                    conn.setRequestProperty("Authorization", "Bearer $userToken")
                }

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
                        val scriptObj = json.optJSONObject("script")
                        var scriptUrl: String? = null
                        var scriptName: String? = null

                        if (scriptObj != null) {
                            if (!scriptObj.isNull("url")) scriptUrl = scriptObj.optString("url")
                            if (!scriptObj.isNull("name")) scriptName = scriptObj.optString("name")
                        }
                        
                        // Handle "null" string edge case from legacy or API quirks
                        if (scriptUrl == "null") scriptUrl = null
                        
                        if (scriptUrl.isNullOrEmpty()) {
                             // Fallback to locally selected script ID if User Mode
                             val localId = prefs.getString("script_id", null)
                             // Ensure localId is not literally "null"
                             if (!localId.isNullOrEmpty() && localId != "null" && userToken != null) {
                                 scriptUrl = localId
                                 scriptUrl = localId
                                 scriptName = prefs.getString("script_name", getString(R.string.script_selected))
                             }
                        }
                        
                        if (!scriptUrl.isNullOrEmpty()) {
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                .edit().putString("script_id", scriptUrl).apply()
                        } else {
                            scriptName = getString(R.string.script_not_selected)
                        }

                        val license = json.optJSONObject("license")
                        val expiry = license?.optString("expiry", "會員制") ?: "會員制"
                        
                        // Extract Subscription Plan
                        val features = json.optJSONObject("features")
                        val userPlan = features?.optString("plan", "free") ?: "free"
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit().putString("user_plan", userPlan).apply()

                        runOnUiThread {
                            setStatus("已連線", false)
                            findViewById<TextView>(R.id.textCurrentScript).text = "任務: $scriptName"
                            findViewById<TextView>(R.id.textLicenseStatus).text = getString(R.string.license_valid)
                            findViewById<TextView>(R.id.textLicenseStatus).setTextColor(0xFF10B981.toInt()) // Emerald-500
                            findViewById<TextView>(R.id.textExpiry).text = "方案: $expiry"

                            // Notify Service to Wake Up Fleet
                            val fleetIntent = Intent("com.gameautoeditor.INIT_FLEET")
                            fleetIntent.setPackage(packageName)
                            sendBroadcast(fleetIntent)
                        }

                    } else {
                         val msg = json.optString("message", getString(R.string.verify_failed))
                         runOnUiThread {
                             setStatus(getString(R.string.verify_failed), false)
                             Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                             if (userToken != null) {
                                 // Token invalid?
                                 setupLicenseModeUI() // Reset to clean state?
                                 showLicenseDialog()
                             } else {
                                 showLicenseDialog()
                             }
                         }
                    }
                } else {
                    runOnUiThread {
                        setStatus("伺服器錯誤 ${conn.responseCode}", false)
                        Toast.makeText(this, getString(R.string.connection_error), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setStatus(getString(R.string.error_format, e.message), false)
                    Log.e(TAG, "Boot error", e)
                }
            }
        }
    }

    private fun fetchUserScripts(token: String) {
        val pd = android.app.ProgressDialog(this)
        pd.setMessage(getString(R.string.loading_script_list))
        pd.show()

        thread {
            try {
                val url = URL("https://game-auto-ai.vercel.app/api/scripts?type=private")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "Bearer $token")
                
                if (conn.responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(resp)
                    val scripts = json.optJSONArray("scripts") ?: JSONArray()
                    
                    runOnUiThread {
                        pd.dismiss()
                        showScriptSelectionDialog(scripts)
                    }
                } else {
                    throw Exception("HTTP ${conn.responseCode}")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    pd.dismiss()
                    Toast.makeText(this, getString(R.string.load_failed_format, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showScriptSelectionDialog(scripts: JSONArray) {
        if (scripts.length() == 0) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.no_script_title))
                .setMessage(getString(R.string.no_script_message))
                .setPositiveButton(R.string.btn_ok, null)
                .show()
            return
        }

        if (scripts.length() == 1) {
            val s = scripts.getJSONObject(0)
            val selectedId = s.getString("id")
            val game = s.optString("game_name", "未知遊戲")
            val name = s.optString("script_name", "未命名腳本")
            val selectedName = "$game - $name"
            
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString("script_id", selectedId)
                .putString("script_name", selectedName)
                .apply()
                
            Toast.makeText(this, "已自動載入唯一腳本: $selectedName", Toast.LENGTH_SHORT).show()
            findViewById<TextView>(R.id.textCurrentScript).text = "任務: $selectedName"
            
            val token = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString("user_token", null)
            performBootCheck(null, token)
            return
        }

        val titles = Array(scripts.length()) { "" }
        val ids = Array(scripts.length()) { "" }

        for (i in 0 until scripts.length()) {
            val s = scripts.getJSONObject(i)
            val game = s.optString("game_name", "未知遊戲")
            val name = s.optString("script_name", "未命名腳本")
            titles[i] = "$game - $name"
            ids[i] = s.getString("id")
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.choose_script_title))
            .setItems(titles) { _, which ->
                val selectedId = ids[which]
                val selectedName = titles[which]
                
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString("script_id", selectedId)
                    .putString("script_name", selectedName)
                    .commit()
                
                Toast.makeText(this, "已選擇: $selectedName", Toast.LENGTH_SHORT).show()
                
                // Refresh Status
                findViewById<TextView>(R.id.textCurrentScript).text = "任務: $selectedName"
                
                // Trigger boot check to validate everything
                val token = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString("user_token", null)
                performBootCheck(null, token)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showUpdateDialog(version: String, url: String) {
        val installerPkg = "com.gameauto.installer"
        val hasInstaller = try {
            packageManager.getPackageInfo(installerPkg, 0)
            true
        } catch (e: Exception) { false }

        val builder = AlertDialog.Builder(this)
            .setTitle("發現新版本 ($version)")
            .setCancelable(false)
            
        if (hasInstaller) {
            builder.setMessage(getString(R.string.update_message_has_installer))
            builder.setPositiveButton("開啟 Installer") { _, _ ->
                val intent = packageManager.getLaunchIntentForPackage(installerPkg)
                if (intent != null) {
                    startActivity(intent)
                } else {
                    Toast.makeText(this, getString(R.string.launch_installer_failed), Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            builder.setMessage("新版本 $version 已發布。\n檢測到您未安裝 Installer，請手動下載更新。")
            builder.setPositiveButton(R.string.btn_download_apk) { _, _ ->
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(browserIntent)
            }
        }
        builder.show()
    }

    private fun downloadAndInstallApk(urlStr: String) {
        // Setup Progress Dialog - Translated
        val progressDialog = android.app.ProgressDialog(this)
        progressDialog.setTitle(R.string.downloading_update_title)
        progressDialog.setMessage(getString(R.string.please_wait_message))
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
                            progressDialog.setMessage(getString(R.string.downloading_format, progress))
                        }
                    } else {
                        // Indeterminate fallback
                         runOnUiThread {
                             if (!progressDialog.isIndeterminate) {
                                 progressDialog.isIndeterminate = true
                                 progressDialog.setMessage(getString(R.string.downloading_indeterminate))
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
                    setStatus(getString(R.string.update_failed_format, e.message), false)
                     AlertDialog.Builder(this)
                        .setTitle(R.string.update_failed_title)
                        .setMessage(e.message)
                        .setPositiveButton(R.string.btn_ok, null)
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
                
                Toast.makeText(this, getString(R.string.allow_install_unknown), Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, getString(R.string.install_failed_format, e.message), Toast.LENGTH_LONG).show()
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
            statusText.text = getString(R.string.status_active)
            statusText.setTextColor(0xFF10B981.toInt()) // Emerald-500
            btnActivate.isEnabled = false
            btnActivate.text = getString(R.string.service_enabled)
            btnShowControls.visibility = android.view.View.VISIBLE
        } else {
            statusText.text = getString(R.string.status_inactive)
            statusText.setTextColor(0xFFEF4444.toInt()) // Red-500
            btnActivate.isEnabled = true
            btnActivate.text = "啟用無障礙服務"
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
                Toast.makeText(this, getString(R.string.allow_overlay_permission), Toast.LENGTH_LONG).show()
            }
        }
    }

    // --- Task Config Logic ---

    data class TaskItem(
        val label: String,
        var enabled: Boolean,
        var priority: Int,
        val jsonRegions: MutableList<JSONObject>
    )

    inner class TaskAdapter(private val items: List<TaskItem>) : 
        RecyclerView.Adapter<TaskAdapter.VH>() {
            
        inner class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
            val checkBox = v.findViewById<android.widget.CheckBox>(R.id.chkTask)
            val dragHandle = v.findViewById<android.view.View>(R.id.imgDragHandle)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_task_config, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.checkBox.text = item.label
            holder.checkBox.isChecked = item.enabled
            holder.checkBox.setOnCheckedChangeListener { _, isChecked -> 
                item.enabled = isChecked
            }
        }
        
        override fun getItemCount(): Int = items.size
    }

    private fun showTaskConfigDialog() {
        val file = File(filesDir, "cached_script.json")
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val scriptId = prefs.getString("script_id", null)
        if (!scriptId.isNullOrEmpty()) {
             // Always force download fresh to reflect latest web edits
             downloadScriptForConfig(scriptId, file)
        } else {
             if (file.exists()) {
                 openTaskConfigDialog(file)
             } else {
                 Toast.makeText(this, getString(R.string.no_script_assigned), Toast.LENGTH_SHORT).show()
             }
        }
    }

    private fun downloadScriptForConfig(scriptId: String, destFile: File) {
         val pd = android.app.ProgressDialog(this)
         pd.setMessage(getString(R.string.downloading_config))
         pd.setCancelable(false)
         pd.show()
         
         thread {
             try {
                val urlString = if (scriptId.startsWith("http")) {
                    scriptId
                } else {
                    "https://game-auto-ai.vercel.app/api/get-script?id=$scriptId"
                }

                var conn = URL(urlString).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = false
                
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val token = prefs.getString("user_token", null)
                if (!token.isNullOrEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer $token")
                }

                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                
                var responseCode = conn.responseCode
                
                if (responseCode == java.net.HttpURLConnection.HTTP_MOVED_TEMP || responseCode == java.net.HttpURLConnection.HTTP_MOVED_PERM) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location != null) {
                        conn = URL(location).openConnection() as HttpURLConnection
                        conn.connectTimeout = 10000
                        conn.readTimeout = 10000
                        responseCode = conn.responseCode
                    }
                }

                if (responseCode == 200) {
                    val json = conn.inputStream.bufferedReader().readText()
                    destFile.writeText(json)
                    runOnUiThread {
                        pd.dismiss()
                        openTaskConfigDialog(destFile)
                    }
                } else {
                    throw Exception("HTTP $responseCode")
                }
             } catch (e: Exception) {
                 runOnUiThread {
                     pd.dismiss()
                     Toast.makeText(this, getString(R.string.config_download_failed, e.message), Toast.LENGTH_SHORT).show()
                     // Fallback to local cache if download fails
                     if (destFile.exists()) {
                         openTaskConfigDialog(destFile)
                     }
                 }
             }
         }
    }

    private fun openTaskConfigDialog(file: File) {
        try {
            val jsonStr = file.readText()
            val graphData = JSONObject(jsonStr)
            val nodes = graphData.optJSONArray("nodes") ?: JSONArray()
            val taskMap = linkedMapOf<String, TaskItem>()
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            
            // 1. Find Root Node (Main Scene)
            var rootNode: JSONObject? = null
            
            // Priority 1: Explicit "isRoot" flag
            for (i in 0 until nodes.length()) {
                val node = nodes.getJSONObject(i)
                if (node.optJSONObject("data")?.optBoolean("isRoot") == true) {
                    rootNode = node
                    break
                }
            }
            
            // Priority 2: ID "start" or "root"
            if (rootNode == null) {
                for (i in 0 until nodes.length()) {
                    val node = nodes.getJSONObject(i)
                    val id = node.getString("id").lowercase()
                    if (id == "start" || id == "root" || id == "node-1") {
                        rootNode = node
                        break
                    }
                }
            }
            
            // Priority 3: First node
            if (rootNode == null && nodes.length() > 0) {
                rootNode = nodes.getJSONObject(0)
            }
            
            if (rootNode != null) {
                val regions = rootNode.optJSONObject("data")?.optJSONArray("regions")
                if (regions != null) {
                    for (j in 0 until regions.length()) {
                        val r = regions.getJSONObject(j)
                        val label = r.optString("label")
                        // Filter Logic:
                        // 1. Must have label
                        // 2. Ignore "New Button" (Default)
                        // 3. Ignore Edge logic
                        // 4. Ignore "CHECK_EXIT" (Pure logic transitions)? No, sometimes users want to disable these.
                        
                        if (!label.isNullOrEmpty() && label != "New Button" && !label.startsWith("Edge")) {
                            if (!taskMap.containsKey(label)) {
                                var isEnabled = r.optBoolean("enabled", true)
                                val varKey = "enable_$label"
                                if (prefs.contains(varKey)) isEnabled = (prefs.getInt(varKey, 1) == 1)
                                
                                val schedule = r.optJSONObject("schedule")
                                val priority = schedule?.optInt("priority", 100) ?: 100
                                taskMap[label] = TaskItem(label, isEnabled, priority, mutableListOf())
                            }
                            taskMap[label]?.jsonRegions?.add(r)
                        }
                    }
                }
            }
            
            if (taskMap.isEmpty()) {
                Toast.makeText(this, getString(R.string.no_configurable_task), Toast.LENGTH_SHORT).show()
                return
            }
            
            val taskList = taskMap.values.sortedBy { it.priority }.toMutableList()
            // Ensure context is Activity context for Theme
            val dialogView = layoutInflater.inflate(R.layout.dialog_task_config, null)
            val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerViewTasks)
            val adapter = TaskAdapter(taskList)
            recyclerView.layoutManager = LinearLayoutManager(this)
            recyclerView.adapter = adapter
            
            val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                    val from = vh.adapterPosition
                    val to = target.adapterPosition
                    java.util.Collections.swap(taskList, from, to)
                    adapter.notifyItemMoved(from, to)
                    return true
                }
                override fun onSwiped(vh: RecyclerView.ViewHolder, d: Int) {}
                override fun isLongPressDragEnabled(): Boolean = true
            })
            itemTouchHelper.attachToRecyclerView(recyclerView)
            
            AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton(R.string.btn_save) { _, _ ->
                     val editor = prefs.edit()
                     taskList.forEachIndexed { index, task ->
                         val varKey = "enable_${task.label}"
                         editor.putInt(varKey, if (task.enabled) 1 else 0)
                         val newPriority = index + 1
                         for (r in task.jsonRegions) {
                             r.put("enabled", task.enabled)
                             var schedule = r.optJSONObject("schedule")
                             if (schedule == null) {
                                 schedule = JSONObject()
                                 r.put("schedule", schedule)
                             }
                             schedule.put("priority", newPriority)
                         }
                     }
                     editor.apply()
                     file.writeText(graphData.toString())
                     Toast.makeText(this, getString(R.string.config_updated), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Config Error", e)
            Toast.makeText(this, getString(R.string.read_failed), Toast.LENGTH_SHORT).show()
        }
    }
}
