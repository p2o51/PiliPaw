package com.gpo.yoin.symbols

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Springs used by the animated symbols. The defaults follow the Material 3 Expressive
 * motion scheme, so the symbols move like the rest of an Expressive app.
 *
 * Provide your own through [LocalSymbolMotion], for example to switch every symbol to
 * the reduced variant when the system asks for less motion:
 *
 * ```
 * CompositionLocalProvider(LocalSymbolMotion provides SymbolMotion.Reduced) { ... }
 * ```
 *
 * @param reduced loops turn into an opacity breath and morphs lose their overshoot.
 * @param spatial movement of whole glyphs (Expressive default spatial).
 * @param fastSpatial small, lively movement (Expressive fast spatial).
 * @param control state changes of controls such as play / pause (Standard fast spatial).
 * @param effects opacity and fill (default effects).
 * @param slowEffects breathing and fades (slow effects).
 * @param resonance the long, soft decay used when sound stops.
 * @param holdMillis pause between two beats of a waiting loop.
 */
@Immutable
public class SymbolMotion(
    public val reduced: Boolean = false,
    public val spatial: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 380f),
    public val fastSpatial: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.6f, stiffness = 800f),
    public val control: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 1400f),
    public val effects: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 1600f),
    public val slowEffects: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 800f),
    public val resonance: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 60f),
    public val holdMillis: Long = 320L,
) {
    /** Returns a copy with some of the springs replaced. */
    public fun copy(
        reduced: Boolean = this.reduced,
        spatial: FiniteAnimationSpec<Float> = this.spatial,
        fastSpatial: FiniteAnimationSpec<Float> = this.fastSpatial,
        control: FiniteAnimationSpec<Float> = this.control,
        effects: FiniteAnimationSpec<Float> = this.effects,
        slowEffects: FiniteAnimationSpec<Float> = this.slowEffects,
        resonance: FiniteAnimationSpec<Float> = this.resonance,
        holdMillis: Long = this.holdMillis,
    ): SymbolMotion = SymbolMotion(reduced, spatial, fastSpatial, control, effects, slowEffects, resonance, holdMillis)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SymbolMotion) return false
        return reduced == other.reduced &&
            spatial == other.spatial &&
            fastSpatial == other.fastSpatial &&
            control == other.control &&
            effects == other.effects &&
            slowEffects == other.slowEffects &&
            resonance == other.resonance &&
            holdMillis == other.holdMillis
    }

    override fun hashCode(): Int {
        var result = reduced.hashCode()
        result = 31 * result + spatial.hashCode()
        result = 31 * result + fastSpatial.hashCode()
        result = 31 * result + control.hashCode()
        result = 31 * result + effects.hashCode()
        result = 31 * result + slowEffects.hashCode()
        result = 31 * result + resonance.hashCode()
        result = 31 * result + holdMillis.hashCode()
        return result
    }

    override fun toString(): String = "SymbolMotion(reduced=$reduced, holdMillis=$holdMillis)"

    public companion object {
        /** Material 3 Expressive springs. */
        public val Default: SymbolMotion = SymbolMotion()

        /** No travel or overshoot: loops become an opacity breath. */
        public val Reduced: SymbolMotion = SymbolMotion(reduced = true)
    }
}

public val LocalSymbolMotion: ProvidableCompositionLocal<SymbolMotion> =
    staticCompositionLocalOf { SymbolMotion.Default }
