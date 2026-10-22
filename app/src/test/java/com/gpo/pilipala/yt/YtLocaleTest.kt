package com.gpo.pilipala.yt

import com.gpo.pilipala.yt.core.extractor.Yt
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class YtLocaleTest {
    @Test
    fun hlNeverEndsInDash() {
        assertEquals("zh-CN", Yt.localization(Locale.forLanguageTag("zh-Hans")).localizationCode)
        assertEquals("zh-TW", Yt.localization(Locale.forLanguageTag("zh-Hant")).localizationCode)
        assertEquals("zh-HK", Yt.localization(Locale.forLanguageTag("zh-Hant-HK")).localizationCode)
        assertEquals("ja", Yt.localization(Locale.forLanguageTag("ja")).localizationCode)
        assertEquals("en-US", Yt.localization(Locale.US).localizationCode)
    }
}
