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
        
        enableButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
        
        updateServiceStatus(statusText)
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
