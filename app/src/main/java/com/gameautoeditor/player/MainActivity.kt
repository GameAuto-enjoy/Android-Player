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
    private val API_BASE_URL: String
        get() = BuildConfig.AI_API_URL.trimEnd('/')
    private val API_BOOT_URL: String
        get() = "$API_BASE_URL/api/device-boot"

    private var lastUpdateFile: File? = null
    private val REQUEST_CODE_INSTALL_PERMISSION = 999

    // Dialog reference to dismiss on successful login
    private var licenseDialog: AlertDialog? = null
    private var installerVerified: Boolean = false

    private companion object {
        private const val OAUTH_PENDING_WINDOW_MS = 10 * 60 * 1000L
        private val JWT_PATTERN = Regex("^[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}$")
    }

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
        
        // Show Controls Button Logic (Step 3 Wizard CTA)
        findViewById<Button>(R.id.btnShowControls).setOnClickListener {
            val serviceEnabled = isAccessibilityServiceEnabled(AutomationService::class.java)
            if (!serviceEnabled) {
                Toast.makeText(this, "請先完成 STEP 1：開啟無障礙服務權限", Toast.LENGTH_LONG).show()
                findViewById<Button>(R.id.btnActivate).performClick()
                return@setOnClickListener
            }
            val intent = Intent("com.gameautoeditor.SHOW_OVERLAY")
            intent.setPackage(packageName) // Explicit broadcast for security/reliability
            sendBroadcast(intent)
            Toast.makeText(this, "✅ 懸浮控制球已掛載！請切換至遊戲畫面", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnTaskConfig).setOnClickListener {
            showTaskConfigDialog()
        }

        findViewById<Button>(R.id.btnSelectScript).setOnClickListener {
            val token = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString("user_token", null)
            if (!token.isNullOrEmpty()) {
                fetchUserScripts(token)
            } else {
                showLicenseDialog()
            }
        }

        // Quick Loop Target Stepper (0 = 無限, otherwise step by 5)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val textLoopVal = findViewById<TextView>(R.id.textLoopTargetValue)
        fun refreshLoopText() {
            val loops = prefs.getInt("custom_max_loops", 0)
            textLoopVal?.text = if (loops <= 0) "無限" else "${loops}回"
        }
        refreshLoopText()

        findViewById<Button>(R.id.btnLoopMinus)?.setOnClickListener {
            val current = prefs.getInt("custom_max_loops", 0)
            val next = if (current <= 5) 0 else current - 5
            prefs.edit().putInt("custom_max_loops", next).apply()
            refreshLoopText()
            val updateIntent = Intent("com.gameautoeditor.UPDATE_RUNTIME_VARS")
            updateIntent.setPackage(packageName)
            updateIntent.putExtra("custom_max_loops", next)
            sendBroadcast(updateIntent)
        }

        findViewById<Button>(R.id.btnLoopPlus)?.setOnClickListener {
            val current = prefs.getInt("custom_max_loops", 0)
            val next = current + 5
            prefs.edit().putInt("custom_max_loops", next).apply()
            refreshLoopText()
            val updateIntent = Intent("com.gameautoeditor.UPDATE_RUNTIME_VARS")
            updateIntent.setPackage(packageName)
            updateIntent.putExtra("custom_max_loops", next)
            sendBroadcast(updateIntent)
        }

        checkOverlayPermission()
        
        try {
            if (!org.opencv.android.OpenCVLoader.initDebug()) {
                Log.e(TAG, "OpenCV init failed in Activity")
            }
        } catch (t: Throwable) {
             Log.e(TAG, "OpenCV init error", t)
        }

        // Start Boot Process (Installer environment MUST be verified before deep-link or boot)
        installerVerified = checkInstallerEnvironment()
        if (installerVerified) {
            if (!handleDeepLink(intent)) {
                bootstrap()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (installerVerified) {
            handleDeepLink(intent)
        }
    }

    override fun onDestroy() {
        try {
            licenseDialog?.dismiss()
        } catch (_: Exception) {}
        licenseDialog = null
        super.onDestroy()
    }

    private fun handleDeepLink(intent: Intent?): Boolean {
        if (!installerVerified) return false
        if (intent?.action != Intent.ACTION_VIEW) return false
        val data = intent.data ?: return false
        if (data.scheme != "gameauto" || data.host != "auth_callback") return false

        // Clear intent data immediately to prevent replay on Activity recreation
        intent.data = null

        val accessToken = data.getQueryParameter("access_token")?.trim()
        val refreshToken = data.getQueryParameter("refresh_token")?.trim()
        val rawEmail = data.getQueryParameter("email")?.trim()

        if (accessToken.isNullOrEmpty() || accessToken.length > 4096 || !JWT_PATTERN.matches(accessToken)) {
            Log.w(TAG, "⚠️ Rejected malformed OAuth access_token in deep link")
            return false
        }
        if (!refreshToken.isNullOrEmpty() && (refreshToken.length > 512 || refreshToken.any { it.isWhitespace() || it.code < 32 })) {
            Log.w(TAG, "⚠️ Rejected malformed OAuth refresh_token in deep link")
            return false
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val oauthPendingTs = prefs.getLong("oauth_pending_ts", 0L)
        val elapsed = System.currentTimeMillis() - oauthPendingTs
        val hasRecentOAuth = oauthPendingTs > 0L && elapsed in 0L..OAUTH_PENDING_WINDOW_MS
        val alreadyAuthenticated = !prefs.getString("user_token", null).isNullOrEmpty() ||
            !prefs.getString("license_key", null).isNullOrEmpty()

        if (alreadyAuthenticated && !hasRecentOAuth) {
            Log.w(TAG, "⚠️ Rejected unsolicited OAuth callback on already-authenticated device")
            return false
        }

        val cleanEmail = rawEmail
            ?.replace(Regex("[\\r\\n\\u0000-\\u001F\\u007F]"), "")
            ?.take(254)
            ?: ""

        val editor = prefs.edit()
            .remove("oauth_pending_ts")
            .remove("license_key") // Clear stale commercial key when switching to User Mode
            .remove("user_id")     // Clear stale cross-account session & script state
            .remove("script_id")
            .remove("script_name")
            .remove("user_plan")
            .putString("user_token", accessToken)
        if (!refreshToken.isNullOrEmpty()) {
            editor.putString("refresh_token", refreshToken)
        } else {
            editor.remove("refresh_token")
        }
        if (cleanEmail.isNotEmpty()) {
            editor.putString("user_email", cleanEmail)
        } else {
            editor.remove("user_email")
        }
        editor.apply()
        try { File(filesDir, "cached_script.json").delete() } catch (_: Exception) {}

        Toast.makeText(this, "登入成功: $cleanEmail", Toast.LENGTH_LONG).show()

        try {
            licenseDialog?.dismiss()
        } catch (_: Exception) {}
        licenseDialog = null

        bootstrap()
        return true
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
                        
                        val remoteKey = if (keyIndex != -1) it.getString(keyIndex) else null
                        val remoteId = if (idIndex != -1) it.getString(idIndex) else null

                        var inherited = false
                        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        val editor = prefs.edit()
                        val hasActiveUserToken = !prefs.getString("user_token", null).isNullOrEmpty()

                        if (!remoteKey.isNullOrEmpty() && !hasActiveUserToken) {
                            editor.putString("license_key", remoteKey)
                            inherited = true
                        }
                        
                        if (!remoteId.isNullOrEmpty()) {
                            editor.putString("device_id", remoteId)
                            inherited = true
                        }

                        if (inherited) {
                            val keyLog = if (hasActiveUserToken) "(skipped: User Mode)" else "${remoteKey?.take(4)}***"
                            Log.i(TAG, "🔗 Tether Successful! Key: $keyLog, ID: $remoteId")
                            editor.apply()
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
                    finishAffinity()
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
                         .remove("user_token") // MUST clear stale Google Login session & script state
                         .remove("refresh_token")
                         .remove("user_id")
                         .remove("user_email")
                         .remove("script_id")
                         .remove("script_name")
                         .remove("user_plan")
                         .apply()
                    try { File(filesDir, "cached_script.json").delete() } catch (_: Exception) {}
                    bootstrap()
                }
            }

        // Web Login
        builder.setNeutralButton("Google 登入") { _, _ ->
            prefs.edit().putLong("oauth_pending_ts", System.currentTimeMillis()).apply()
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$API_BASE_URL/login?platform=android&force_relogin=true"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            // Note: Dialog will remain open until onNewIntent dismisses it
        }

        if (!isFinishing && !isDestroyed) {
            licenseDialog = builder.setCancelable(false).show()
        }
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

    private fun performBootCheck(licenseKey: String?, userToken: String?, isRetry: Boolean = false) {
        thread {
            var conn: HttpURLConnection? = null
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

                conn = (URL(API_BOOT_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Content-Type", "application/json")
                    if (userToken != null) {
                        setRequestProperty("Authorization", "Bearer $userToken")
                    }
                }

                conn.outputStream.use { it.write(jsonBody.toString().toByteArray(Charsets.UTF_8)) }

                val responseCode = conn.responseCode
                if (responseCode == 401 && userToken != null && !isRetry && TokenRefreshHelper.refreshSync(this, userToken)) {
                    val refreshed = prefs.getString("user_token", null)
                    if (!refreshed.isNullOrEmpty()) {
                        performBootCheck(null, refreshed, isRetry = true)
                        return@thread
                    }
                }

                if (responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val json = JSONObject(resp)

                    if (json.optBoolean("authorized")) {
                        // 1. Check Update (honoring forceUpdate flag)
                        val update = json.optJSONObject("update")
                        if (update != null) {
                            val dlUrl = update.optString("downloadUrl", "")
                            val version = update.optString("versionName", "")
                            val forceUpdate = update.optBoolean("forceUpdate", true)
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) {
                                    showUpdateDialog(version, dlUrl, forceUpdate)
                                }
                            }
                            if (forceUpdate) {
                                return@thread
                            }
                        }

                        // 2. Set Script
                        val scriptObj = json.optJSONObject("script")
                        var scriptUrl: String? = null
                        var scriptName: String? = null

                        if (scriptObj != null) {
                            if (!scriptObj.isNull("url")) scriptUrl = scriptObj.optString("url")
                            if (!scriptObj.isNull("name")) scriptName = scriptObj.optString("name")
                        }

                        if (scriptUrl == "null") scriptUrl = null

                        if (scriptUrl.isNullOrEmpty()) {
                            val localId = prefs.getString("script_id", null)
                            if (!localId.isNullOrEmpty() && localId != "null" && userToken != null) {
                                scriptUrl = localId
                                scriptName = prefs.getString("script_name", getString(R.string.script_selected))
                            }
                        }

                        val license = json.optJSONObject("license")
                        val expiry = license?.optString("expiry", "會員制") ?: "會員制"

                        // Extract Subscription Plan & User ID
                        val features = json.optJSONObject("features")
                        val userPlan = features?.optString("plan", "free") ?: "free"
                        val editor = prefs.edit().putString("user_plan", userPlan)
                        if (!scriptUrl.isNullOrEmpty()) {
                            editor.putString("script_id", scriptUrl)
                        } else {
                            scriptName = getString(R.string.script_not_selected)
                        }
                        if (userToken != null) {
                            val resolvedUserId = license?.optString("user", "")
                                ?.takeIf { it.isNotBlank() && it != "Unknown" && it != "null" }
                            if (resolvedUserId != null) {
                                editor.putString("user_id", resolvedUserId)
                            }
                        } else {
                            editor.remove("user_id")
                        }
                        editor.apply()

                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            setStatus("已連線", false)
                            findViewById<TextView>(R.id.textCurrentScript).text = "任務: $scriptName"
                            findViewById<TextView>(R.id.textLicenseStatus).text = getString(R.string.license_valid)
                            findViewById<TextView>(R.id.textLicenseStatus).setTextColor(0xFF10B981.toInt())
                            findViewById<TextView>(R.id.textExpiry).text = "方案: $expiry"

                            val fleetIntent = Intent("com.gameautoeditor.INIT_FLEET")
                            fleetIntent.setPackage(packageName)
                            sendBroadcast(fleetIntent)
                        }
                    } else {
                        if (userToken != null && !isRetry && TokenRefreshHelper.refreshSync(this, userToken)) {
                            val refreshed = prefs.getString("user_token", null)
                            if (!refreshed.isNullOrEmpty()) {
                                performBootCheck(null, refreshed, isRetry = true)
                                return@thread
                            }
                        }
                        val msg = json.optString("message", getString(R.string.verify_failed))
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            setStatus(getString(R.string.verify_failed), false)
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                            if (userToken != null) {
                                setupLicenseModeUI()
                            }
                            showLicenseDialog()
                        }
                    }
                } else {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        setStatus("伺服器錯誤 $responseCode", false)
                        Toast.makeText(this, getString(R.string.connection_error), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    setStatus(getString(R.string.error_format, e.message), false)
                    Log.e(TAG, "Boot error", e)
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun fetchUserScripts(token: String, isRetry: Boolean = false) {
        val pd = android.app.ProgressDialog(this)
        pd.setMessage(getString(R.string.loading_script_list))
        if (!isFinishing && !isDestroyed) pd.show()

        thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("$API_BASE_URL/api/scripts?type=private")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Authorization", "Bearer $token")
                }

                val code = conn.responseCode
                if (code == 401 && !isRetry && TokenRefreshHelper.refreshSync(this, token)) {
                    val refreshed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString("user_token", null)
                    if (!refreshed.isNullOrEmpty()) {
                        runOnUiThread {
                            try { if (pd.isShowing) pd.dismiss() } catch (_: Exception) {}
                            if (!isFinishing && !isDestroyed) fetchUserScripts(refreshed, isRetry = true)
                        }
                        return@thread
                    }
                }

                if (code == 200) {
                    val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val json = JSONObject(resp)
                    val scripts = json.optJSONArray("scripts") ?: JSONArray()

                    runOnUiThread {
                        try { if (pd.isShowing) pd.dismiss() } catch (_: Exception) {}
                        if (!isFinishing && !isDestroyed) showScriptSelectionDialog(scripts)
                    }
                } else {
                    throw Exception("HTTP $code")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    try { if (pd.isShowing) pd.dismiss() } catch (_: Exception) {}
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, getString(R.string.load_failed_format, e.message), Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                conn?.disconnect()
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

    private fun showUpdateDialog(version: String, url: String, forceUpdate: Boolean = true) {
        val installerPkg = "com.gameauto.installer"
        val hasInstaller = try {
            packageManager.getPackageInfo(installerPkg, 0)
            true
        } catch (e: Exception) { false }

        val builder = AlertDialog.Builder(this)
            .setTitle("發現新版本 ($version)")
            .setCancelable(!forceUpdate)

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

        if (!forceUpdate) {
            builder.setNegativeButton(R.string.btn_cancel, null)
        }
        builder.show()
    }

    private fun downloadAndInstallApk(urlStr: String) {
        val progressDialog = android.app.ProgressDialog(this)
        progressDialog.setTitle(R.string.downloading_update_title)
        progressDialog.setMessage(getString(R.string.please_wait_message))
        progressDialog.isIndeterminate = false
        progressDialog.max = 100
        progressDialog.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
        progressDialog.setCancelable(false)
        if (!isFinishing && !isDestroyed) progressDialog.show()

        thread {
            var connection: HttpURLConnection? = null
            val destFile = File(getExternalFilesDir(null), "update.apk")
            val tmpFile = File(getExternalFilesDir(null), "update.apk.tmp")
            try {
                if (!urlStr.startsWith("https://")) {
                    throw IllegalArgumentException("僅允許透過 HTTPS 下載更新")
                }
                if (tmpFile.exists()) tmpFile.delete()

                val url = URL(urlStr)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    throw Exception("下載失敗: HTTP $code")
                }

                val fileLength = connection.contentLength
                val hasLength = fileLength > 0

                val data = ByteArray(8192)
                var total = 0L
                val header = ByteArray(4)
                var headerFilled = 0

                connection.inputStream.use { input ->
                    tmpFile.outputStream().use { output ->
                        var count: Int
                        while (input.read(data).also { count = it } != -1) {
                            if (headerFilled < 4) {
                                val take = minOf(4 - headerFilled, count)
                                System.arraycopy(data, 0, header, headerFilled, take)
                                headerFilled += take
                            }
                            total += count.toLong()
                            output.write(data, 0, count)

                            if (hasLength) {
                                val progress = (total * 100 / fileLength).toInt()
                                runOnUiThread {
                                    if (!isFinishing && !isDestroyed && progressDialog.isShowing) {
                                        progressDialog.progress = progress
                                        progressDialog.setMessage(getString(R.string.downloading_format, progress))
                                    }
                                }
                            } else {
                                runOnUiThread {
                                    if (!isFinishing && !isDestroyed && progressDialog.isShowing && !progressDialog.isIndeterminate) {
                                        progressDialog.isIndeterminate = true
                                        progressDialog.setMessage(getString(R.string.downloading_indeterminate))
                                    }
                                }
                            }
                        }
                        output.flush()
                    }
                }

                val isZipMagic = headerFilled == 4 &&
                    header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                    header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
                if (total <= 0L || (hasLength && total != fileLength.toLong()) || !isZipMagic) {
                    throw Exception("APK 檔案損毀或不完整")
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        java.nio.file.Files.move(
                            tmpFile.toPath(),
                            destFile.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE
                        )
                    } catch (_: Exception) {
                        if (destFile.exists()) destFile.delete()
                        if (!tmpFile.renameTo(destFile)) throw Exception("無法寫入更新檔")
                    }
                } else {
                    if (destFile.exists()) destFile.delete()
                    if (!tmpFile.renameTo(destFile)) throw Exception("無法寫入更新檔")
                }

                runOnUiThread {
                    try { if (progressDialog.isShowing) progressDialog.dismiss() } catch (_: Exception) {}
                    if (!isFinishing && !isDestroyed) installApk(destFile)
                }
            } catch (e: Exception) {
                if (tmpFile.exists()) tmpFile.delete()
                runOnUiThread {
                    try { if (progressDialog.isShowing) progressDialog.dismiss() } catch (_: Exception) {}
                    if (!isFinishing && !isDestroyed) {
                        setStatus(getString(R.string.update_failed_format, e.message), false)
                        AlertDialog.Builder(this)
                            .setTitle(R.string.update_failed_title)
                            .setMessage(e.message)
                            .setPositiveButton(R.string.btn_ok, null)
                            .show()
                    }
                }
            } finally {
                connection?.disconnect()
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
            statusText.text = "✅ 權限已就緒"
            statusText.setTextColor(0xFF10B981.toInt()) // Emerald-500
            btnActivate.isEnabled = false
            btnActivate.text = "✅ 無障礙服務已開啟"
            btnShowControls.visibility = android.view.View.VISIBLE
        } else {
            statusText.text = "⚠️ 待開啟"
            statusText.setTextColor(0xFFEF4444.toInt()) // Red-500
            btnActivate.isEnabled = true
            btnActivate.text = "前往開啟無障礙服務"
            btnShowControls.visibility = android.view.View.VISIBLE
        }
    }

    private fun isAccessibilityServiceEnabled(service: Class<out android.accessibilityservice.AccessibilityService>): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK) ?: return false
        for (enabledService in enabledServices) {
            val serviceInfo = enabledService.resolveInfo?.serviceInfo ?: continue
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
            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.text = item.label
            holder.checkBox.isChecked = item.enabled
            holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                item.enabled = isChecked
            }
        }
        
        override fun getItemCount(): Int = items.size
    }

    private fun taskConfigPrefKey(scriptId: String?): String =
        "task_config_${scriptId?.takeIf { it.isNotBlank() } ?: "default"}"

    private fun showTaskConfigDialog() {
        val file = File(filesDir, "cached_script.json")
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val scriptId = prefs.getString("script_id", null)
        if (!scriptId.isNullOrEmpty() && scriptId != "null") {
             downloadScriptForConfig(scriptId, file)
        } else {
             if (file.exists()) {
                 openTaskConfigDialog(file, null)
             } else {
                 Toast.makeText(this, getString(R.string.no_script_assigned), Toast.LENGTH_SHORT).show()
             }
        }
    }

    private fun downloadScriptForConfig(scriptId: String, destFile: File, isRetry: Boolean = false) {
        val pd = android.app.ProgressDialog(this)
        pd.setMessage(getString(R.string.downloading_config))
        pd.setCancelable(false)
        if (!isFinishing && !isDestroyed) pd.show()

        thread {
            var conn: HttpURLConnection? = null
            try {
                val urlString = if (scriptId.startsWith("http")) {
                    scriptId
                } else {
                    "$API_BASE_URL/api/get-script?id=$scriptId"
                }

                conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }

                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val gameAutoPrefs = getSharedPreferences("GameAutoPrefs", MODE_PRIVATE)
                val token = prefs.getString("user_token", null) ?: gameAutoPrefs.getString("user_token", null)
                if (!token.isNullOrEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer $token")
                }
                val licenseKey = gameAutoPrefs.getString("license_key", null) ?: prefs.getString("license_key", null)
                if (!licenseKey.isNullOrEmpty()) {
                    conn.setRequestProperty("x-license-key", licenseKey)
                }
                val deviceId = gameAutoPrefs.getString("device_id", null)
                    ?: prefs.getString("device_id", null)
                    ?: Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                if (!deviceId.isNullOrEmpty()) {
                    conn.setRequestProperty("x-device-id", deviceId)
                }

                var responseCode = conn.responseCode

                if (responseCode == 401 && !token.isNullOrEmpty() && !isRetry && TokenRefreshHelper.refreshSync(this, token)) {
                    runOnUiThread {
                        try { if (pd.isShowing) pd.dismiss() } catch (_: Exception) {}
                        if (!isFinishing && !isDestroyed) downloadScriptForConfig(scriptId, destFile, isRetry = true)
                    }
                    return@thread
                }

                if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == HttpURLConnection.HTTP_MOVED_PERM) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    conn = null
                    if (location != null) {
                        conn = (URL(location).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 10_000
                            readTimeout = 10_000
                        }
                        responseCode = conn.responseCode
                    }
                }

                if (responseCode == 200 && conn != null) {
                    val json = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    destFile.writeText(json)
                    runOnUiThread {
                        try { if (pd.isShowing) pd.dismiss() } catch (_: Exception) {}
                        if (!isFinishing && !isDestroyed) openTaskConfigDialog(destFile, scriptId)
                    }
                } else {
                    throw Exception("HTTP $responseCode")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    try { if (pd.isShowing) pd.dismiss() } catch (_: Exception) {}
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, getString(R.string.config_download_failed, e.message), Toast.LENGTH_SHORT).show()
                        if (destFile.exists()) {
                            openTaskConfigDialog(destFile, scriptId)
                        }
                    }
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun openTaskConfigDialog(file: File, scriptId: String? = null) {
        try {
            val jsonStr = file.readText()
            val graphData = JSONObject(jsonStr)
            val nodes = graphData.optJSONArray("nodes") ?: JSONArray()
            val taskMap = linkedMapOf<String, TaskItem>()
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val activeScriptId = scriptId?.takeIf { it.isNotBlank() } ?: prefs.getString("script_id", null)?.takeIf { it.isNotBlank() }
            val hasAnyPerScriptConfig = prefs.all.keys.any { it.startsWith("task_config_") }
            val savedConfig = prefs.getString(taskConfigPrefKey(activeScriptId), null)?.let { raw ->
                try { JSONObject(raw) } catch (_: Exception) { null }
            }

            // 1. Find Root Node (Main Scene)
            var rootNode: JSONObject? = null
            for (i in 0 until nodes.length()) {
                val node = nodes.getJSONObject(i)
                if (node.optJSONObject("data")?.optBoolean("isRoot") == true) {
                    rootNode = node
                    break
                }
            }
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
            if (rootNode == null && nodes.length() > 0) {
                rootNode = nodes.getJSONObject(0)
            }

            if (rootNode != null) {
                val regions = rootNode.optJSONObject("data")?.optJSONArray("regions")
                if (regions != null) {
                    for (j in 0 until regions.length()) {
                        val r = regions.getJSONObject(j)
                        val label = r.optString("label")
                        if (!label.isNullOrEmpty() && label != "New Button" && !label.startsWith("Edge")) {
                            if (!taskMap.containsKey(label)) {
                                val savedEntry = savedConfig?.optJSONObject(label)
                                var isEnabled = r.optBoolean("enabled", true)
                                val varKey = "enable_$label"
                                if (savedEntry != null && savedEntry.has("enabled")) {
                                    isEnabled = savedEntry.optBoolean("enabled", true)
                                } else if (!hasAnyPerScriptConfig && prefs.contains(varKey)) {
                                    isEnabled = (prefs.getInt(varKey, 1) == 1)
                                }

                                val schedule = r.optJSONObject("schedule")
                                val priorityKey = "priority_$label"
                                val priority = when {
                                    savedEntry != null && savedEntry.has("priority") -> savedEntry.optInt("priority", 100)
                                    !hasAnyPerScriptConfig && prefs.contains(priorityKey) -> prefs.getInt(priorityKey, 100)
                                    else -> schedule?.optInt("priority", 100) ?: 100
                                }
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
            val dialogView = layoutInflater.inflate(R.layout.dialog_task_config, null)
            val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerViewTasks)
            val adapter = TaskAdapter(taskList)
            recyclerView.layoutManager = LinearLayoutManager(this)
            recyclerView.adapter = adapter

            val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                    val from = vh.adapterPosition
                    val to = target.adapterPosition
                    if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
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
                    val persistedConfig = JSONObject()
                    taskList.forEachIndexed { index, task ->
                        val newPriority = index + 1
                        persistedConfig.put(
                            task.label,
                            JSONObject().put("enabled", task.enabled).put("priority", newPriority)
                        )
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
                    editor.putString(taskConfigPrefKey(activeScriptId), persistedConfig.toString())
                    editor.apply()
                    file.writeText(graphData.toString())

                    val syncIntent = Intent("com.gameautoeditor.UPDATE_TASK_CONFIG")
                    syncIntent.setPackage(packageName)
                    sendBroadcast(syncIntent)

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
