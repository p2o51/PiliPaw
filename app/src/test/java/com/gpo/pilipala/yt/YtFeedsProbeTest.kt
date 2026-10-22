package com.gpo.pilipala.yt

import com.gpo.pilipala.yt.core.extractor.PawDownloader
import com.gpo.pilipala.yt.core.extractor.YtFeeds
import com.grack.nanojson.JsonObject
import okhttp3.OkHttpClient
import org.junit.Assume
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

/** Network probe (run by hand, YT_PROBE=1): which renderers a browse page carries today. */
class YtFeedsProbeTest {
    @Test
    fun renderers() {
        Assume.assumeTrue(System.getenv("YT_PROBE") == "1")
        NewPipe.init(PawDownloader(OkHttpClient()), Localization("zh", "CN"), ContentCountry("JP"))
        val browse = YtFeeds::class.java.getDeclaredMethod("browse", String::class.java, String::class.java).apply { isAccessible = true }
        for (id in listOf("FEwhat_to_watch", "VLPLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI", "FEtrending")) {
            val json = browse.invoke(YtFeeds, id, null) as JsonObject
            val counts = HashMap<String, Int>()
            YtFeeds.walk(json) { k, _ -> if (k.endsWith("Renderer") || k.endsWith("ViewModel")) counts[k] = (counts[k] ?: 0) + 1 }
            println("PROBE $id " + counts.entries.sortedByDescending { it.value }.take(12).joinToString { "${it.key}=${it.value}" })
            val collector = org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector(org.schabi.newpipe.extractor.ServiceList.YouTube.serviceId)
            val parser = org.schabi.newpipe.extractor.ServiceList.YouTube.getTimeAgoParser(NewPipe.getPreferredLocalization())
            var types = HashMap<String, Int>()
            var errors = 0
            var firstError: Throwable? = null
            YtFeeds.walk(json) { k, v ->
                if (k == "lockupViewModel") {
                    val t = v.getString("contentType"); types[t] = (types[t] ?: 0) + 1
                    if (t == "LOCKUP_CONTENT_TYPE_VIDEO") runCatching { collector.commit(org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeLockupStreamInfoItemExtractor(v, parser)) }.onFailure { errors++; if (firstError == null) firstError = it }
                }
            }
            println("PROBE  lockup types=$types parsed=${collector.items.size} collectorErrors=${collector.errors.size} first=${collector.errors.firstOrNull()} commitErr=$errors $firstError")
        }
    }
}
