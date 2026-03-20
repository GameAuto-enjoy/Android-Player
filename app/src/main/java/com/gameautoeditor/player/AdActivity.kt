package com.gameautoeditor.player

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import android.graphics.Color
import android.os.Build
import android.view.WindowInsets
import android.view.WindowInsetsController

class AdActivity : AppCompatActivity() {
    private val TAG = "GameAutoAd"
    private val AD_URL = "https://game-auto-ai.vercel.app/ads"
    private val AD_DURATION_MS: Long = 5000 // 5 seconds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Hide System UI (Fullscreen)
        // using a simpler approach that avoids getInsetsController() NPE on some devices
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )


        val layout = FrameLayout(this)
        layout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        layout.setBackgroundColor(Color.BLACK)

        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, 
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        webView.loadUrl(AD_URL)
        layout.addView(webView)

        val closeButton = Button(this)
        val btnParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        // Position top-right with margin
        btnParams.gravity = Gravity.TOP or Gravity.END
        btnParams.setMargins(0, 96, 48, 0)
        closeButton.layoutParams = btnParams
        closeButton.isEnabled = false
        closeButton.text = "廣告將在 5 秒後結束"
        closeButton.alpha = 0.9f
        closeButton.setBackgroundColor(Color.argb(200, 0, 0, 0))
        closeButton.setTextColor(Color.WHITE)
        closeButton.setPadding(32, 16, 32, 16)
        closeButton.setOnClickListener {
            finishAndExecute()
        }
        layout.addView(closeButton)

        setContentView(layout)

        object : CountDownTimer(AD_DURATION_MS, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                closeButton.text = "廣告將在 ${millisUntilFinished / 1000 + 1} 秒後結束"
            }

            override fun onFinish() {
                closeButton.isEnabled = true
                closeButton.text = "✕ 關閉廣告並執行"
                closeButton.setBackgroundColor(Color.argb(255, 79, 70, 229)) // Indigo 600
                closeButton.alpha = 1.0f
            }
        }.start()
    }

    override fun onBackPressed() {
        // Disable back button while ad is playing
    }

    private fun finishAndExecute() {
        val intent = Intent("com.gameautoeditor.START_SCRIPT_AFTER_AD")
        intent.setPackage(packageName)
        sendBroadcast(intent)
        finish()
    }
}
