package com.gpo.pilipala.feature.sources

import com.gpo.pilipala.feature.player.PiliPlayerController
import com.gpo.pilipala.yt.feature.player.Player

/** A player the app can show: YouTube's one player, or one Bilibili page's controller. */
internal sealed interface PlayerSlot {
    data object YouTube : PlayerSlot

    /** Equal only for the same controller (it does not override equals). */
    data class Bili(val player: Any) : PlayerSlot
}

internal sealed interface HostAction {
    /** Stop and free that player (its mini bar goes with it). */
    data class Release(val slot: PlayerSlot) : HostAction

    /** The mini bar's size, place and fold go back to the plain bar. */
    data object ResetDock : HostAction
}

/**
 * Which player is in front (on a page, or YouTube's player open) and which one is docked in the
 * mini bar. The rule: at most one player plays for the user. Opening or starting any video
 * releases a docked other one; a page that leaves docks its player only if nothing newer came
 * to the front meanwhile (a page covered by the next video page leaves after that page opened).
 * Pure: the caller carries out the actions.
 */
internal class PlayerHostMachine {
    var front: PlayerSlot? = null
        private set
    var docked: PlayerSlot? = null
        private set

    /** A page made or took back a player, or YouTube's player opened. */
    fun opened(slot: PlayerSlot): List<HostAction> {
        val out = mutableListOf<HostAction>(HostAction.ResetDock)
        docked?.let { if (it != slot) out += HostAction.Release(it) }
        docked = null
        // YouTube's player is an overlay over any page: a Bilibili page opening replaces it. A
        // Bilibili page under YouTube's player stays (it is still on the stack), paused.
        if (front == PlayerSlot.YouTube && slot != PlayerSlot.YouTube) out += HostAction.Release(PlayerSlot.YouTube)
        front = slot
        return out
    }

    /** A player began to play: from its page, or from the mini bar (then nothing changes). */
    fun started(slot: PlayerSlot): List<HostAction> {
        if (docked == slot) return emptyList()
        val out = mutableListOf<HostAction>()
        docked?.let { out += HostAction.Release(it) }
        docked = null
        front = slot
        return out
    }

    /**
     * [slot]'s page left (or YouTube's player collapsed) and it would keep playing in the bar.
     * Null: refused, the caller releases it. Otherwise what to do first (a docked other one goes).
     */
    fun dock(slot: PlayerSlot): List<HostAction>? {
        if (front != slot) return null
        val out = mutableListOf<HostAction>()
        docked?.let { if (it != slot) out += HostAction.Release(it) }
        docked = slot
        front = null
        return out
    }

    /** [slot] was released (swiped away, closed, its page went without docking). */
    fun released(slot: PlayerSlot) {
        if (docked == slot) docked = null
        if (front == slot) front = null
    }
}

/** The app's one [PlayerHostMachine], wired to both players. Main thread only. */
internal object PlayerHost {
    private val machine = PlayerHostMachine()

    val docked: PlayerSlot? get() = machine.docked

    fun opened(slot: PlayerSlot) = apply(machine.opened(slot))

    fun started(slot: PlayerSlot) = apply(machine.started(slot))

    fun dock(slot: PlayerSlot): Boolean {
        val actions = machine.dock(slot) ?: return false
        apply(actions)
        return true
    }

    fun released(slot: PlayerSlot) {
        // The bar's video went: nothing of its window (size, place) outlives it.
        if (machine.docked == slot) KeepPlaying.resetDock()
        machine.released(slot)
    }

    private fun apply(actions: List<HostAction>) {
        for (a in actions) when (a) {
            HostAction.ResetDock -> KeepPlaying.resetDock()
            is HostAction.Release -> when (val s = a.slot) {
                PlayerSlot.YouTube -> if (Player.session != null) Player.close()
                is PlayerSlot.Bili -> {
                    val c = s.player as PiliPlayerController
                    if (BiliKeeper.held === c) BiliKeeper.close() else machine.released(s)
                }
            }
        }
    }
}
