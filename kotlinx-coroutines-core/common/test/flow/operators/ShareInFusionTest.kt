/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.flow

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlin.test.*

class ShareInFusionTest : TestBase() {
    /**
     * Test perfect fusion for operators **after** [shareIn].
     */
    @Test
    fun testOperatorFusion() = runTest {
        val sh = emptyFlow<Int>().shareIn(this, 0)
        assertTrue(sh !is MutableSharedFlow<*>) // cannot be cast to mutable shared flow!!!
        assertSame(sh, (sh as Flow<*>).cancellable())
        assertSame(sh, (sh as Flow<*>).flowOn(Dispatchers.Default))
        assertSame(sh, sh.buffer(Channel.RENDEZVOUS))
        coroutineContext.cancelChildren()
    }

    @Test
    fun testFlowOnContextFusion() = runTest {
        val flow = flow<String> {
            assertEquals("FlowCtx", currentCoroutineContext()[CoroutineName]?.name)
            emit("OK")
        }.flowOn(CoroutineName("FlowCtx"))
        assertEquals("OK", flow.shareIn(this, 1).first())
        coroutineContext.cancelChildren()
    }
}