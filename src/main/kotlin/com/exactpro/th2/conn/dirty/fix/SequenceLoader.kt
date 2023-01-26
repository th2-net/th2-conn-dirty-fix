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
import com.exactpro.th2.dataprovider.grpc.MessageSearchRequest
import com.exactpro.th2.dataprovider.grpc.MessageSearchResponse
import com.exactpro.th2.dataprovider.grpc.MessageStream
import com.exactpro.th2.dataprovider.grpc.TimeRelation
import com.google.protobuf.Int32Value
import com.google.protobuf.Timestamp
import com.google.protobuf.util.Timestamps
import io.netty.buffer.Unpooled
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset


class SequenceLoader(
    private val dataProvider: DataProviderService,
    private val sessionStartTime: LocalTime?,
    private val sessionAlias: String,
) {
    private val sessionStartDateTime = OffsetDateTime
        .now(ZoneOffset.UTC)
        .with(sessionStartTime ?: LocalTime.now())
        .atZoneSameInstant(ZoneId.systemDefault())
        .toInstant()
        .toTimestamp()

    fun load(): SequenceHolder {
        val serverSeq = searchSeq(createSearchRequest(Instant.now().toTimestamp(), Direction.FIRST))
        val clientSeq = searchSeq(createSearchRequest(Instant.now().toTimestamp(), Direction.SECOND))
        return SequenceHolder(clientSeq, serverSeq)
    }

    private fun searchSeq(request: MessageSearchRequest): Int {
        val iterator = dataProvider.searchMessages(request)
        if(!iterator.hasNext()) {
            return 0
        }
        var message: MessageSearchResponse? = null
        while (iterator.hasNext()) {
            message = iterator.next()
            if(sessionStartTime != null) {
                if(Timestamps.compare(sessionStartDateTime, message.message.timestamp) > 0) {
                    return 0
                }
            }
            val msg = Unpooled.copiedBuffer(message.message.bodyRaw.toByteArray())
            return msg.findField(MSG_SEQ_NUM_TAG)?.value?.toInt() ?: continue
        }
        return searchSeq(createSearchRequest(message!!.message.timestamp, message!!.message.messageId.direction))
    }

    private fun createSearchRequest(timestamp: Timestamp, direction: Direction): MessageSearchRequest {
        return MessageSearchRequest.newBuilder().apply {
            startTimestamp = timestamp
            searchDirection = TimeRelation.PREVIOUS
            addResponseFormats(MessageSearchRequest.ResponseFormat.BASE_64)
            addStream(
                MessageStream.newBuilder()
                .setName(sessionAlias)
                .setDirection(direction)
            )
            resultCountLimit = Int32Value.of(5)
        }.build()
    }
}