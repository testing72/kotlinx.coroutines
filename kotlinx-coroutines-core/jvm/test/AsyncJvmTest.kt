/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines

import kotlinx.coroutines.channels.*
import kotlin.test.*


class AsyncJvmTest : TestBase() {
    // This must be a common test but it fails on JS because of KT-21961
    fun CoroutineScope.triggerChannel() = produce(capacity = 3) {
        while(true) {
            delay(1)
            send(Unit)
        }
    }

    @Test
    fun main() = runBlocking<Unit> {
        val channel = triggerChannel()
        for (value in channel) {
            delay(1)
        }
    }
}
