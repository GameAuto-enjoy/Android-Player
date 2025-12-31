package com.gameautoeditor.player

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader

class AutomationService : AccessibilityService() {
    
    private val TAG = "AutomationService"
    private lateinit var scriptEngine: ScriptEngine
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "✅ Accessibility Service 已啟動")
        
        scriptEngine = ScriptEngine(this)
        
        // 從 assets 載入預先打包的腳本
        loadAndExecuteScript()
    }
    
    private fun loadAndExecuteScript() {
        try {
            val inputStream = assets.open("script.json")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val scriptJson = reader.readText()
            reader.close()
            
            Log.i(TAG, "📄 載入內建腳本")
            
            // 延遲 3 秒後自動執行
            android.os.Handler(mainLooper).postDelayed({
                showToast("開始執行自動化腳本")
                scriptEngine.executeScript(scriptJson)
            }, 3000)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 載入腳本失敗: ${e.message}", e)
            showToast("找不到內建腳本 (assets/script.json)")
        }
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 可在此監聽畫面變化事件
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "⚠️ Service interrupted")
        scriptEngine.stop()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "🛑 Accessibility Service 已停止")
        scriptEngine.stop()
    }
    
    fun showToast(message: String) {
        android.os.Handler(mainLooper).post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
