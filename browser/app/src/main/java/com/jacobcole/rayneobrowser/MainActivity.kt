package com.jacobcole.rayneobrowser

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
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
            }
        }
        wv.webChromeClient = WebChromeClient()
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
