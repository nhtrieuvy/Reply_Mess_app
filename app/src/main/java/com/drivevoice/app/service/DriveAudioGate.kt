package com.drivevoice.app.service

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex

internal class DriveAudioGate {
    private val mutex = Mutex()

    fun tryAcquire(): Closeable? {
        val owner = Any()
        if (!mutex.tryLock(owner)) return null
        val released = AtomicBoolean()
        return Closeable { if (released.compareAndSet(false, true)) mutex.unlock(owner) }
    }
}

internal val driveAudioGate = DriveAudioGate()
