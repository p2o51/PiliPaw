package com.gpo.pilipala.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FmtEntitiesTest {
    @Test fun hexAndDecimalReferences() {
        assertEquals("AFTER'45", Fmt.decodeEntities("AFTER&#x27;45"))
        assertEquals("AFTER'45", Fmt.decodeEntities("AFTER&#X27;45"))
        assertEquals("it's \"x\"", Fmt.decodeEntities("it&#39;s &#34;x&#34;"))
        assertEquals("😀", Fmt.decodeEntities("&#x1F600;"))
    }

    @Test fun namedReferences() {
        assertEquals("Tom & Jerry", Fmt.decodeEntities("Tom &amp; Jerry"))
        assertEquals("<a> \"b\" 'c' d", Fmt.decodeEntities("&lt;a&gt; &quot;b&quot; &apos;c&apos;&nbsp;d"))
    }

    @Test fun decodesOncePerPass() {
        assertEquals("&quot;", Fmt.decodeEntities("&amp;quot;"))
        assertEquals("&nbsp;", Fmt.decodeEntities("&amp;nbsp;"))
        assertEquals("  ", Fmt.decodeEntities(Fmt.decodeEntities("&amp;nbsp;&amp;nbsp;")))
    }

    @Test fun leavesUnknownAndInvalidAlone() {
        assertEquals("&foo; &#xFFFFFFFF; &#0; & ; &amp", Fmt.decodeEntities("&foo; &#xFFFFFFFF; &#0; & ; &amp"))
        val plain = "no entities here"
        assertSame(plain, Fmt.decodeEntities(plain))
    }

    @Test fun stripTagsThenDecode() {
        assertEquals(
            "AFTER'45",
            Fmt.stripTags("<em class=\"keyword\">AFTER</em>&#x27;<em class=\"keyword\">45</em>"),
        )
        assertEquals("R&B 合集", Fmt.stripTags("<em class=\"keyword\">R&amp;B</em> 合集"))
    }
}
