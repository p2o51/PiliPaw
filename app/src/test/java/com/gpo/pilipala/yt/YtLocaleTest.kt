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
        assertEquals("zh-CN", Yt.localization(Locale.forLanguageTag("zh-Hans-JP")).localizationCode)
        assertEquals("zh-CN", Yt.localization(Locale.forLanguageTag("zh-SG")).localizationCode)
        assertEquals("ja", Yt.localization(Locale.forLanguageTag("ja-JP")).localizationCode)
        assertEquals("en", Yt.localization(Locale.forLanguageTag("en-JP")).localizationCode)
        assertEquals("en", Yt.localization(Locale.US).localizationCode)
        assertEquals("en-GB", Yt.localization(Locale.UK).localizationCode)
        assertEquals("es-419", Yt.localization(Locale.forLanguageTag("es-MX")).localizationCode)
        assertEquals("pt-BR", Yt.localization(Locale.forLanguageTag("pt-BR")).localizationCode)
    }
}
