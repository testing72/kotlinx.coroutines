/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.stdlib

import kotlinx.coroutines.*
import java.util.concurrent.*
import kotlin.concurrent.*
import kotlin.coroutines.*
import kotlin.test.*

class StdlibCancellableCoroutineTest : TestBase() {

    private var isCancelled = false

    @Test
    fun testPureUsage() {
        val latch = CountDownLatch(1)
        ::pureUsage.startCoroutine(Continuation(EmptyCoroutineContext) {
            println("Aha: ${it.getOrNull()}")
            latch.countDown()
        })
        latch.await()
    }

    @Test
    fun testCancellationWithKotlinxCoroutines() = runTest {
        val deferred = async(Dispatchers.Default) {
            pureUsage()
        }

        Thread.sleep(500)
        deferred.cancelAndJoin()
        assertTrue { deferred.isCancelled }
        assertTrue { isCancelled }
    }

    @Test
    fun testRegularUsageWithKotlinxCoroutines() = runTest {
        val result = withContext(Dispatchers.Default) {
            pureUsage()
        }

        assertEquals(42, result)
        assertFalse { isCancelled }

    }

    private suspend fun pureUsage(): Int {
        return suspendStdlibCancellableCoroutine<Int> { cont ->
            cont.whenCancelled {
                println("Was cancelled")
                isCancelled = true
            }

            thread {
                Thread.sleep(1000)
                cont.resumeWith(Result.success(42))
                println("Resumed")
            }
        }
    }
}