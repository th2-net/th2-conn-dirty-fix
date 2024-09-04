/*
 * Copyright 2023-2024 Exactpro (Exactpro Systems Limited)
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

import com.exactpro.th2.common.grpc.MessageID
import com.exactpro.th2.conn.dirty.fix.MessageSearcher
import com.exactpro.th2.conn.dirty.fix.findField
import com.exactpro.th2.constants.Constants
import com.exactpro.th2.constants.Constants.IS_POSS_DUP
import com.exactpro.th2.dataprovider.lw.grpc.DataProviderService
import com.exactpro.th2.dataprovider.lw.grpc.MessageGroupResponse
import com.exactpro.th2.dataprovider.lw.grpc.MessageSearchResponse
import com.google.protobuf.ByteString
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import java.nio.charset.StandardCharsets
import java.util.Collections.emptyIterator
import kotlin.test.Test

class RecoveryTest {
    private lateinit var channel: Channel
    private lateinit var fixHandler: FixHandler

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun testSequenceResetInRange(useCache: Boolean) {
        val settings = FixHandlerTest.createHandlerSettings().apply {
            isLoadMissedMessagesFromCradle = true
            messageCacheSize = if(useCache) 100 else 0
        }
        val messages: List<String> = (3 .. 5).map { message(it, it.toString()) }
        val ms = MessageSearcher(messages.map(::toMessageSearchResponse))
        val dataProviderService: DataProviderService = mock {
            on { searchMessageGroups(any()) }.thenAnswer {
                ms.searchMessages(it.getArgument(0))
            }
        }
        channel = Channel(settings, dataProviderService)
        fixHandler = channel.fixHandler
        fixHandler.onOpen(channel)
        fixHandler.onIncoming(channel, logonResponse, generateMessageID())
        messages.forEach {
            val byteBuf = Unpooled.buffer().writeBytes(it.toByteArray(StandardCharsets.UTF_8))
            fixHandler.onOutgoing(channel, byteBuf, HashMap())
            fixHandler.postOutgoingMqPublish(channel, byteBuf, MessageID.getDefaultInstance(), HashMap(), null)
        }
        // requesting resend from 2 to 5
        val resendRequest = Unpooled.wrappedBuffer(
            "8=FIXT.1.1\u00019=73\u000135=2\u000134=2\u000149=client\u000156=server\u000150=trader\u000152=2014-12-22T10:15:30Z\u00017=2\u000116=5\u000110=226\u0001".toByteArray(
                StandardCharsets.UTF_8
            )
        )
        fixHandler.onIncoming(channel, resendRequest, generateMessageID())
        assertEquals(5, channel.queue.size)

        channel.queue[0].assertLogon(1)
        channel.queue[1].assertSequenceReset(2, 3)
        channel.queue[2].assertMessage("C", 3, IS_POSS_DUP, "3")
        channel.queue[3].assertMessage("C", 4, IS_POSS_DUP, "4")
        channel.queue[4].assertMessage("C", 5, IS_POSS_DUP, "5")

        verify(dataProviderService, times(2)).searchMessageGroups(any())
        verifyNoMoreInteractions(dataProviderService)
    }

    @Test
    fun testSequenceResetInsideRange() {
        val settings = FixHandlerTest.createHandlerSettings().apply {
            isLoadMissedMessagesFromCradle = true
            messageCacheSize = 0
        }
        val ms = MessageSearcher(
            listOf(
                messageSearchResponse(4),
                messageSearchResponse(5)
            )
        )
        val dataProviderService: DataProviderService = mock {
            on { searchMessageGroups(any()) }.thenAnswer {
                ms.searchMessages(it.getArgument(0))
            }
        }
        channel = Channel(settings, dataProviderService)
        fixHandler = channel.fixHandler
        fixHandler.onOpen(channel)
        fixHandler.onIncoming(channel, logonResponse, generateMessageID())
        // handler sequence after loop is 22
        for (i in 0..20) {
            val message = Unpooled.buffer().writeBytes(messageWithoutSeqNum().toByteArray(StandardCharsets.UTF_8))
            fixHandler.onOutgoing(channel, message, HashMap())
            fixHandler.postOutgoingMqPublish(channel, message, MessageID.getDefaultInstance(), HashMap(), null)
        }
        // requesting resend from 2 to 8
        val resendRequest = Unpooled.wrappedBuffer(
            "8=FIXT.1.1\u00019=73\u000135=2\u000134=2\u000149=client\u000156=server\u000150=trader\u000152=2014-12-22T10:15:30Z\u00017=2\u000116=8\u000110=226\u0001".toByteArray(
                StandardCharsets.UTF_8
            )
        )
        fixHandler.onIncoming(channel, resendRequest, generateMessageID())
        assertEquals(5, channel.queue.size, channel.queue.asSequence()
            .map { it.findField(Constants.MSG_SEQ_NUM_TAG)?.value }
            .joinToString()
        )

        channel.queue[0].assertLogon(1)
        // for missed messages after beginSeqNo to 4
        channel.queue[1].assertSequenceReset(2, 4)
        channel.queue[2].assertMessage("C", 4, IS_POSS_DUP)
        channel.queue[3].assertMessage("C", 5, IS_POSS_DUP)
        // For missed messages after 4
        channel.queue[4].assertSequenceReset(6, 23)

        verify(dataProviderService, times(2)).searchMessageGroups(any())
        verifyNoMoreInteractions(dataProviderService)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun testSequenceResetOutOfRange(useCache: Boolean) {
        val settings = FixHandlerTest.createHandlerSettings().apply {
            isLoadMissedMessagesFromCradle = true
            messageCacheSize = if(useCache) 100 else 0
        }
        val ms = MessageSearcher((2 .. 4).map { messageSearchResponse(it, it.toString()) })
        val dataProviderService: DataProviderService = mock {
            on { searchMessageGroups(any()) }.thenAnswer {
                ms.searchMessages(it.getArgument(0))
            }
        }
        channel = Channel(settings, dataProviderService)
        fixHandler = channel.fixHandler
        fixHandler.onOpen(channel)
        fixHandler.onIncoming(channel, logonResponse, generateMessageID())
        for (i in 2..4) {
            val byteBuf = Unpooled.buffer().writeBytes(messageWithoutSeqNum(i.toString()).toByteArray(StandardCharsets.UTF_8))
            fixHandler.onOutgoing(channel, byteBuf, HashMap())
            fixHandler.postOutgoingMqPublish(channel, byteBuf, MessageID.getDefaultInstance(), HashMap(), null)
        }
        // requesting resend from 2 to 6
        val resendRequest = Unpooled.wrappedBuffer(
            "8=FIXT.1.1\u00019=73\u000135=2\u000134=2\u000149=client\u000156=server\u000150=trader\u000152=2014-12-22T10:15:30Z\u00017=2\u000116=6\u000110=226\u0001".toByteArray(
                StandardCharsets.UTF_8
            )
        )
        fixHandler.onIncoming(channel, resendRequest, generateMessageID())
        assertEquals(5, channel.queue.size)

        channel.queue[0].assertLogon(1)
        channel.queue[1].assertMessage("C", 2, IS_POSS_DUP, "2")
        channel.queue[2].assertMessage("C", 3, IS_POSS_DUP, "3")
        channel.queue[3].assertMessage("C", 4, IS_POSS_DUP, "4")
        channel.queue[4].assertSequenceReset(5, 5)

        verify(dataProviderService, times(2)).searchMessageGroups(any())
        verifyNoMoreInteractions(dataProviderService)
    }

    @Test
    fun testSequenceResetAdminMessages() {
        val settings = FixHandlerTest.createHandlerSettings().apply {
            isLoadMissedMessagesFromCradle = true
            messageCacheSize = 0
        }
        val ms = MessageSearcher(
            listOf(
                messageSearchResponseAdmin(2),
                messageSearchResponse(4),
                messageSearchResponseAdmin(5),
                messageSearchResponseAdmin(6)
            )
        )
        val dataProviderService: DataProviderService = mock {
            on { searchMessageGroups(any()) }.thenAnswer {
                ms.searchMessages(it.getArgument(0))
            }
        }
        channel = Channel(settings, dataProviderService)
        fixHandler = channel.fixHandler
        fixHandler.onOpen(channel)
        fixHandler.onIncoming(channel, logonResponse, generateMessageID())
        // handler sequence after loop is 22
        for (i in 0..20) {
            val message = Unpooled.buffer().writeBytes(messageWithoutSeqNum().toByteArray(StandardCharsets.UTF_8))
            fixHandler.onOutgoing(channel, message, HashMap())
            fixHandler.postOutgoingMqPublish(channel, message, MessageID.getDefaultInstance(), HashMap(), null)
        }
        // requesting resend from 1 to 5
        val resendRequest = Unpooled.wrappedBuffer(
            "8=FIXT.1.1\u00019=73\u000135=2\u000134=2\u000149=client\u000156=server\u000150=trader\u000152=2014-12-22T10:15:30Z\u00017=1\u000116=5\u000110=226\u0001".toByteArray(
                StandardCharsets.UTF_8
            )
        )
        fixHandler.onIncoming(channel, resendRequest, generateMessageID())

        // sequence reset for messages from 1 to 3 ( 1, 2 - missing, 3 - admin )
        channel.queue[1].assertSequenceReset(1, 4)
        channel.queue[2].assertMessage("C", 4, IS_POSS_DUP)
        // sequence reset for messages from 1 to 3 ( 1, 2 - missing, 3 - admin )
        channel.queue[3].assertSequenceReset(5, 23)

        verify(dataProviderService, times(2)).searchMessageGroups(any())
        verifyNoMoreInteractions(dataProviderService)
    }

    @Test
    fun allMessagesMissed() {
        val settings = FixHandlerTest.createHandlerSettings().apply {
            isLoadMissedMessagesFromCradle = true
            messageCacheSize = 0
        }
        val dataProviderService: DataProviderService = mock {
            on { searchMessageGroups(any()) }.thenReturn(emptyIterator())
        }
        channel = Channel(settings, dataProviderService)
        fixHandler = channel.fixHandler
        fixHandler.onOpen(channel)
        fixHandler.onIncoming(channel, logonResponse, generateMessageID())
        // handler sequence after loop is 22
        for (i in 0..20) {
            val message = Unpooled.buffer().writeBytes(messageWithoutSeqNum().toByteArray(StandardCharsets.UTF_8))
            fixHandler.onOutgoing(channel, message, HashMap())
            fixHandler.postOutgoingMqPublish(channel, message, MessageID.getDefaultInstance(), HashMap(), null)
        }
        // requesting resend from 1 to 5
        val resendRequest = Unpooled.wrappedBuffer(
            "8=FIXT.1.1\u00019=73\u000135=2\u000134=2\u000149=client\u000156=server\u000150=trader\u000152=2014-12-22T10:15:30Z\u00017=1\u000116=5\u000110=226\u0001".toByteArray(
                StandardCharsets.UTF_8
            )
        )
        fixHandler.onIncoming(channel, resendRequest, generateMessageID())
        assertEquals(2, channel.queue.size)

        // sequence reset for messages from 1 to 3 ( 1, 2 - missing, 3 - admin )
        channel.queue[0].assertLogon(1)
        channel.queue[1].assertSequenceReset(1, 23)

        verify(dataProviderService).searchMessageGroups(any())
        verifyNoMoreInteractions(dataProviderService)
    }

    @Test
    fun recoverFromCache() {
        val settings = FixHandlerTest.createHandlerSettings().apply {
            isLoadMissedMessagesFromCradle = true
            messageCacheSize = 100
        }
        val dataProviderService: DataProviderService = mock { }
        channel = Channel(settings, dataProviderService)
        fixHandler = channel.fixHandler
        fixHandler.onOpen(channel)
        fixHandler.onIncoming(channel, logonResponse, generateMessageID())
        repeat(20) {
            val message = Unpooled.buffer().writeBytes(messageWithoutSeqNum(it.toString()).toByteArray(StandardCharsets.UTF_8))
            fixHandler.onOutgoing(channel, message, HashMap())
            fixHandler.postOutgoingMqPublish(channel, message, MessageID.getDefaultInstance(), HashMap(), null)
        }
        // requesting resend from 1 to 5
        val resendRequest = Unpooled.wrappedBuffer(
            "8=FIXT.1.1\u00019=73\u000135=2\u000134=2\u000149=client\u000156=server\u000150=trader\u000152=2014-12-22T10:15:30Z\u00017=1\u000116=5\u000110=226\u0001".toByteArray(
                StandardCharsets.UTF_8
            )
        )
        fixHandler.onIncoming(channel, resendRequest, generateMessageID())

        assertEquals(6, channel.queue.size)
        channel.queue[0].assertLogon(1)
        channel.queue[1].assertSequenceReset(1, 2)
        channel.queue[2].assertMessage("C", 2, IS_POSS_DUP, "0")
        channel.queue[3].assertMessage("C", 3, IS_POSS_DUP, "1")
        channel.queue[4].assertMessage("C", 4, IS_POSS_DUP, "2")
        channel.queue[5].assertMessage("C", 5, IS_POSS_DUP, "3")

        verifyNoInteractions(dataProviderService)
    }

    companion object {
        private val logonResponse: ByteBuf = Unpooled.wrappedBuffer(
            "8=FIXT.1.1\u00019=105\u000135=A\u000134=1\u000149=server\u000156=client\u000150=system\u000152=2014-12-22T10:15:30Z\u000198=0\u0001108=30\u00011137=9\u00011409=0\u000110=203\u0001".toByteArray(
                StandardCharsets.US_ASCII
            )
        )

        private fun messageSearchResponse(sequence: Int, test: String = "test"): MessageSearchResponse = MessageSearchResponse.newBuilder()
            .setMessage(
                MessageGroupResponse.newBuilder()
                    .setBodyRaw(ByteString.copyFromUtf8(message(sequence, test)))
            ).build()

        private fun messageSearchResponseAdmin(sequence: Int): MessageSearchResponse =
            MessageSearchResponse.newBuilder()
                .setMessage(
                    MessageGroupResponse.newBuilder()
                        .setBodyRaw(ByteString.copyFromUtf8(adminMessage(sequence)))
                ).build()

        private fun toMessageSearchResponse(message: String) = MessageSearchResponse.newBuilder()
            .setMessage(
                MessageGroupResponse.newBuilder()
                    .setBodyRaw(ByteString.copyFromUtf8(message))
            ).build()

        private fun ByteBuf.assertMessage(
            msgType: String,
            msgSeqNum: Int,
            possDupFlag: String,
            text: String? = "test",
        ) {
            assertAll(
                { assertEquals(msgType, findField(Constants.MSG_TYPE_TAG)?.value, "MsgType mismatch") },
                { assertEquals(msgSeqNum, findField(Constants.MSG_SEQ_NUM_TAG)?.value?.toInt(), "SeqNum mismatch") },
                { assertEquals(possDupFlag, findField(Constants.POSS_DUP_TAG)?.value ?: "N", "PostDupFlag mismatch") },
                { assertEquals(text, findField(Constants.TEXT_TAG)?.value, "Text mismatch") },
            )
        }

        private fun ByteBuf.assertLogon(
            msgSeqNum: Int,
        ) {
            assertAll(
                { assertEquals(Constants.MSG_TYPE_LOGON, findField(Constants.MSG_TYPE_TAG)?.value, "MsgType mismatch") },
                { assertEquals(msgSeqNum, findField(Constants.MSG_SEQ_NUM_TAG)?.value?.toInt(), "SeqNum mismatch") },
            )
        }

        private fun ByteBuf.assertSequenceReset(
            msgSeqNum: Int,
            newSeqNum: Int,
        ) {
            assertAll(
                { assertEquals(Constants.MSG_TYPE_SEQUENCE_RESET, findField(Constants.MSG_TYPE_TAG)?.value, "MsgType mismatch") },
                { assertEquals(msgSeqNum, findField(Constants.MSG_SEQ_NUM_TAG)?.value?.toInt(), "SeqNum mismatch") },
                { assertEquals(newSeqNum, findField(Constants.NEW_SEQ_NO_TAG)?.value?.toInt(), "NewSeqNum mismatch") },
            )
        }

        private fun message(sequence: Int, test: String = "test"): String =
            "8=FIXT.1.1\u00019=70\u000135=C\u0001552=1\u000149=client\u000134=${sequence}\u000156=server\u000152=2014-12-22T10:15:30Z\u000150=trader\u000158=${test}\u000110=132\u0001"

        private fun messageWithoutSeqNum(test: String = "test"): String =
            "8=FIXT.1.1\u00019=70\u000135=C\u0001552=1\u000149=client\u000156=server\u000152=2014-12-22T10:15:30Z\u000150=trader\u000158=${test}\u000110=132\u0001"

        private fun adminMessage(sequence: Int): String =
            "8=FIXT.1.1\u00019=70\u000135=4\u0001552=1\u000149=client\u000134=${sequence}\u000156=server\u000152=2014-12-22T10:15:30Z\u000150=trader\u000110=132\u0001"
    }
}
