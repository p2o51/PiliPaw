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

    /** Dock in the form the video's page was opened from (the window at its size and corner …). */
    data class Restore(val form: Any) : HostAction
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

    /** The form the docked picture had when tapped open: the video's page goes back to it. */
    var remembered: Pair<PlayerSlot, Any>? = null
        private set
    private var pendingForm: Any? = null

    /** The docked picture was tapped to open its video, in [form]; the open that follows keeps it. */
    fun openingFromDock(form: Any) {
        pendingForm = form
    }

    /** A page made or took back a player, or YouTube's player opened. */
    fun opened(slot: PlayerSlot): List<HostAction> {
        val out = mutableListOf<HostAction>(HostAction.ResetDock)
        // Opened from its own docked picture: remembered for its return; any other open forgets.
        val form = pendingForm
        pendingForm = null
        remembered = if (form != null && docked == slot) slot to form else null
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
        remembered?.let { (s, form) -> if (s == slot) out += HostAction.Restore(form) }
        remembered = null
        docked = slot
        front = null
        return out
    }

    /** [slot] was released (swiped away, closed, its page went without docking). */
    fun released(slot: PlayerSlot) {
        if (docked == slot) docked = null
        if (front == slot) front = null
        if (remembered?.first == slot) remembered = null
    }
}

/** The app's one [PlayerHostMachine], wired to both players. Main thread only. */
internal object PlayerHost {
    private val machine = PlayerHostMachine()

    val docked: PlayerSlot? get() = machine.docked

    /** The player on a page (or YouTube's, open) right now. */
    val front: PlayerSlot? get() = machine.front

    fun opened(slot: PlayerSlot) {
        MiniLog.event("opened", slot.label())
        apply(machine.opened(slot))
    }

    fun started(slot: PlayerSlot) = apply(machine.started(slot))

    fun dock(slot: PlayerSlot): Boolean {
        val actions = machine.dock(slot)
        MiniLog.event(if (actions == null) "dock refused" else "dock", slot.label())
        if (actions == null) return false
        apply(actions)
        return true
    }

    /** The form [slot]'s page was opened from, while it is open (where its return lands). */
    fun remembered(slot: PlayerSlot): Any? = machine.remembered?.takeIf { it.first == slot }?.second

    fun openingFromDock(form: Any) = machine.openingFromDock(form)

    private fun PlayerSlot.label() = when (this) {
        PlayerSlot.YouTube -> "YouTube"
        is PlayerSlot.Bili -> "B站@" + Integer.toHexString(System.identityHashCode(player))
    }

    fun released(slot: PlayerSlot) {
        MiniLog.event("released", slot.label())
        // The bar's video went: nothing of its window (size, place) outlives it.
        if (machine.docked == slot) KeepPlaying.resetDock()
        machine.released(slot)
    }

    private fun apply(actions: List<HostAction>) {
        for (a in actions) when (a) {
            HostAction.ResetDock -> KeepPlaying.resetDock()
            is HostAction.Restore -> (a.form as? DockForm)?.let(KeepPlaying::restore)
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
