package com.gpo.pilipala.yt.feature.player

import com.gpo.pilipala.feature.player.*

import androidx.compose.runtime.Immutable
import com.gpo.pilipala.yt.core.settings.AppSettings
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.sponsorblock.SponsorBlockApiSettings
import org.schabi.newpipe.extractor.sponsorblock.SponsorBlockCategory
import org.schabi.newpipe.extractor.stream.StreamInfo

/** A chapter: starts at [startMs], titled [title], with the creator's still at [preview] if any. */
@Immutable
class Chapter(val startMs: Long, val title: String, val preview: String)

/** A SponsorBlock segment [startMs] until [endMs] of [category]. */
@Immutable
class SkipSegment(val id: String, val startMs: Long, val endMs: Long, val category: SbCategory)

/**
 * The SponsorBlock categories PipePaw shows, each with its label and whether it is skipped by
 * default (paid promotion, self promotion and subscribe reminders; the rest are only marked).
 */
enum class SbCategory(val api: SponsorBlockCategory, val label: String, val skipByDefault: Boolean) {
    Sponsor(SponsorBlockCategory.SPONSOR, "赞助", true),
    SelfPromo(SponsorBlockCategory.SELF_PROMO, "自我推广", true),
    Interaction(SponsorBlockCategory.INTERACTION, "互动提醒", true),
    Intro(SponsorBlockCategory.INTRO, "片头", false),
    Outro(SponsorBlockCategory.OUTRO, "片尾", false),
    ;

    /** 自动跳过 for this category (设置 › SponsorBlock). */
    val skip: AppSettings.Key<Boolean> = AppSettings.register("sb_skip_${api.apiName}", skipByDefault)

    companion object {
        fun of(c: SponsorBlockCategory?): SbCategory? = entries.firstOrNull { it.api == c }
    }
}

object Segments {
    /** SponsorBlock on or off (设置 › SponsorBlock); off fetches nothing. */
    val enabled: AppSettings.Key<Boolean> = AppSettings.register("sb_enabled", true)

    /**
     * Tells the extractor what to fetch with every video: the SponsorBlock categories above when
     * enabled, and Return YouTube Dislike's counts (always: one light request).
     */
    fun configure() {
        val yt = ServiceList.YouTube
        yt.isFetchDislike = true
        yt.sponsorBlockApiSettings = if (!enabled.value) null else SponsorBlockApiSettings().apply {
            apiUrl = "https://sponsor.ajay.app/api/"
            includeSponsorCategory = true
            includeSelfPromoCategory = true
            includeInteractionCategory = true
            includeIntroCategory = true
            includeOutroCategory = true
        }
    }

    fun chapters(info: StreamInfo?): List<Chapter> =
        info?.streamSegments.orEmpty()
            .map { Chapter(it.startTimeSeconds * 1000L, it.title.orEmpty(), it.previewUrl.orEmpty()) }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs }

    fun skips(info: StreamInfo?): List<SkipSegment> =
        info?.sponsorBlockSegments.orEmpty().mapNotNull { s ->
            val cat = SbCategory.of(s.category) ?: return@mapNotNull null
            if (s.endTime <= s.startTime) return@mapNotNull null
            // The extractor already gives milliseconds.
            SkipSegment(s.uuid.orEmpty(), s.startTime.toLong(), s.endTime.toLong(), cat)
        }.sortedBy { it.startMs }
}

/** The chapter that covers [ms], if any. */
fun List<Chapter>.at(ms: Long): Chapter? = lastOrNull { ms >= it.startMs }
