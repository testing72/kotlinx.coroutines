/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.stdlib

import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*
import kotlin.jvm.*

private const val UNDECIDED = 0
private const val SUSPENDED = 1
private const val RESUMED = 2
private val NOT_COMPLETED = Any()

private open class CompletedWithException(@JvmField val exception: Throwable)
private class Cancelled(cause: Throwable?) : CompletedWithException(cause ?: CancellationException("TODO"))

@PublishedApi
internal class StdlibCancellableContinuationImpl<T>(
    private val delegate: Continuation<T>
) : StdlibCancellableContinuation<T> {

    override val context: CoroutineContext
        get() = delegate.context

    public var detach: Detach? = null
    private val _state = atomic<Any?>(NOT_COMPLETED)
    private val _decision = atomic(UNDECIDED)
    private var cancellationHandler: ((cause: Throwable?) -> Unit)? = null

    override fun resumeWith(result: Result<T>) {
        _state.loop { state ->
            if (state !== NOT_COMPLETED) {
                return // TODO maybe report error or makeResumed
            }
            // TODO AFU bug
            val r = result.getOrElse { CompletedExceptionally(it) }
            if (_state.compareAndSet(NOT_COMPLETED, r)) {
                tryResume(result)
                detach?.invoke()
            }
        }
    }


    override fun whenCancelled(handler: (cause: Throwable?) -> Unit) {
        // TODO too lazy to merge state machines
        require(cancellationHandler == null)
        cancellationHandler = handler
    }

    override fun cancel(cause: Throwable?): Boolean {
        _state.loop { state ->
            if (state !== NOT_COMPLETED) return false
            val e = cause ?: CancellationException("TODO")
            if (!_state.compareAndSet(state, Cancelled(e))) return false
            cancellationHandler?.invoke(e)
            detach?.invoke()
            tryResume(Result.failure(e))
            return true
        }
    }

    private fun tryResume(result: Result<T>) {
        _decision.loop { decision ->
            when(decision) {
                UNDECIDED -> if (_decision.compareAndSet(UNDECIDED, RESUMED)) return
                SUSPENDED -> if (_decision.compareAndSet(SUSPENDED, RESUMED)) {
                    delegate.resumeWith(result)
                }
                else -> return // TODO actually reason about the state
            }
        }
    }

    @PublishedApi
    internal fun getResult(): Any? {
        if (trySuspend()) return COROUTINE_SUSPENDED
        when (val state = _state.value) {
            is CompletedExceptionally -> throw state.cause
            else -> return state
        }
    }

    private fun trySuspend(): Boolean {
        _decision.loop { decision ->
            when (decision) {
                UNDECIDED -> if (this._decision.compareAndSet(UNDECIDED, SUSPENDED)) return true
                RESUMED -> return false
                else -> error("Already suspended")
            }
        }
    }
}
