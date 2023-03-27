/*
 * Copyright 2022-2023 Exactpro (Exactpro Systems Limited)
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

import com.exactpro.th2.dataprovider.grpc.MessageSearchRequest
import com.exactpro.th2.dataprovider.grpc.MessageSearchResponse
import com.exactpro.th2.dataprovider.grpc.TimeRelation
import com.google.protobuf.util.Timestamps

class MessageSearcher(private val messages: List<MessageSearchResponse>) {

    fun searchMessages(request: MessageSearchRequest): Iterator<MessageSearchResponse> {
        val limit = request.resultCountLimit.value
        val startTimestamp = request.startTimestamp
        val searchDirection = request.searchDirection

        var filteredMessages = if (searchDirection == TimeRelation.NEXT) {
            messages.filter {
                Timestamps.compare(it.message.timestamp, startTimestamp) >= 0 }
        } else {
            messages.filter {
                Timestamps.compare(it.message.timestamp, startTimestamp) <= 0
            }.reversed()
        }

        return filteredMessages.take(limit).iterator()
    }
}