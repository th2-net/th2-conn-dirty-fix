/*
 * Copyright 2024 Exactpro (Exactpro Systems Limited)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.exactpro.th2

import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.MessageID
import com.google.protobuf.Timestamp
import com.google.protobuf.util.Timestamps
import java.util.concurrent.atomic.AtomicLong

private val SEQUENCE_COUNTER = AtomicLong()

@JvmOverloads
fun generateMessageID(
    direction: Direction = Direction.FIRST,
    sequence: Long = SEQUENCE_COUNTER.incrementAndGet(),
    timestamp: Timestamp = Timestamps.now(),
    alias: String = "test-session-alias",
    group: String = "test-session-group",
    book: String = "test-book",
): MessageID {
    return MessageID.newBuilder()
        .setBookName(book)
        .setConnectionId(
            ConnectionID.newBuilder()
                .setSessionGroup(group)
                .setSessionAlias(alias)
                .build()
        )
        .setTimestamp(timestamp)
        .setSequence(sequence)
        .setDirection(direction)
        .build()
}