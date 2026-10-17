package com.gpo.pilipala.feature.reply

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.IntOffset
import com.gpo.pilipala.ui.theme.YoinMotion

/** Yoin spring specs captured in composition so non-composable transition lambdas can use them. */
@Immutable
internal class ReplyMotionSpecs(
    val fade: FiniteAnimationSpec<Float>,
    val scale: FiniteAnimationSpec<Float>,
    val offset: FiniteAnimationSpec<IntOffset>,
)

@Composable
internal fun replyMotion(): ReplyMotionSpecs = ReplyMotionSpecs(
    fade = YoinMotion.defaultEffectsSpec(),
    scale = YoinMotion.fastSpatialSpec(),
    offset = YoinMotion.fastSpatialSpec(),
)

internal fun ReplyMotionSpecs.crossfade(): ContentTransform = fadeIn(fade) togetherWith fadeOut(fade)
