package com.jacobcole.rayneobrowser

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.CustomViewCallback
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.jacobcole.rayneobrowser.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var controlServer: ControlServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        configureWebView()
        wireToolbar()
        handleIntent(intent)
        startControlServer()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        controlServer?.stop()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && binding.webview.canGoBack()) {
            binding.webview.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    fun setStereoEnabled(enabled: Boolean) {
        binding.stereo.stereoEnabled = enabled
    }

    fun isStereoEnabled(): Boolean = binding.stereo.stereoEnabled

    data class HistoryEntry(val url: String, val title: String, val timestamp: Long)
    private val history = java.util.concurrent.ConcurrentLinkedDeque<HistoryEntry>()
    private val historyCap = 50

    fun pushHistory(url: String, title: String) {
        if (url.isBlank() || url.startsWith("data:") || url.startsWith("about:")) return
        // Dedup: skip if same URL as most recent entry
        if (history.peekFirst()?.url == url) return
        history.addFirst(HistoryEntry(url, title, System.currentTimeMillis()))
        while (history.size > historyCap) history.pollLast()
    }

    fun getHistorySnapshot(): List<HistoryEntry> = history.toList()

    private var viewportMaxed = false

    fun toggleViewportMax(): Boolean {
        viewportMaxed = !viewportMaxed
        binding.toolbar.visibility = if (viewportMaxed) View.GONE else View.VISIBLE
        val insets = window.decorView.windowInsetsController
        if (insets != null) {
            if (viewportMaxed) {
                insets.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                insets.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                insets.show(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
            }
        }
        return viewportMaxed
    }

    fun isViewportMaxed(): Boolean = viewportMaxed

    private fun configureWebView() {
        val wv = binding.webview
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = userAgentString + " RayNeoBrowser/0.1"
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.urlBar.setText(url ?: "")
                pushHistory(url ?: "", view?.title ?: "")
                // Auto-unmute: YouTube (and most autoplay-policy-respecting sites)
                // start <video> with muted=true. Persistently clear that for the
                // first ~10s after load so audio works without manual unmute.
                view?.evaluateJavascript(
                    """(function(){
                        var unmute = function(){
                            document.querySelectorAll('video').forEach(function(v){ if (v.muted) v.muted = false; });
                        };
                        unmute();
                        var n = 0;
                        var iv = setInterval(function(){ unmute(); if (++n > 20) clearInterval(iv); }, 500);
                    })();""".trimIndent(),
                    null
                )
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            private var fsView: View? = null
            private var fsCallback: CustomViewCallback? = null
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fsView != null) { callback.onCustomViewHidden(); return }
                fsView = view
                fsCallback = callback
                (window.decorView as? android.view.ViewGroup)?.addView(
                    view,
                    android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
            override fun onHideCustomView() {
                fsView?.let { (window.decorView as? android.view.ViewGroup)?.removeView(it) }
                fsView = null
                fsCallback?.onCustomViewHidden()
                fsCallback = null
            }
        }
    }

    private fun wireToolbar() {
        binding.btnBack.setOnClickListener {
            if (binding.webview.canGoBack()) binding.webview.goBack()
        }
        binding.btnForward.setOnClickListener {
            if (binding.webview.canGoForward()) binding.webview.goForward()
        }
        binding.btnGo.setOnClickListener { loadFromUrlBar() }
        binding.urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadFromUrlBar()
                true
            } else false
        }
    }

    private fun loadFromUrlBar() {
        val raw = binding.urlBar.text.toString().trim()
        if (raw.isEmpty()) return
        val url = normalizeUrl(raw)
        binding.webview.loadUrl(url)
        binding.webview.requestFocus()
    }

    private fun normalizeUrl(raw: String): String {
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        if (raw.contains(".") && !raw.contains(" ")) return "https://$raw"
        return "https://www.google.com/search?q=" + android.net.Uri.encode(raw)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.toString()?.let { url ->
                binding.webview.loadUrl(url)
                binding.urlBar.setText(url)
            }
        }
    }

    private fun startControlServer() {
        controlServer = ControlServer(this, binding.webview, binding.urlBar).apply {
            try { start() } catch (e: Exception) { android.util.Log.e("RayNeoBrowser", "control server failed to start", e) }
        }
    }
}
