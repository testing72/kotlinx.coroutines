/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("unused")
package kotlinx.coroutines.linearizability

import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.internal.*
import kotlinx.coroutines.internal.SegmentQueueSynchronizer.Mode.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*
import kotlin.coroutines.*

internal class SimpleCountDownLatch(count: Int) : SegmentQueueSynchronizer<Unit>(ASYNC) {
    private val count = atomic(count)
    private val waiters = atomic(0)

    fun countDown() {
        val r = count.decrementAndGet()
        if (r <= 0) releaseWaiters()
    }

    private fun releaseWaiters() {
        val w = waiters.getAndUpdate {
            // is the mark set?
            if (it and DONE_MARK != 0) return
            it or DONE_MARK
        }
        repeat(w) { tryResume(Unit) }
    }

    suspend fun await() {
        // check whether the count has been reached zero
        if (waiters.value and DONE_MARK != 0) return
        // add a new waiter (checking the counter again)
        val w = waiters.incrementAndGet()
        if (w and DONE_MARK != 0) return
        suspendCancellableCoroutine<Unit> { suspend(it) }
    }
}
private const val DONE_MARK = 1 shl 31

internal class SimpleConflatedChannel<T : Any> : SegmentQueueSynchronizer<T>(SYNC) {
    private val state = atomic<Any?>(null) // null | element | WAITERS
    private val waiters = atomic(0)

    fun send(x: T) {
        retry@while (true) {
            val e = state.value
            when {
                e === null -> {
                    // check whether the marker should be set
                    // since it can be incorrectly re-set by this
                    // or another `send`
                    if (setMarkerIfNeeded()) continue@retry
                    // try to set the element
                    if (state.compareAndSet(null, x)) return
                }
                e === WAITERS -> {
                    // check whether there exist a waiter
                    if (decWaiters()) {
                        // try to resume the first waiter synchronously;
                        // otherwise, an implementation is not linearizable
                        if (tryResume(x)) return
                    } else {
                        // the marker should be re-set
                        state.compareAndSet(WAITERS, null)
                        continue@retry
                    }
                }
                else -> {
                    // try to conflate the element
                    if (state.compareAndSet(e, x)) return
                }
            }
        }
    }

    fun tryReceive(): T? {
        state.loop { s ->
            if (s === null || s === WAITERS) return null
            // an element is stored
            if (state.compareAndSet(s, null)) return s as T
        }
    }

    suspend fun receive(): T {
        while (true) {
            // Try to retrieve an element
            val e = tryReceive()
            if (e !== null) return e
            // Try to increment the number of waiters, set the marker
            // to the `state`, and suspend.
            waiters.incrementAndGet()
            val suspend = setMarker() || !decWaiters()
            if (suspend) return receiveSlowPath()
        }
    }

    private suspend fun receiveSlowPath()  = suspendAtomicCancellableCoroutineReusable<T> sc@ { cont ->
        while (true) {
            // Try to retrieve an element
            val e = tryReceive()
            if (e !== null) {
                cont.resume(e)
                return@sc
            }
            // Try to increment the number of waiters, set the marker
            // to the `state`, and suspend.
            waiters.incrementAndGet()
            val suspend = setMarker() || !decWaiters()
            if (suspend && suspend(cont)) return@sc
        }
    }

    private fun setMarkerIfNeeded(): Boolean {
        if (waiters.value == 0) return false
        state.compareAndSet(null, WAITERS)
        return true
    }

    private fun decWaiters() : Boolean {
        waiters.loop { w ->
            if (w == 0) return false
            if (waiters.compareAndSet(w, w - 1)) return true
        }
    }

    private fun setMarker(): Boolean {
        state.loop { s ->
            if (s === WAITERS) return true
            if (s === null) {
                if (state.compareAndSet(null, WAITERS)) return true
            }
            return false // an element is stored
        }
    }
}
private val WAITERS = Symbol("WAITERS")

internal class SimpleConflatedChannelLCStressTest {
    val c = SimpleConflatedChannel<Int>()

    @Operation
    fun send(e: Int) = c.send(e)

    @Operation(cancellableOnSuspension = true)
    suspend fun receive() = c.receive()

    @Operation
    fun tryReceive() = c.tryReceive()

    @Test
    fun test() = LCStressOptionsDefault()
        .sequentialSpecification(SimpleConflatedChannelIntSpec::class.java)
        .threads(3)
        .invocationsPerIteration(100_000)
        .actorsPerThread(4)
        .logLevel(LoggingLevel.INFO)
        .check(this::class)
}

internal class SimpleConflatedChannelIntSpec : VerifierState() {
    private val waiters = ArrayList<CancellableContinuation<Int>>()
    private var element = Int.MAX_VALUE

    fun send(e: Int) {
        while (true) {
            if (waiters.isEmpty()) {
                element = e
                return
            } else {
                val w = waiters.removeAt(0)
                val token = w.tryResume(e) ?: continue
                w.completeResume(token)
                return
            }
        }
    }

    suspend fun receive(): Int {
        return tryReceive() ?: suspendAtomicCancellableCoroutine { cont ->
            waiters.add(cont)
        }
    }

    fun tryReceive(): Int? {
        return if (element == Int.MAX_VALUE) null
               else element.also { element = Int.MAX_VALUE }
    }

    override fun extractState() = element
}
