/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.channels

import kotlinx.coroutines.*

/**
 * A strategy for buffer overflow handling in [channels][Channel] and [flows][kotlinx.coroutines.flow.Flow] that
 * controls behavior on buffer overflow and typically defaults to [SUSPEND].
 */
@ExperimentalCoroutinesApi
public enum class BufferOverflow {
    /**
     * Suspend on buffer overflow.
     */
    SUSPEND,

    /**
     * Keep the latest value on buffer overflow, drop the oldest, do not suspend.
     */
    KEEP_LATEST,

    /**
     * Drop the latest value on buffer overflow, keep the oldest, do not suspend.
     */
    DROP_LATEST
}
