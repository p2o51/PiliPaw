package com.gpo.pilipala.ui.components

import coil3.EventListener
import coil3.decode.DataSource
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.transition.CrossfadeTransition
import coil3.transition.Transition
import coil3.transition.TransitionTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Images on their way: a page coming back from under another waits for its covers before it
 * shows itself (navigation/transition), so they never come in as blank tiles over its snapshot.
 */
object ImageLoads : EventListener() {
    private val _inFlight = MutableStateFlow(0)
    val inFlight: StateFlow<Int> = _inFlight.asStateFlow()

    override fun onStart(request: ImageRequest) = _inFlight.update { it + 1 }
    override fun onSuccess(request: ImageRequest, result: SuccessResult) = done()
    override fun onError(request: ImageRequest, result: ErrorResult) = done()
    override fun onCancel(request: ImageRequest) = done()

    private fun done() = _inFlight.update { (it - 1).coerceAtLeast(0) }
}

/**
 * Coil's crossfade, only for a picture fetched from the network: one already on the device
 * (disk or memory) is simply there, so a page drawn again shows its covers at once instead of
 * fading each in from an empty tile.
 */
object NetworkCrossfade : Transition.Factory {
    private val crossfade = CrossfadeTransition.Factory()

    override fun create(target: TransitionTarget, result: ImageResult): Transition =
        if (result is SuccessResult && result.dataSource != DataSource.NETWORK) {
            Transition.Factory.NONE.create(target, result)
        } else {
            crossfade.create(target, result)
        }
}
