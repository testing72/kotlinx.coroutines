/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("unused")
package kotlinx.coroutines.linearizability

import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.internal.*
import kotlinx.coroutines.internal.SegmentQueueSynchronizer.Mode.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*
import kotlin.coroutines.*

/*
  This test suite is not only but tests, but also provides a set of example
  on how the `SegmentQueueSynchronizer` abstraction can be used for different
  synchronization primitives.
 */

internal class SimpleMutex : SegmentQueueSynchronizer<Unit>(ASYNC) {
    private val state = atomic(-1) // -1 -- locked, x>=0 -- number of waiters

    fun isLocked() = state.value != -1

    suspend fun lock() {
        val s = state.getAndIncrement()
        // Is the lock acquired?
        if (s == -1) return
        // Suspend otherwise
        suspendAtomicCancellableCoroutineReusable<Unit> { cont ->
            check(suspend(cont)) { "Should not fail in ASYNC mode" }
        }
    }

    fun release() {
        while (true) {
            val s = state.getAndUpdate { cur ->
                if (cur == -1) throw IllegalStateException("This mutex is unlocked")
                cur - 1
            }
            if (s == 0) return // no waiters
            if (tryResume(Unit)) return
        }
    }
}

class SimpleMutexLCSressTest : VerifierState() {
    private val m = SimpleMutex()

    @Operation
    suspend fun lock() = m.lock()

    @Operation(handleExceptionsAsResult = [IllegalStateException::class])
    fun release() = m.release()

    override fun extractState() = m.isLocked()

    @Test
    fun test() = LCStressOptionsDefault()
        .actorsBefore(0)
        .actorsAfter(0)
        .check(this::class)

}

class SimpleMutexStressTest {
    @Test
    fun testSimple() = runBlocking {
        val m = SimpleMutex()
        check(!m.isLocked())
        m.lock()
        check(m.isLocked())
        m.release()
        check(!m.isLocked())
    }

    @Test
    fun test() = runBlocking {
        val t = 10
        val n = 100_000
        val m = SimpleMutex()
        var c = 0
        val jobs = (1..t).map { GlobalScope.launch {
            repeat(n) {
                m.lock()
                c++
                m.release()
            }
        } }
        jobs.forEach { it.join() }
        assert(c == n * t)
    }
}


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
        if (remaining() == 0) return
        // add a new waiter (checking the counter again)
        val w = waiters.incrementAndGet()
        if (w and DONE_MARK != 0) return
        suspendCancellableCoroutine<Unit> { suspend(it) }
    }

    fun remaining(): Int = count.value.coerceAtLeast(0)
}
private const val DONE_MARK = 1 shl 31

abstract class AbstractSimpleCountDownLatchLCStressTest(count: Int) : VerifierState() {
    private val cdl = SimpleCountDownLatch(count)

    @Operation
    fun countDown() = cdl.countDown()

    @Operation
    fun remaining() = cdl.remaining()

    @Operation
    suspend fun await() = cdl.await()

    override fun extractState() = remaining()

    @Test
    fun test() = LCStressOptionsDefault()
        .actorsBefore(0)
        .actorsAfter(0)
        .check(this::class)
}
class SimpleCountDownLatch1LCStressTest : AbstractSimpleCountDownLatchLCStressTest(1)
class SimpleCountDownLatch2LCStressTest : AbstractSimpleCountDownLatchLCStressTest(2)


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
        if (suspend(cont)) return@sc
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

class SimpleConflatedChannelLCStressTest {
    private val c = SimpleConflatedChannel<Int>()

    @Operation
    fun send(e: Int) = c.send(e)

    @Operation(cancellableOnSuspension = true)
    suspend fun receive() = c.receive()

    @Operation
    fun tryReceive() = c.tryReceive()

    @Test
    fun test() = LCStressOptionsDefault()
        .sequentialSpecification(SimpleConflatedChannelIntSpec::class.java)
        .check(this::class)
}

class SimpleConflatedChannelIntSpec : VerifierState() {
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


internal class SimpleBarrier(private val parties: Int) : SegmentQueueSynchronizer<Boolean>(ASYNC) {
    private val arrived = atomic(0L)

    val done get() = arrived.value >= parties

    suspend fun arrive(): Boolean {
        val a = arrived.incrementAndGet()
        return when {
            a < parties -> {
                suspendAtomicCancellableCoroutineReusable { cont -> suspend(cont) }
            }
            a == parties.toLong() -> {
                repeat(parties - 1) {
                    while (!tryResume(true)) {}
                }
                true
            }
            else -> false
        }
    }
}

class SimpleBarrierLCStressTest : VerifierState() {
    private val b = SimpleBarrier(3)

    @Operation(cancellableOnSuspension = false)
    suspend fun arrive() = b.arrive()

    override fun extractState() = b.done

    @Test
    fun test() = LCStressOptionsDefault()
        .actorsBefore(0)
        .actorsAfter(0)
        .threads(3)
        .check(this::class)
}