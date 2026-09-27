package com.dany.presence.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filterIsInstance

/** The one channel. Thread-safe; emit never suspends (drops the oldest on overflow). */
class Bus {
    private val flow = MutableSharedFlow<Signal>(replay = 0, extraBufferCapacity = 256, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    val signals: SharedFlow<Signal> = flow

    fun emit(s: Signal) { flow.tryEmit(s) }

    inline fun <reified T : Signal> of() = signals.filterIsInstance<T>()
}
