package com.gpo.pilipala.feature.reply

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Comment list for any subject. CONTRACT — signature is fixed.
 * type: 1 = video, 11 = dynamic (images), 17 = dynamic (text/forward), 12 = article, 14 = audio.
 * [header] lets the host put its own content above the comments in the same
 * LazyColumn (dynamic detail). [onTimestampClick] receives seconds when a
 * "03:21" link in a video comment is tapped.
 */
@Composable
fun ReplyList(
    oid: Long,
    type: Int,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    header: (LazyListScope.() -> Unit)? = null,
    onTimestampClick: ((seconds: Long) -> Unit)? = null,
) {
    LazyColumn(modifier = modifier, contentPadding = contentPadding) {
        header?.invoke(this)
    }
}
