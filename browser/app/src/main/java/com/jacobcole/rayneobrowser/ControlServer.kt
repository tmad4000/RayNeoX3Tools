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
            "/key" -> handleKey(session)
            "/fullscreen" -> handleFullscreen()
            "/play" -> handlePlay()
            "/seek" -> handleSeek(session)
            "/scroll" -> handleScroll(session)
            "/zoom" -> handleZoom(session)
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

    private fun handleKey(session: IHTTPSession): Response {
        val name = session.parameters["name"]?.firstOrNull()
            ?: return json(mapOf("error" to "missing name"), Response.Status.BAD_REQUEST)
        val code = when (name.uppercase()) {
            "SPACE" -> android.view.KeyEvent.KEYCODE_SPACE
            "ENTER" -> android.view.KeyEvent.KEYCODE_ENTER
            "TAB" -> android.view.KeyEvent.KEYCODE_TAB
            "ESCAPE", "ESC" -> android.view.KeyEvent.KEYCODE_ESCAPE
            "BACK" -> android.view.KeyEvent.KEYCODE_BACK
            "LEFT" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
            "RIGHT" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            "UP" -> android.view.KeyEvent.KEYCODE_DPAD_UP
            "DOWN" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
            "F" -> android.view.KeyEvent.KEYCODE_F
            "K" -> android.view.KeyEvent.KEYCODE_K
            "M" -> android.view.KeyEvent.KEYCODE_M
            else -> return json(mapOf("error" to "unknown key", "name" to name), Response.Status.BAD_REQUEST)
        }
        activity.runOnUiThread {
            val t = android.os.SystemClock.uptimeMillis()
            val down = android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, code, 0)
            val up = android.view.KeyEvent(t, t + 50, android.view.KeyEvent.ACTION_UP, code, 0)
            webView.dispatchKeyEvent(down)
            webView.dispatchKeyEvent(up)
        }
        return json(mapOf("ok" to true, "key" to name.uppercase(), "code" to code))
    }

    private fun handleFullscreen(): Response {
        // Three layers, all applied together and toggleable:
        // 1. viewport-max: hide our toolbar + system bars so WebView fills Activity.
        // 2. CSS override: force the active <video>/iframe to position:fixed + 100vw/vh,
        //    z-index above everything. Works where requestFullscreen doesn't (YouTube
        //    mobile, sites that eat gesture requirements).
        // 3. Fire the page-level requestFullscreen() as a bonus for sites that honor it.
        val main = activity as? MainActivity
        val maxed = arrayOf(false)
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            maxed[0] = main?.toggleViewportMax() ?: false
            val forcedCss = maxed[0]
            val js = """(function(){
                var ID = '__rayneo_fs_style__';
                var existing = document.getElementById(ID);
                if (existing) existing.remove();
                if ($forcedCss) {
                    var el = document.querySelector('video')
                          || document.querySelector('iframe[src*=wistia], iframe[src*=youtube], iframe[src*=vimeo], iframe[src*=player]');
                    if (el) {
                        var style = document.createElement('style');
                        style.id = ID;
                        // Use the element's unique characteristics if possible; for video tag, tag-based works.
                        var sel = el.tagName.toLowerCase();
                        if (el.id) sel += '#' + el.id;
                        style.textContent = sel + '{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;max-width:100vw!important;max-height:100vh!important;z-index:2147483647!important;background:black!important;object-fit:contain!important;}';
                        document.head.appendChild(style);
                        try { if (el.requestFullscreen) el.requestFullscreen(); else if (el.webkitEnterFullscreen) el.webkitEnterFullscreen(); } catch(e) {}
                        return 'forced:' + sel;
                    }
                    return 'no-media';
                }
                try { if (document.exitFullscreen) document.exitFullscreen(); } catch(e) {}
                return 'restored';
            })()"""
            webView.evaluateJavascript(js, null)
            latch.countDown()
        }
        latch.await(1, TimeUnit.SECONDS)
        return json(mapOf("ok" to true, "viewportMaxed" to maxed[0]))
    }

    private fun handleScroll(session: IHTTPSession): Response {
        val dir = session.parameters["dir"]?.firstOrNull() ?: "down"
        val amount = session.parameters["amount"]?.firstOrNull()?.toIntOrNull() ?: 400
        val js = when (dir) {
            "up" -> "window.scrollBy(0, -$amount)"
            "down" -> "window.scrollBy(0, $amount)"
            "top" -> "window.scrollTo(0, 0)"
            "bottom" -> "window.scrollTo(0, document.body.scrollHeight)"
            else -> "window.scrollBy(0, $amount)"
        }
        activity.runOnUiThread { webView.evaluateJavascript(js, null) }
        return json(mapOf("ok" to true, "dir" to dir, "amount" to amount))
    }

    private fun handleZoom(session: IHTTPSession): Response {
        val level = session.parameters["level"]?.firstOrNull()?.toIntOrNull()
        val delta = session.parameters["delta"]?.firstOrNull()?.toIntOrNull()
        val latch = CountDownLatch(1)
        val current = arrayOf(100)
        activity.runOnUiThread {
            val target = when {
                level != null -> level.coerceIn(50, 300)
                delta != null -> (webView.settings.textZoom + delta).coerceIn(50, 300)
                else -> 100
            }
            webView.settings.textZoom = target
            current[0] = target
            latch.countDown()
        }
        latch.await(1, TimeUnit.SECONDS)
        return json(mapOf("zoom" to current[0]))
    }

    private fun handleSeek(session: IHTTPSession): Response {
        val delta = session.parameters["delta"]?.firstOrNull()?.toDoubleOrNull()
        val to = session.parameters["to"]?.firstOrNull()?.toDoubleOrNull()
        if (delta == null && to == null) {
            return json(mapOf("error" to "pass ?delta=SECONDS or ?to=SECONDS"), Response.Status.BAD_REQUEST)
        }
        val html5 = if (to != null) "v.currentTime = $to; return 'html5:to:'+v.currentTime;"
                    else "v.currentTime = Math.max(0, v.currentTime + ($delta)); return 'html5:delta:'+v.currentTime;"
        val wistia = if (to != null) "window._wq=window._wq||[]; window._wq.push({id:id, onReady:function(v){v.time($to);}}); return 'wistia:to:$to';"
                     else "window._wq=window._wq||[]; window._wq.push({id:id, onReady:function(v){v.time(Math.max(0, v.time()+($delta)));}}); return 'wistia:delta:$delta';"
        val js = buildPlayerDispatchJs(html5 = html5, wistia = wistia)
        val result = arrayOf<String?>(null)
        val latch = CountDownLatch(1)
        activity.runOnUiThread { webView.evaluateJavascript(js) { r -> result[0] = r; latch.countDown() } }
        latch.await(3, TimeUnit.SECONDS)
        return json(mapOf("result" to (result[0] ?: "null")))
    }

    private fun handlePlay(): Response {
        // First try JS-based play/pause, which works when the player is loaded
        // (HTML5 video in active state or Wistia with API hooked).
        val js = buildPlayerDispatchJs(
            html5 = "var r = v.play(); if (r && r.catch) r.catch(function(){}); return 'html5:played';",
            wistia = "window._wq=window._wq||[]; window._wq.push({id:id, onReady:function(v){v.state()==='playing'?v.pause():v.play();}}); return 'wistia:'+id;"
        )
        val result = arrayOf<String?>(null)
        val latch = CountDownLatch(1)
        activity.runOnUiThread { webView.evaluateJavascript(js) { r -> result[0] = r; latch.countDown() } }
        latch.await(3, TimeUnit.SECONDS)
        val r = result[0] ?: "null"

        // If no player was found via JS (e.g. YouTube not yet initialized) OR
        // the call may not have had user activation, fall back to a native tap
        // on the video element's screen position. YouTube mobile requires a
        // real touch event to start playback.
        if (r.contains("no-player") || r.contains("null")) {
            val posJs = "(function(){var el=document.querySelector('video'); if(!el) return null; var rect=el.getBoundingClientRect(); var dpr=window.devicePixelRatio||1; return JSON.stringify({x:(rect.left+rect.width/2)*dpr, y:(rect.top+rect.height/2)*dpr});})()"
            val raw = arrayOf<String?>(null)
            val l2 = CountDownLatch(1)
            activity.runOnUiThread { webView.evaluateJavascript(posJs) { pr -> raw[0] = pr; l2.countDown() } }
            l2.await(2, TimeUnit.SECONDS)
            val coords = try {
                val inner = JSONObject("{\"v\":${raw[0]}}").getString("v")
                if (inner == "null") null else JSONObject(inner)
            } catch (e: Exception) { null }
            if (coords != null) {
                val px = coords.optDouble("x").toFloat()
                val py = coords.optDouble("y").toFloat()
                activity.runOnUiThread {
                    val t = android.os.SystemClock.uptimeMillis()
                    val down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, px, py, 0)
                    val up = android.view.MotionEvent.obtain(t, t + 50, android.view.MotionEvent.ACTION_UP, px, py, 0)
                    webView.dispatchTouchEvent(down); webView.dispatchTouchEvent(up)
                    down.recycle(); up.recycle()
                }
                return json(mapOf("result" to "tap-fallback"))
            }
        }
        return json(mapOf("result" to r))
    }

    /**
     * Build a JS snippet that picks the active player (HTML5 video, Wistia,
     * or first visible iframe) and runs a player-specific action.
     *
     * [html5] runs with `v` bound to the chosen <video> element.
     * [wistia] runs with `id` bound to the Wistia video ID string.
     */
    private fun buildPlayerDispatchJs(html5: String, wistia: String): String {
        return """(function(){
            // Any <video> element, even if 0x0 / not yet loaded. YouTube mobile
            // lazy-loads the video source; the element is in the DOM from the start
            // but duration/readyState stay 0 until playback actually starts.
            var videos = [].slice.call(document.querySelectorAll('video'));
            if (videos.length) {
                var v = videos.find(function(v){return !v.paused;}) || videos[0];
                $html5
            }
            var wiList = [].slice.call(document.querySelectorAll('iframe[src*=wistia]'));
            if (wiList.length) {
                var inView = wiList.find(function(ifr){
                    var r = ifr.getBoundingClientRect();
                    return r.width>100 && r.top<window.innerHeight && r.bottom>0 && r.right>0 && r.left<window.innerWidth;
                }) || wiList[0];
                var m = inView.src.match(/iframe\/([^?\/]+)/);
                var id = m ? m[1] : '_all';
                $wistia
            }
            return 'no-player';
        })()"""
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
            // Also clear/set the page-level .muted property on the active player.
            // YouTube auto-starts with <video>.muted=true due to browser autoplay
            // policy — adjusting the Android STREAM_MUSIC volume alone won't make
            // sound come out; we also have to flip video.muted.
            val wantMuted = when (action) {
                "mute" -> true
                "unmute", "up", "max" -> false
                else -> null
            }
            if (wantMuted != null) {
                val js = buildPlayerDispatchJs(
                    html5 = "v.muted = $wantMuted; if(!v.paused) v.play(); return 'html5:muted='+v.muted;",
                    wistia = "window._wq=window._wq||[]; window._wq.push({id:id, onReady:function(v){${if (wantMuted) "v.mute();" else "v.unmute();"}}}); return 'wistia:' + id;"
                )
                webView.evaluateJavascript(js, null)
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
