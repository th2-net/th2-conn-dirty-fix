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
import com.exactpro.th2.constants.Constants.IS_POSS_DUP
import com.exactpro.th2.constants.Constants.MSG_SEQ_NUM_TAG
import com.exactpro.th2.constants.Constants.POSS_DUP_TAG
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
import kotlin.math.ceil


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

    fun processClientMessages(sessionAlias: String,
                              searchRange: SearchRange,
                              processMessage: (ByteBuf) -> Boolean,
    ) {
        val startTimestamp = findStartTimestamp(
            sessionAlias,
            Direction.SECOND,
            searchRange
        ) ?: return
        searchMessages(createSearchRequest(startTimestamp, Direction.SECOND, sessionAlias, searchDirection = TimeRelation.NEXT), processMessage)
    }

    private fun findStartTimestamp(
        sessionAlias: String,
        direction: Direction,
        searchRange: SearchRange,
    ): Timestamp? {
        val firstValidMessage = searchMessageWithValidSequence(
            createSearchRequest(Instant.now().toTimestamp(), Direction.SECOND, sessionAlias)
        ) ?: return null

        return searchUntilFoundRangeStart(
            sessionAlias,
            searchRange,
            direction,
            firstValidMessage
        )
    }

    private fun searchUntilFoundRangeStart(sessionAlias: String,
                                           searchRange: SearchRange,
                                           direction: Direction,
                                           lastMessageDetails: MessageDetails,

                                           ): Timestamp? {
        val numberOfMessagesToSkipThrough = lastMessageDetails.payloadSequence - searchRange.start
        val expectedSequenceAfterSkiping = lastMessageDetails.messageSequence - numberOfMessagesToSkipThrough
        val numberOfLoops = ceil(numberOfMessagesToSkipThrough.toDouble() / 20).toInt()
        var searchRequest = createSearchRequest(lastMessageDetails.timestamp, direction, sessionAlias, 20)
        var lastNonNullMessage: MessageGroupResponse? = null

        repeat(numberOfLoops) {
            var message: MessageGroupResponse? = null

            for(response in dataProvider.searchMessages(searchRequest)) {
                lastNonNullMessage = response.message
                message = response.message
                if(message.messageId.sequence <= expectedSequenceAfterSkiping) {
                    break
                }
            }

            when(message) {
                null -> { return lastNonNullMessage?.timestamp ?: lastMessageDetails.timestamp }
                else -> {
                    if(message.messageId.sequence <= expectedSequenceAfterSkiping) {
                        val firstValidMessage = searchMessageWithValidSequence(
                            createSearchRequest(message.timestamp, Direction.SECOND, sessionAlias)
                        ) ?: return message.timestamp
                        return if(firstValidMessage.messageSequence <= searchRange.start) {
                            firstValidMessage.timestamp
                        } else {
                            searchUntilFoundRangeStart(
                                sessionAlias,
                                searchRange,
                                direction,
                                firstValidMessage
                            )
                        }
                    }
                }
            }
        }

        val message = lastNonNullMessage ?: return lastMessageDetails.timestamp

        val firstValidMessage = searchMessageWithValidSequence(
            createSearchRequest(message.timestamp, Direction.SECOND, sessionAlias)
        ) ?: return message.timestamp

        return if(firstValidMessage.messageSequence <= searchRange.start) {
            firstValidMessage.timestamp
        } else {
            searchUntilFoundRangeStart(
                sessionAlias,
                searchRange,
                direction,
                firstValidMessage
            )
        }
    }

    private fun searchMessageWithValidSequence(
        request: MessageSearchRequest
    ): MessageDetails? {
        var message: MessageGroupResponse? = null
        for(response in dataProvider.searchMessages(request)) {
            message = response.message
            if (sessionStartTime != null && compare(sessionStartDateTime, message.timestamp) > 0) {
                return null
            }
            val buffer = Unpooled.buffer().writeBytes(message.bodyRaw.toByteArray())
            val seqNum = buffer.findField(MSG_SEQ_NUM_TAG)?.value ?: continue
            if(buffer.findField(MSG_SEQ_NUM_TAG)?.value == IS_POSS_DUP) continue
            return MessageDetails(seqNum.toInt(), message.timestamp, message.messageId.sequence)
        }
        return when(message) {
            null -> null
            else -> searchMessageWithValidSequence(
                createSearchRequest(
                    message.timestamp,
                    message.messageId.direction,
                    message.messageId.connectionId.sessionAlias
                )
            )
        }
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
            val buffer = Unpooled.buffer()
            buffer.writeBytes(message.bodyRaw.toByteArray())
            if(!processMessage(buffer)) {
                return
            }
        }
        if(message != null) {
            searchMessages(
                createSearchRequest(
                    message.timestamp.plusNano(),
                    message.messageId.direction,
                    message.messageId.connectionId.sessionAlias,
                    searchDirection = TimeRelation.NEXT
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

    private fun createSearchRequest(
        timestamp: Timestamp,
        direction: Direction,
        sessionAlias: String,
        resultCountLimit: Int = 5,
        searchDirection: TimeRelation = TimeRelation.PREVIOUS
    ): MessageSearchRequest {
        return MessageSearchRequest.newBuilder().apply {
            startTimestamp = timestamp
            endTimestamp = sessionStartYesterday
            addResponseFormats(BASE_64_FORMAT)
            addStream(
                MessageStream.newBuilder()
                    .setName(sessionAlias)
                    .setDirection(direction)
            )
            this.resultCountLimit = Int32Value.of(resultCountLimit)
            this.searchDirection = searchDirection
        }.build()
    }

    data class SearchRange(val start: Int, val end: Int)
    data class MessageDetails(val payloadSequence: Int, val timestamp: Timestamp, val messageSequence: Long)

    private fun Timestamp.plusNano() = Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos + 1).build()

    companion object {
        const val BASE_64_FORMAT = "BASE_64"
    }
}