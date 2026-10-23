package com.gpo.pilipala.yt.core.extractor.decoder

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.gpo.pilipala.yt.core.extractor.Yt
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.services.youtube.YoutubeApiDecoder
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptDecoder
import java.io.File

/**
 * Solves YouTube's signature and `n` challenges on the phone, in a WebView's V8, with yt-dlp's
 * EJS solver (`assets/ejs`, Unlicense). Ported from PipePipe's WebViewJavaScriptDecoder
 * (PipePipeClient, GPL-3.0).
 *
 * Installed with [YoutubeApiDecoder.setLocalDecoder]: the extractor asks it first, and on any
 * failure drops it for the rest of the process and falls back to PipePipe's remote decoder
 * (`api.pipepipe.dev`). The current player script is found through `iframe_api`, cached on disk
 * for a day, and loaded into V8 once per player version.
 */
class WebViewJsDecoder(context: Context) : YoutubeJavaScriptDecoder {
    private val app = context.applicationContext
    private val runtime = WebViewRuntime.get(app)
    private val prefs = app.getSharedPreferences("youtube_player_cache", Context.MODE_PRIVATE)

    private var solverLoaded = false
    private var loadedPlayerId: String? = null
    private var preparedPlayerId: String? = null
    private var preparedPlayerCode: String? = null
    private var warmed = false
    /** The runtime's page the solver and player went into; a new page has neither. */
    private var loadedGeneration = -1L

    /** Loads V8, the solver and the current player ahead of the first video (background thread). */
    @Synchronized
    fun prewarm() {
        if (warmed) return
        warmed = true
        val start = SystemClock.elapsedRealtime()
        try {
            val data = getPlayerData("")
            decodeBatch(data.playerId, emptyList(), emptyList())
            Log.i(Tag, "prewarm ${SystemClock.elapsedRealtime() - start}ms player=${data.playerId}")
        } catch (e: ParsingException) {
            Log.w(Tag, "prewarm failed", e)
        }
    }

    @Synchronized
    override fun getPlayerData(videoId: String): YoutubeJavaScriptDecoder.PlayerData {
        try {
            val cachedId = prefs.getString("playerId", null)
            val cachedTimestamp = prefs.getInt("signatureTimestamp", 0)
            if (cachedId != null && cachedTimestamp != 0 && System.currentTimeMillis() < prefs.getLong("expiresAt", 0)) {
                val file = playerFile(cachedId)
                if (file.isFile) {
                    preparedPlayerId = cachedId
                    preparedPlayerCode = file.readText()
                    return YoutubeJavaScriptDecoder.PlayerData(cachedId, cachedTimestamp)
                }
            }
            val iframe = Yt.downloader.get(IframeUrl).responseBody()
            val playerId = PlayerPattern.find(iframe)?.groupValues?.get(1) ?: throw ParsingException("No YouTube player id")
            val file = playerFile(playerId)
            val code = if (file.isFile) file.readText() else fetchAndCache(playerId, file)
            val timestamp = TimestampPattern.find(code)?.groupValues?.get(1)?.toInt() ?: throw ParsingException("No signature timestamp")
            preparedPlayerId = playerId
            preparedPlayerCode = code
            prefs.edit()
                .putString("playerId", playerId)
                .putInt("signatureTimestamp", timestamp)
                .putLong("expiresAt", System.currentTimeMillis() + PlayerCacheTtlMs)
                .apply()
            return YoutubeJavaScriptDecoder.PlayerData(playerId, timestamp)
        } catch (e: ParsingException) {
            throw e
        } catch (e: Exception) {
            throw ParsingException("Could not load the YouTube player", e)
        }
    }

    @Synchronized
    override fun decodeBatch(
        playerId: String,
        signatures: List<String>?,
        throttlingParameters: List<String>?,
    ): YoutubeApiDecoder.BatchDecodeResult {
        try {
            ensureSolver()
            val hasN = !throttlingParameters.isNullOrEmpty()
            val hasSig = !signatures.isNullOrEmpty()
            val requests = JSONArray()
            if (hasN) requests.put(request("n", throttlingParameters!!))
            if (hasSig) requests.put(request("sig", signatures!!))
            if (playerId != loadedPlayerId) {
                val code = if (playerId == preparedPlayerId && preparedPlayerCode != null) preparedPlayerCode!! else fetchPlayer(playerId)
                upload(playerId, code)
            }
            if (playerId == preparedPlayerId) {
                preparedPlayerId = null
                preparedPlayerCode = null
            }
            val wrapper = evaluate(JSONObject().put("playerId", playerId).put("requests", requests))
            Log.i(Tag, "player=$playerId cold=${wrapper.optBoolean("cold")} v8=${wrapper.optLong("elapsedMs")}ms n=${throttlingParameters?.size ?: 0} sig=${signatures?.size ?: 0}")
            return parse(wrapper, hasN, hasSig)
        } catch (e: ParsingException) {
            Log.e(Tag, "decode failed for player=$playerId", e)
            throw e
        } catch (e: Exception) {
            Log.e(Tag, "decode failed for player=$playerId", e)
            throw ParsingException("Local decoding failed", e)
        }
    }

