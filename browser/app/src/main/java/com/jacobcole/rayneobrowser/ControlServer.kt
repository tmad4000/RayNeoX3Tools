package com.jacobcole.rayneobrowser

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.EditText
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ControlServer(
    private val activity: Activity,
    private val webView: WebView,
    private val urlBar: EditText,
    port: Int = 7317
) : NanoHTTPD("0.0.0.0", port) {

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/", "/index.html" -> serveControlHtml()
            "/url" -> handleUrl(session)
            "/js" -> handleJs(session)
            "/dom" -> handleDom()
            "/screenshot" -> handleScreenshot()
            "/back" -> handleBack()
            "/forward" -> handleForward()
            "/title" -> handleTitle()
            "/ping" -> json(mapOf("ok" to true, "version" to "0.1.0"))
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found: ${session.uri}")
        }
    }

    private fun serveControlHtml(): Response {
        val html = activity.resources.openRawResource(R.raw.control).bufferedReader().use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }


    private fun handleUrl(session: IHTTPSession): Response {
        val url = when (session.method) {
            Method.POST -> readBody(session)?.let { JSONObject(it).optString("url") }
            Method.GET -> session.parameters["url"]?.firstOrNull()
            else -> null
        }
        if (url.isNullOrBlank()) {
            return json(mapOf("error" to "missing url"), Response.Status.BAD_REQUEST)
        }
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            webView.loadUrl(url)
            urlBar.setText(url)
            latch.countDown()
        }
        latch.await(1, TimeUnit.SECONDS)
        return json(mapOf("ok" to true, "url" to url))
    }

    private fun handleJs(session: IHTTPSession): Response {
        val code = readBody(session) ?: return json(mapOf("error" to "missing body"), Response.Status.BAD_REQUEST)
        val result = arrayOf<String?>(null)
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            webView.evaluateJavascript(code) { r ->
                result[0] = r
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        return json(mapOf("result" to (result[0] ?: "null")))
    }

    private fun handleDom(): Response {
        val html = arrayOf<String?>(null)
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            webView.evaluateJavascript("document.documentElement.outerHTML") { r ->
                html[0] = r
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        return newFixedLengthResponse(Response.Status.OK, "text/html", html[0] ?: "")
    }

    private fun handleScreenshot(): Response {
        val bytes = arrayOf<ByteArray?>(null)
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            val root = activity.window.decorView.rootView
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bytes[0] = bos.toByteArray()
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        val data = bytes[0] ?: ByteArray(0)
        return newFixedLengthResponse(Response.Status.OK, "image/png", data.inputStream(), data.size.toLong())
    }

    private fun handleBack(): Response {
        activity.runOnUiThread {
            if (webView.canGoBack()) webView.goBack()
        }
        return json(mapOf("ok" to true))
    }

    private fun handleForward(): Response {
        activity.runOnUiThread {
            if (webView.canGoForward()) webView.goForward()
        }
        return json(mapOf("ok" to true))
    }

    private fun handleTitle(): Response {
        val title = arrayOf<String?>(null)
        val url = arrayOf<String?>(null)
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            title[0] = webView.title
            url[0] = webView.url
            latch.countDown()
        }
        latch.await(1, TimeUnit.SECONDS)
        return json(mapOf("title" to (title[0] ?: ""), "url" to (url[0] ?: "")))
    }

    private fun readBody(session: IHTTPSession): String? {
        val body = HashMap<String, String>()
        session.parseBody(body)
        return body["postData"]
    }

    private fun json(map: Map<String, Any>, status: Response.Status = Response.Status.OK): Response {
        val obj = JSONObject(map)
        return newFixedLengthResponse(status, "application/json", obj.toString())
    }
}
