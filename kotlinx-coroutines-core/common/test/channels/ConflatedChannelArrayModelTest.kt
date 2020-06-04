/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.channels

import kotlinx.coroutines.*
import kotlin.test.*

// Test that ArrayChannel(1, KEEP_LATEST) works just like ConflatedChannel()
class ConflatedChannelArrayModelTest : ConflatedChannelTest() {
    override fun <T> createConflatedChannel(): Channel<T> =
        ArrayChannel<T>(1, BufferOverflow.KEEP_LATEST)
}
