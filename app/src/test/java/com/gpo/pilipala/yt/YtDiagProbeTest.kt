package com.gpo.pilipala.yt

import com.gpo.pilipala.yt.core.extractor.PawDownloader
import com.gpo.pilipala.yt.core.extractor.YtDiag
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class YtDiagProbeTest {
    @Test
    fun report() {
        Assume.assumeTrue(System.getenv("YT_PROBE") == "1")
        NewPipe.init(PawDownloader(OkHttpClient()), Localization("zh", "CN"), ContentCountry("JP"))
        println(runBlocking { YtDiag.report() })
    }
}
