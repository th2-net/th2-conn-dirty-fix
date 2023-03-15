/*
 * Copyright 2023 Exactpro (Exactpro Systems Limited)
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
package com.exactpro.th2.conn.dirty.fix

import com.exactpro.th2.SequenceHolder
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.message.toTimestamp
import com.exactpro.th2.constants.Constants.MSG_SEQ_NUM_TAG
import com.exactpro.th2.dataprovider.grpc.DataProviderService
import com.exactpro.th2.dataprovider.grpc.MessageGroupResponse
import com.exactpro.th2.dataprovider.grpc.MessageSearchRequest
import com.exactpro.th2.dataprovider.grpc.MessageStream
import com.exactpro.th2.dataprovider.grpc.TimeRelation
import com.google.protobuf.Int32Value
import com.google.protobuf.Timestamp
import com.google.protobuf.util.Timestamps.compare
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset


class MessageLoader(
    private val dataProvider: DataProviderService,
    private val sessionStartTime: LocalTime?
) {
    private val sessionStart = OffsetDateTime
        .now(ZoneOffset.UTC)
        .with(sessionStartTime ?: LocalTime.now())
        .atZoneSameInstant(ZoneId.systemDefault());

    private val sessionStartDateTime = sessionStart
        .toInstant()
        .toTimestamp()

    private val sessionStartYesterday = sessionStart
        .minusDays(1)
        .toInstant()
        .toTimestamp()

    fun loadInitialSequences(sessionAlias: String): SequenceHolder {
        val serverSeq = searchSeq(createSearchRequest(Instant.now().toTimestamp(), Direction.FIRST, sessionAlias))
        val clientSeq = searchSeq(createSearchRequest(Instant.now().toTimestamp(), Direction.SECOND, sessionAlias))
        return SequenceHolder(clientSeq, serverSeq)
    }

    fun processClientMessages(processMessage: (ByteBuf) -> Boolean, sessionAlias: String) {
        searchMessages(createSearchRequest(Instant.now().toTimestamp(), Direction.SECOND, sessionAlias), processMessage)
    }

    private fun searchMessages(
        request: MessageSearchRequest,
        processMessage: (ByteBuf) -> Boolean
    ) {
        var message: MessageGroupResponse? = null
        for(response in dataProvider.searchMessages(request)) {
            message = response.message
            if (sessionStartTime != null && compare(sessionStartDateTime, message.timestamp) > 0) {
                return
            }
            val buffer = Unpooled.wrappedBuffer(message.bodyRaw.toByteArray())
            if(!processMessage(buffer)) return
        }
        if(message != null) {
            searchMessages(
                createSearchRequest(
                    message.timestamp,
                    message.messageId.direction,
                    message.messageId.connectionId.sessionAlias
                ),
                processMessage
            )
        }
    }

    private fun searchSeq(request: MessageSearchRequest): Int {
        var message: MessageGroupResponse? = null
        for (response in dataProvider.searchMessages(request)) {
            message = response.message
            if (sessionStartTime != null && compare(sessionStartDateTime, message.timestamp) > 0) {
                return 0
            }
            val buffer = Unpooled.wrappedBuffer(message.bodyRaw.asReadOnlyByteBuffer())
            return buffer.findField(MSG_SEQ_NUM_TAG)?.value?.toInt() ?: continue
        }
        return when (message) {
            null -> 0
            else -> searchSeq(
                createSearchRequest(
                    message.timestamp,
                    message.messageId.direction,
                    message.messageId.connectionId.sessionAlias
                )
            )
        }
    }

    private fun createSearchRequest(timestamp: Timestamp, direction: Direction, sessionAlias: String) =
        MessageSearchRequest.newBuilder().apply {
            startTimestamp = timestamp
            endTimestamp = sessionStartYesterday
            searchDirection = TimeRelation.PREVIOUS
            addResponseFormats(BASE_64_FORMAT)
            addStream(
                MessageStream.newBuilder()
                .setName(sessionAlias)
                .setDirection(direction)
            )
            resultCountLimit = Int32Value.of(5)
        }.build()

    companion object {
        const val BASE_64_FORMAT = "BASE_64"
    }
}