/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.stdlib

import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

public typealias Detach = Function0<Unit>

@OptIn(ExperimentalStdlibApi::class)
public interface CancellationToken : CoroutineContext.Element {
    public companion object Key : CoroutineContext.Key<CancellationToken>
    override val key: CoroutineContext.Key<*> get() = Key

    public fun attach(continuation: StdlibCancellableContinuation<*>): Detach

    override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? = getPolymorphicElement(key)
    override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext = minusPolymorphicKey(key)
}

public interface StdlibCancellableContinuation<T> : Continuation<T> {
    public fun whenCancelled(handler: (cause: Throwable?) -> Unit)
    public fun cancel(cause: Throwable?): Boolean
}

// Not inline because of the stupid debugger ffs
public suspend fun <T> suspendStdlibCancellableCoroutine(
    block: (StdlibCancellableContinuation<T>) -> Unit
): T = suspendCoroutineUninterceptedOrReturn { uCont ->
    val cancellable = StdlibCancellableContinuationImpl(uCont.intercepted())
    val token = uCont.context[CancellationToken]
    cancellable.detach = token?.attach(cancellable)
    block(cancellable)
    cancellable.getResult()
}