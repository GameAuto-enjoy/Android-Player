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
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.graphics.Color
import android.util.Log

class AdActivity : AppCompatActivity() {
    private val TAG = "GameAutoAd"
    private val AD_URL: String
        get() = "${BuildConfig.AI_API_URL.trimEnd('/')}/ads"
    private val AD_DURATION_MS: Long = 5000 // 5 seconds
    private var isFinished = false
    private var countDownTimer: CountDownTimer? = null
    private var adWebView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        } catch (t: Throwable) {
            Log.w(TAG, "設置全螢幕系統 UI 失敗", t)
        }

        val layout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        // 嘗試載入 WebView，若設備無 WebView 元件則降級顯示文字佔位畫面，絕不崩潰
        try {
            val webView = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = WebViewClient()
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                loadUrl(AD_URL)
            }
            adWebView = webView
            layout.addView(webView)
        } catch (t: Throwable) {
            Log.e(TAG, "設備缺少 WebView 或載入失敗，降級處理: ${t.message}")
            val placeholder = TextView(this).apply {
                text = "GameAuto AI\n贊助商支援播放中..."
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            layout.addView(placeholder)
        }

        val closeButton = Button(this).apply {
            val btnParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, 96, 48, 0)
            }
            layoutParams = btnParams
            isEnabled = false
            text = "廣告將在 5 秒後結束"
            alpha = 0.9f
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setTextColor(Color.WHITE)
            setPadding(32, 16, 32, 16)
            setOnClickListener {
                finishAndExecute()
            }
        }
        layout.addView(closeButton)

        setContentView(layout)

        try {
            countDownTimer = object : CountDownTimer(AD_DURATION_MS, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    if (isFinishing || isDestroyed) return
                    closeButton.text = "廣告將在 ${millisUntilFinished / 1000 + 1} 秒後結束"
                }

                override fun onFinish() {
                    if (isFinishing || isDestroyed) return
                    closeButton.isEnabled = true
                    closeButton.text = "✕ 關閉廣告並執行"
                    closeButton.setBackgroundColor(Color.argb(255, 79, 70, 229)) // Indigo 600
                    closeButton.alpha = 1.0f
                }
            }.start()
        } catch (t: Throwable) {
            Log.e(TAG, "計時器異常，直接完成並執行", t)
            finishAndExecute()
        }
    }

    override fun onBackPressed() {
        // Disable back button while ad is playing
    }

    private fun finishAndExecute() {
        if (isFinished) return
        isFinished = true
        countDownTimer?.cancel()
        countDownTimer = null
        try {
            val intent = Intent("com.gameautoeditor.START_SCRIPT_AFTER_AD").apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "發送 START_SCRIPT_AFTER_AD 廣播失敗", t)
        }
        finish()
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        countDownTimer = null
        adWebView?.let { wv ->
            try {
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.clearHistory()
                wv.removeAllViews()
                wv.destroy()
            } catch (t: Throwable) {
                Log.w(TAG, "釋放廣告 WebView 失敗: ${t.message}")
            }
        }
        adWebView = null
        super.onDestroy()
    }
}