    private fun ensureSolver() {
        runtime.ensureReady(TimeoutMs, "V8 initialization")
        if (runtime.generation != loadedGeneration) {
            solverLoaded = false
            loadedPlayerId = null
        }
        if (solverLoaded) return
        runtime.evaluateBlocking(
            "if(!Object.hasOwn){Object.hasOwn=function(o,p){return Object.prototype.hasOwnProperty.call(o,p)}};" +
                "if(!Array.prototype.at){Array.prototype.at=function(i){i=Math.trunc(i)||0;if(i<0)i+=this.length;return this[i]}};" +
                runtime.loadAsset("ejs/yt.solver.polyfills.es5.js") +
                runtime.loadAsset("ejs/yt.solver.lib.es5.min.js") +
                ";var meriyah=lib.meriyah,astring=lib.astring;true",
            TimeoutMs,
            "V8 library initialization",
        )
        val ok = runtime.evaluateBlocking(
            runtime.loadAsset("ejs/yt.solver.core.es5.min.js") + ";typeof jsc==='function'",
            TimeoutMs,
            "V8 core initialization",
        )
        if (ok != "true") throw ParsingException("EJS initialization returned $ok")
        solverLoaded = true
        loadedGeneration = runtime.generation
    }

    /** The first call for a player preprocesses it into solver functions; later calls only run them. */
    private fun evaluate(request: JSONObject): JSONObject {
        val script = "(function(i){try{var s=performance.now(),cold=window.__ejsPlayerId!==i.playerId;" +
            "if(cold){var r=jsc({type:'player',player:window.__ejsPlayer,requests:i.requests,output_preprocessed:true});" +
            "window.__ejsSolvers={};Function('_result',r.preprocessed_player)(window.__ejsSolvers);" +
            "window.__ejsPlayerId=i.playerId;window.__ejsPlayer=null;delete r.preprocessed_player}" +
            "else{r={type:'result',responses:i.requests.map(function(q){var f=window.__ejsSolvers[q.type];" +
            "if(!f)return{type:'error',error:'Failed to extract '+q.type+' function'};" +
            "try{var d={};q.challenges.forEach(function(v){d[v]=f(v)});return{type:'result',data:d}}" +
            "catch(e){return{type:'error',error:e instanceof Error?e.message+'\\n'+e.stack:String(e)}}})}}" +
            "return JSON.stringify({ok:true,cold:cold,elapsedMs:Math.round(performance.now()-s),result:r})}" +
            "catch(e){return JSON.stringify({ok:false,error:String(e),stack:e&&e.stack})}})($request)"
        val value = runtime.evaluateBlocking(script, TimeoutMs, "V8 decoding")
        if (value == null || value == "null") throw ParsingException("V8 returned null")
        // evaluateJavascript hands back the string JSON-encoded: unwrap it, then parse.
        val wrapper = JSONObject(JSONArray("[$value]").getString(0))
        if (!wrapper.optBoolean("ok")) {
            throw ParsingException("V8 error: ${wrapper.optString("error")}\n${wrapper.optString("stack")}")
        }
        return wrapper
    }

    /** The player script into V8 in chunks (one huge evaluateJavascript argument is slow or fails). */
    private fun upload(playerId: String, code: String) {
        raw("window.__ejsPlayer=''")
        var start = 0
        while (start < code.length) {
            val end = minOf(start + ChunkChars, code.length)
            raw("window.__ejsPlayer+=" + JSONObject.quote(code.substring(start, end)))
            start = end
        }
        raw("if(window.__ejsPlayer.length!==${code.length}){throw new Error(window.__ejsPlayer.length)}")
        loadedPlayerId = playerId
    }

    private fun raw(script: String) {
        val value = runtime.evaluateBlocking("(function(){try{$script;return ''}catch(e){return String(e)}})()", TimeoutMs, "V8 player upload")
        if (value != null && value != "\"\"") throw ParsingException("Could not upload the player: $value")
    }

    private fun fetchPlayer(playerId: String): String = try {
        Yt.downloader.get(PlayerUrl.format(playerId)).responseBody()
    } catch (e: Exception) {
        throw ParsingException("Could not fetch the YouTube player", e)
    }

    private fun fetchAndCache(playerId: String, file: File): String {
        val code = fetchPlayer(playerId)
        val tmp = File(file.path + ".tmp")
        tmp.writeText(code)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw ParsingException("Could not cache the YouTube player")
        }
        app.filesDir.listFiles()?.filter { it.name.startsWith(CachePrefix) && it != file }?.forEach { it.delete() }
        return code
    }

    private fun playerFile(playerId: String) = File(app.filesDir, "$CachePrefix$playerId.js")

    private fun parse(wrapper: JSONObject, hasN: Boolean, hasSig: Boolean): YoutubeApiDecoder.BatchDecodeResult {
        val result = wrapper.getJSONObject("result")
        if (result.optString("type") != "result") throw ParsingException("Decoder returned $result")
        val sigs = HashMap<String, String>()
        val ns = HashMap<String, String>()
        val responses = result.getJSONArray("responses")
        for (i in 0 until responses.length()) {
            val response = responses.getJSONObject(i)
            if (response.optString("type") != "result") throw ParsingException("Decoder response returned $response")
            // Requests go in as n first, then sig.
            val into = if (hasN && (i == 0 || !hasSig)) ns else sigs
            val data = response.getJSONObject("data")
            data.keys().forEach { into[it] = data.getString(it) }
        }
        return YoutubeApiDecoder.BatchDecodeResult(sigs, ns)
    }

    private fun request(type: String, values: List<String>) = JSONObject().put("type", type).put("challenges", JSONArray(values))

    private companion object {
        const val Tag = "WebViewJsDecoder"
        const val PlayerUrl = "https://www.youtube.com/s/player/%s/player_ias.vflset/en_US/base.js"
        const val IframeUrl = "https://www.youtube.com/iframe_api"
        const val TimeoutMs = 30_000L
        const val PlayerCacheTtlMs = 24L * 60 * 60 * 1000
        const val CachePrefix = "youtube-player-"
        const val ChunkChars = 128 * 1024
        val PlayerPattern = Regex("""player\\/([a-z0-9]{8})\\/""")
        val TimestampPattern = Regex("""signatureTimestamp\s*[:=]\s*(\d+)""")
    }
}
