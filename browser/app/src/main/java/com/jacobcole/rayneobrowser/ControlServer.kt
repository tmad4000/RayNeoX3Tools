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
            "/refresh" -> handleRefresh()
            "/tap-element" -> handleTapElement(session)
            "/stereo" -> handleStereo(session)
            "/volume" -> handleVolume(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found: ${session.uri}")
        }
    }

    private fun handleStereo(session: IHTTPSession): Response {
        val onParam = session.parameters["on"]?.firstOrNull()
        val main = activity as? MainActivity
            ?: return json(mapOf("error" to "activity is not MainActivity"), Response.Status.INTERNAL_ERROR)
        if (onParam != null) {
            val desired = onParam == "1" || onParam.equals("true", ignoreCase = true)
            val latch = CountDownLatch(1)
            activity.runOnUiThread { main.setStereoEnabled(desired); latch.countDown() }
            latch.await(1, TimeUnit.SECONDS)
        }
        return json(mapOf("stereo" to main.isStereoEnabled()))
    }

    private fun handleVolume(session: IHTTPSession): Response {
        val action = session.parameters["action"]?.firstOrNull() ?: "get"
        val am = activity.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val stream = android.media.AudioManager.STREAM_MUSIC
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            when (action) {
                "up" -> am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
                "down" -> am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
                "mute" -> am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_MUTE, android.media.AudioManager.FLAG_SHOW_UI)
                "unmute" -> am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_UNMUTE, android.media.AudioManager.FLAG_SHOW_UI)
                "max" -> am.setStreamVolume(stream, am.getStreamMaxVolume(stream), android.media.AudioManager.FLAG_SHOW_UI)
            }
            latch.countDown()
        }
        latch.await(1, TimeUnit.SECONDS)
        return json(mapOf(
            "volume" to am.getStreamVolume(stream),
            "max" to am.getStreamMaxVolume(stream),
            "muted" to am.isStreamMute(stream)
        ))
    }

    private fun handleRefresh(): Response {
        activity.runOnUiThread { webView.reload() }
        return json(mapOf("ok" to true))
    }

    private fun handleTapElement(session: IHTTPSession): Response {
        val body = readBody(session) ?: return json(mapOf("error" to "missing body"), Response.Status.BAD_REQUEST)
        val selector = JSONObject(body).optString("selector")
        if (selector.isBlank()) return json(mapOf("error" to "missing selector"), Response.Status.BAD_REQUEST)
        val js = "(function(){var el=document.querySelector(" + JSONObject.quote(selector) + ");" +
            "if(!el) return JSON.stringify({ok:false,error:'not-found'});" +
            "el.scrollIntoView({block:'center',inline:'center',behavior:'auto'});" +
            "var r=el.getBoundingClientRect();" +
            // If still off-screen horizontally, manually scroll to its offsetLeft
            "if(r.left<0||r.right>window.innerWidth){window.scrollTo(el.offsetLeft-window.innerWidth/2+r.width/2, el.offsetTop-window.innerHeight/2+r.height/2);r=el.getBoundingClientRect();}" +
            "var dpr=window.devicePixelRatio||1;" +
            "return JSON.stringify({ok:true,x:Math.max(10,(r.left+r.width/2)*dpr),y:Math.max(10,(r.top+r.height/2)*dpr)});})()"
        val result = arrayOf<String?>(null)
        val latch = CountDownLatch(1)
        activity.runOnUiThread { webView.evaluateJavascript(js) { r -> result[0] = r; latch.countDown() } }
        latch.await(3, TimeUnit.SECONDS)
        val raw = result[0] ?: return json(mapOf("error" to "js timeout"), Response.Status.INTERNAL_ERROR)
        val unescaped = try { JSONObject("{\"v\":$raw}").getString("v") } catch (e: Exception) { raw }
        val inner = try { JSONObject(unescaped) } catch (e: Exception) {
            return json(mapOf("error" to "parse", "raw" to raw), Response.Status.INTERNAL_ERROR)
        }
        if (!inner.optBoolean("ok")) return json(mapOf("ok" to false, "error" to inner.optString("error")))
        val px = inner.optDouble("x").toFloat()
        val py = inner.optDouble("y").toFloat()
        // Give the scroll a moment to settle, then dispatch a touch.
        activity.runOnUiThread {
            webView.postDelayed({
                val t = android.os.SystemClock.uptimeMillis()
                val down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, px, py, 0)
                val up = android.view.MotionEvent.obtain(t, t + 50, android.view.MotionEvent.ACTION_UP, px, py, 0)
                webView.dispatchTouchEvent(down); webView.dispatchTouchEvent(up)
                down.recycle(); up.recycle()
            }, 250)
        }
        return json(mapOf("ok" to true, "x" to px.toDouble(), "y" to py.toDouble()))
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
