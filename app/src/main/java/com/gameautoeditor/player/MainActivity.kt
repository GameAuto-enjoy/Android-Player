package com.gameautoeditor.player

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        val statusText: TextView = findViewById(R.id.statusText)
        val enableButton: Button = findViewById(R.id.enableButton)
        val activateButton: Button = findViewById(R.id.activateButton)
        
        enableButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
        
        // 啟動碼按鈕
        activateButton.setOnClickListener {
            showActivationDialog()
        }
        
        updateServiceStatus(statusText)
        updateActivationStatus()
        checkOverlayPermission()
        
        // Setup new buttons
        findViewById<android.widget.Button>(R.id.btnActivate).setOnClickListener {
            // Open Accessibility Settings
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
        
        findViewById<android.widget.Button>(R.id.btnInputScript).setOnClickListener {
            showActivationDialog()
        }
        
        findViewById<android.widget.Button>(R.id.btnUpdateScript).setOnClickListener {
            performHotUpdate()
        }
    }
    
    private fun performHotUpdate() {
        val btn = findViewById<android.widget.Button>(R.id.btnUpdateScript)
        val originalText = btn.text
        btn.text = "Checking..."
        btn.isEnabled = false
        
        Thread {
            try {
                // Fetch list from Vercel API
                val apiUrl = "https://game-auto-editor.vercel.app/api/list-scripts"
                val connection = java.net.URL(apiUrl).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                
                if (connection.responseCode == 200) {
                    val responseJson = connection.inputStream.bufferedReader().readText()
                    val json = org.json.JSONObject(responseJson)
                    
                    if (json.optBoolean("success")) {
                        val scripts = json.optJSONArray("scripts")
                        if (scripts != null && scripts.length() > 0) {
                            // First one is the latest because API sorts it
                            val latestScript = scripts.getJSONObject(0)
                            val latestUrl = latestScript.getString("url")
                            val name = latestScript.getString("pathname")
                            val date = latestScript.getString("uploadedAt")
                            
                            // Save to Prefs
                            val prefs = getSharedPreferences("GameAutoEditor", MODE_PRIVATE)
                            prefs.edit().putString("script_id", latestUrl).apply()
                            
                            runOnUiThread {
                                android.widget.Toast.makeText(this, "🔥 Updated to: $name", android.widget.Toast.LENGTH_LONG).show()
                                findViewById<android.widget.TextView>(R.id.textCurrentScript).text = name
                                btn.text = originalText
                                btn.isEnabled = true
                            }
                        } else {
                            runOnUiThread {
                                android.widget.Toast.makeText(this, "No scripts found", android.widget.Toast.LENGTH_SHORT).show()
                                btn.text = originalText
                                btn.isEnabled = true
                            }
                        }
                    } 
                } else {
                     runOnUiThread {
                        android.widget.Toast.makeText(this, "Check failed: ${connection.responseCode}", android.widget.Toast.LENGTH_SHORT).show()
                        btn.text = originalText
                        btn.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    android.widget.Toast.makeText(this, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    btn.text = originalText
                    btn.isEnabled = true
                }
            }
        }.start()
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, 1234)
            android.widget.Toast.makeText(this, "請授權「顯示在上方」權限以顯示懸浮窗", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    
    private fun showActivationDialog() {
        val input = android.widget.EditText(this)
        input.hint = "請輸入 ID 或 JSON URL"
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        
        // 如果已有啟動碼，顯示前幾個字元
        val currentId = getSharedPreferences("GameAutoEditor", MODE_PRIVATE)
            .getString("script_id", null)
        if (currentId != null) {
            input.setText(currentId)
        }
        
        android.app.AlertDialog.Builder(this)
            .setTitle("輸入腳本來源")
            .setMessage("請輸入啟動碼（Purchase ID）\n或是直接輸入 JSON 檔案的網址 (http/https)")
            .setView(input)
            .setPositiveButton("確認") { _, _ ->
                val scriptId = input.text.toString().trim()
                if (scriptId.isNotEmpty()) {
                    // 儲存到 SharedPreferences
                    getSharedPreferences("GameAutoEditor", MODE_PRIVATE)
                        .edit()
                        .putString("script_id", scriptId)
                        .apply()
                    
                    android.widget.Toast.makeText(
                        this,
                        "✅ 設定成功！\n請啟用無障礙服務以開始自動化",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    
                    updateActivationStatus()
                } else {
                    android.widget.Toast.makeText(
                        this,
                        "❌ 請輸入內容",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("清除") { _, _ ->
                getSharedPreferences("GameAutoEditor", MODE_PRIVATE)
                    .edit()
                    .remove("script_id")
                    .apply()
                android.widget.Toast.makeText(
                    this,
                    "已清除設定",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                updateActivationStatus()
            }
            .show()
    }
    
    private fun updateActivationStatus() {
        val activateButton: Button = findViewById(R.id.activateButton)
        val scriptId = getSharedPreferences("GameAutoEditor", MODE_PRIVATE)
            .getString("script_id", null)
        
        if (scriptId != null) {
            activateButton.text = "✅ 已啟動\n點擊重新設定"
            activateButton.setBackgroundColor(getColor(android.R.color.holo_green_dark))
        } else {
            activateButton.text = "⚠️ 輸入啟動碼"
            activateButton.setBackgroundColor(getColor(android.R.color.holo_orange_dark))
        }
    }
    
    override fun onResume() {
        super.onResume()
        val statusText: TextView = findViewById(R.id.statusText)
        updateServiceStatus(statusText)
    }
    
    private fun updateServiceStatus(textView: TextView) {
        val isEnabled = isAccessibilityServiceEnabled()
        textView.text = if (isEnabled) {
            "✅ 服務已啟用\n腳本將在 3 秒後自動執行"
        } else {
            "❌ 請啟用無障礙服務"
        }
    }
    
    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "${packageName}/.AutomationService"
        try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            return enabledServices?.contains(service) == true
        } catch (e: Exception) {
            return false
        }
    }
}
