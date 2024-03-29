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

package com.exactpro.th2;

import com.exactpro.th2.common.grpc.MessageID;
import com.exactpro.th2.common.grpc.RawMessage;
import com.exactpro.th2.conn.dirty.fix.FixField;
import com.exactpro.th2.conn.dirty.tcp.core.api.IChannel;
import com.exactpro.th2.conn.dirty.tcp.core.api.IChannel.SendMode;
import com.exactpro.th2.conn.dirty.tcp.core.api.IHandler;
import com.exactpro.th2.conn.dirty.tcp.core.api.IHandlerContext;
import com.exactpro.th2.conn.dirty.tcp.core.util.CommonUtil;
import com.exactpro.th2.util.Util;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.exactpro.th2.conn.dirty.fix.FixByteBufUtilKt.findField;
import static com.exactpro.th2.conn.dirty.fix.FixByteBufUtilKt.findLastField;
import static com.exactpro.th2.conn.dirty.fix.FixByteBufUtilKt.firstField;
import static com.exactpro.th2.conn.dirty.fix.FixByteBufUtilKt.lastField;
import static com.exactpro.th2.conn.dirty.fix.FixByteBufUtilKt.updateChecksum;
import static com.exactpro.th2.conn.dirty.fix.FixByteBufUtilKt.updateLength;
import static com.exactpro.th2.conn.dirty.fix.KeyFileType.Companion.OperationMode.ENCRYPT_MODE;
import static com.exactpro.th2.conn.dirty.tcp.core.util.CommonUtil.getEventId;
import static com.exactpro.th2.conn.dirty.tcp.core.util.CommonUtil.toByteBuf;
import static com.exactpro.th2.constants.Constants.ADMIN_MESSAGES;
import static com.exactpro.th2.constants.Constants.BEGIN_SEQ_NO;
import static com.exactpro.th2.constants.Constants.BEGIN_SEQ_NO_TAG;
import static com.exactpro.th2.constants.Constants.BEGIN_STRING_TAG;
import static com.exactpro.th2.constants.Constants.BODY_LENGTH;
import static com.exactpro.th2.constants.Constants.BODY_LENGTH_TAG;
import static com.exactpro.th2.constants.Constants.CHECKSUM;
import static com.exactpro.th2.constants.Constants.CHECKSUM_TAG;
import static com.exactpro.th2.constants.Constants.DEFAULT_APPL_VER_ID;
import static com.exactpro.th2.constants.Constants.ENCRYPTED_PASSWORD;
import static com.exactpro.th2.constants.Constants.ENCRYPT_METHOD;
import static com.exactpro.th2.constants.Constants.END_SEQ_NO;
import static com.exactpro.th2.constants.Constants.END_SEQ_NO_TAG;
import static com.exactpro.th2.constants.Constants.GAP_FILL_FLAG;
import static com.exactpro.th2.constants.Constants.GAP_FILL_FLAG_TAG;
import static com.exactpro.th2.constants.Constants.HEART_BT_INT;
import static com.exactpro.th2.constants.Constants.IS_POSS_DUP;
import static com.exactpro.th2.constants.Constants.MSG_SEQ_NUM;
import static com.exactpro.th2.constants.Constants.MSG_SEQ_NUM_TAG;
import static com.exactpro.th2.constants.Constants.MSG_TYPE;
import static com.exactpro.th2.constants.Constants.MSG_TYPE_HEARTBEAT;
import static com.exactpro.th2.constants.Constants.MSG_TYPE_LOGON;
import static com.exactpro.th2.constants.Constants.MSG_TYPE_LOGOUT;
import static com.exactpro.th2.constants.Constants.MSG_TYPE_RESEND_REQUEST;
import static com.exactpro.th2.constants.Constants.MSG_TYPE_SEQUENCE_RESET;
import static com.exactpro.th2.constants.Constants.MSG_TYPE_TAG;
import static com.exactpro.th2.constants.Constants.MSG_TYPE_TEST_REQUEST;
import static com.exactpro.th2.constants.Constants.NEW_ENCRYPTED_PASSWORD;
import static com.exactpro.th2.constants.Constants.NEW_PASSWORD;
import static com.exactpro.th2.constants.Constants.NEW_SEQ_NO;
import static com.exactpro.th2.constants.Constants.NEW_SEQ_NO_TAG;
import static com.exactpro.th2.constants.Constants.NEXT_EXPECTED_SEQ_NUM;
import static com.exactpro.th2.constants.Constants.NEXT_EXPECTED_SEQ_NUMBER_TAG;
import static com.exactpro.th2.constants.Constants.PASSWORD;
import static com.exactpro.th2.constants.Constants.POSS_DUP_TAG;
import static com.exactpro.th2.constants.Constants.RESET_SEQ_NUM;
import static com.exactpro.th2.constants.Constants.SENDER_COMP_ID;
import static com.exactpro.th2.constants.Constants.SENDER_COMP_ID_TAG;
import static com.exactpro.th2.constants.Constants.SENDER_SUB_ID;
import static com.exactpro.th2.constants.Constants.SENDER_SUB_ID_TAG;
import static com.exactpro.th2.constants.Constants.SENDING_TIME;
import static com.exactpro.th2.constants.Constants.SENDING_TIME_TAG;
import static com.exactpro.th2.constants.Constants.SESSION_STATUS_TAG;
import static com.exactpro.th2.constants.Constants.SUCCESSFUL_LOGOUT_CODE;
import static com.exactpro.th2.constants.Constants.TARGET_COMP_ID;
import static com.exactpro.th2.constants.Constants.TARGET_COMP_ID_TAG;
import static com.exactpro.th2.constants.Constants.TEST_REQ_ID;
import static com.exactpro.th2.constants.Constants.TEST_REQ_ID_TAG;
import static com.exactpro.th2.constants.Constants.TEXT_TAG;
import static com.exactpro.th2.constants.Constants.USERNAME;
import static com.exactpro.th2.netty.bytebuf.util.ByteBufUtil.indexOf;
import static com.exactpro.th2.netty.bytebuf.util.ByteBufUtil.isEmpty;
import static com.exactpro.th2.util.MessageUtil.findByte;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;

//todo parse logout
//todo gapFillTag
//todo ring buffer as cache
//todo add events

public class FixHandler implements AutoCloseable, IHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(FixHandler.class);

    private static final int DAY_SECONDS = 24 * 60 * 60;
    private static final String SOH = "\001";
    private static final byte BYTE_SOH = 1;
    private static final String STRING_MSG_TYPE = "MsgType";
    private static final String REJECT_REASON = "Reject reason";
    private static final String STUBBING_VALUE = "XXX";

    private final Log outgoingMessages = new Log(10000);
    private final AtomicInteger msgSeqNum;
    private final AtomicInteger serverMsgSeqNum;
    private final AtomicInteger testReqID = new AtomicInteger(0);
    private final AtomicBoolean sessionActive = new AtomicBoolean(true);
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicBoolean connStarted = new AtomicBoolean(false);
    private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);
    private final IHandlerContext context;
    private final InetSocketAddress address;

    private Future<?> heartbeatTimer = CompletableFuture.completedFuture(null);
    private Future<?> testRequestTimer = CompletableFuture.completedFuture(null);
    private Future<?> reconnectRequestTimer = CompletableFuture.completedFuture(null);
    private volatile IChannel channel;
    protected FixHandlerSettings settings;
    private long lastSendTime = System.currentTimeMillis();

    public FixHandler(IHandlerContext context) {
        this.context = context;
        this.settings = (FixHandlerSettings) context.getSettings();
        if(settings.getStateFilePath() == null || !settings.getStateFilePath().exists()) {
            msgSeqNum = new AtomicInteger(0);
            serverMsgSeqNum = new AtomicInteger(0);
        } else {
            SequenceHolder sequences = Util.readSequences(settings.getStateFilePath());
            msgSeqNum = new AtomicInteger(sequences.getClientSeq());
            serverMsgSeqNum = new AtomicInteger(sequences.getServerSeq());
        }

        LOGGER.info("Initial sequences are: client - {}, server - {}", msgSeqNum.get(), serverMsgSeqNum.get());

        if(settings.getSessionStartTime() != null) {
            Objects.requireNonNull(settings.getSessionEndTime(), "Session end is required when session start is presented");
            LocalTime resetTime = settings.getSessionStartTime();
            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
            ZonedDateTime scheduleTime = now.with(resetTime);

            if(scheduleTime.isBefore(now)) {
                scheduleTime = now.plusDays(1).with(resetTime);
            }
            long time = now.until(scheduleTime, ChronoUnit.SECONDS);
            executorService.scheduleAtFixedRate(this::reset, time, DAY_SECONDS, TimeUnit.SECONDS);
        }

        if(settings.getSessionEndTime() != null) {
            LocalTime resetTime = settings.getSessionEndTime();
            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
            ZonedDateTime scheduleTime = now.with(resetTime);

            if(scheduleTime.isBefore(now)) {
                scheduleTime = now.plusDays(1).with(resetTime);
            } else if(now.isBefore(now.with(settings.getSessionStartTime()))) {
                sessionActive.set(false);
            }

            long time = now.until(scheduleTime, ChronoUnit.SECONDS);
            executorService.scheduleAtFixedRate(() -> {
                this.close();
                sessionActive.set(false);
            }, time, DAY_SECONDS, TimeUnit.SECONDS);
        }

        String host = settings.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("host cannot be blank");
        int port = settings.getPort();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be in 1..65535 range");
        address = new InetSocketAddress(host, port);
        Objects.requireNonNull(settings.getSecurity(), "security cannot be null");
        Objects.requireNonNull(settings.getBeginString(), "BeginString can not be null");
        Objects.requireNonNull(settings.getResetSeqNumFlag(), "ResetSeqNumFlag can not be null");
        Objects.requireNonNull(settings.getResetOnLogon(), "ResetOnLogon can not be null");
        if (settings.getHeartBtInt() <= 0) throw new IllegalArgumentException("HeartBtInt cannot be negative or zero");
        if (settings.getTestRequestDelay() <= 0) throw new IllegalArgumentException("TestRequestDelay cannot be negative or zero");
        if (settings.getDisconnectRequestDelay() <= 0) throw new IllegalArgumentException("DisconnectRequestDelay cannot be negative or zero");
    }

    @Override
    public void onStart() {
        channel = context.createChannel(address, settings.getSecurity(), Map.of(), true, settings.getReconnectDelay() * 1000L, Integer.MAX_VALUE);
        channel.open();
    }

    @NotNull
    @Override
    public CompletableFuture<MessageID> send(@NotNull RawMessage rawMessage) {
        if (!sessionActive.get()) {
            throw new IllegalStateException("Session is not active. It is not possible to send messages.");
        }
        if (!channel.isOpen()) {
            try {
                channel.open().get();
            } catch (Exception e) {
                ExceptionUtils.rethrow(e);
            }
        }

        return channel.send(toByteBuf(rawMessage.getBody()), rawMessage.getMetadata().getPropertiesMap(), getEventId(rawMessage), SendMode.HANDLE_AND_MANGLE);
    }

    @Override
    public ByteBuf onReceive(IChannel channel, ByteBuf buffer) {
        int offset = buffer.readerIndex();
        if (offset == buffer.writerIndex()) return null;

        int beginStringIdx = indexOf(buffer, "8=FIX");
        if (beginStringIdx < 0) {
            return null;
        }

        if (beginStringIdx > offset) {
            buffer.readerIndex(beginStringIdx);
            return buffer.retainedSlice(offset, beginStringIdx - offset);
        }

        int nextBeginString = indexOf(buffer, SOH + "8=FIX") + 1;
        int checksum = indexOf(buffer, CHECKSUM);
        int endOfMessageIdx = findByte(buffer, checksum + 1, BYTE_SOH);

        try {
            if (checksum == -1 || endOfMessageIdx == -1 || endOfMessageIdx - checksum != 7) {
                throw new IllegalStateException("Failed to parse message: " + buffer.toString(US_ASCII) + ". No Checksum or no tag separator at the end of the message with index: " + beginStringIdx);
            }
        } catch (Exception e) {
            if (nextBeginString > 0) {
                buffer.readerIndex(nextBeginString);
            } else {
                buffer.readerIndex(beginStringIdx);
            }
            return null;
        }

        buffer.readerIndex(endOfMessageIdx + 1);
        return buffer.retainedSlice(beginStringIdx, endOfMessageIdx + 1 - beginStringIdx);
    }

    @NotNull
    @Override
    public Map<String, String> onIncoming(@NotNull IChannel channel, @NotNull ByteBuf message) {
        Map<String, String> metadata = new HashMap<>();

        int beginString = indexOf(message, "8=FIX");

        if (beginString == -1) {
            metadata.put(REJECT_REASON, "Not a FIX message");
            return metadata;
        }

        FixField msgSeqNumValue = findField(message, MSG_SEQ_NUM_TAG);
        if (msgSeqNumValue == null) {
            metadata.put(REJECT_REASON, "No msgSeqNum Field");
            if (LOGGER.isErrorEnabled()) LOGGER.error("Invalid message. No MsgSeqNum in message: {}", message.toString(US_ASCII));
            return metadata;
        }

        FixField msgType = findField(message, MSG_TYPE_TAG);
        if (msgType == null) {
            metadata.put(REJECT_REASON, "No msgType Field");
            if (LOGGER.isErrorEnabled()) LOGGER.error("Invalid message. No MsgType in message: {}", message.toString(US_ASCII));
            return metadata;
        }

        FixField possDup = findField(message, POSS_DUP_TAG);
        boolean isDup = false;
        if(possDup != null) {
            isDup = possDup.getValue().equals(IS_POSS_DUP);
        }

        int receivedMsgSeqNum = Integer.parseInt(requireNonNull(msgSeqNumValue.getValue()));

        if(receivedMsgSeqNum < serverMsgSeqNum.get() && !isDup) {
            sendLogout();
            reconnectRequestTimer = executorService.schedule(this::sendLogon, settings.getReconnectDelay(), TimeUnit.SECONDS);
            metadata.put(REJECT_REASON, "SeqNum is less than expected.");
            if (LOGGER.isErrorEnabled()) LOGGER.error("Invalid message. SeqNum is less than expected {}: {}", serverMsgSeqNum.get(), message.toString(US_ASCII));
            return metadata;
        }

        serverMsgSeqNum.incrementAndGet();

        if (serverMsgSeqNum.get() < receivedMsgSeqNum && !isDup && enabled.get()) {
            sendResendRequest(serverMsgSeqNum.get(), receivedMsgSeqNum);
        }

        String msgTypeValue = requireNonNull(msgType.getValue());
        switch (msgTypeValue) {
            case MSG_TYPE_HEARTBEAT:
                if (LOGGER.isInfoEnabled()) LOGGER.info("Heartbeat received - {}", message.toString(US_ASCII));
                checkHeartbeat(message);
                testRequestTimer = executorService.schedule(this::sendTestRequest, settings.getTestRequestDelay(), TimeUnit.SECONDS);
                break;
            case MSG_TYPE_LOGON:
                if (LOGGER.isInfoEnabled()) LOGGER.info("Logon received - {}", message.toString(US_ASCII));
                boolean connectionSuccessful = checkLogon(message);
                if (connectionSuccessful) {
                    if(settings.useNextExpectedSeqNum()) {
                        FixField nextExpectedSeqField = findField(message, NEXT_EXPECTED_SEQ_NUMBER_TAG);
                        if(nextExpectedSeqField == null) {
                            metadata.put(REJECT_REASON, "No NextExpectedSeqNum field");
                            if (LOGGER.isErrorEnabled()) LOGGER.error("Invalid message. No NextExpectedSeqNum in message: {}", message.toString(US_ASCII));
                            return metadata;
                        }

                        int nextExpectedSeqNumber = Integer.parseInt(requireNonNull(nextExpectedSeqField.getValue()));
                        int seqNum = msgSeqNum.get();
                        if(nextExpectedSeqNumber < seqNum) {
                            recovery(nextExpectedSeqNumber, seqNum);
                        }
                    }

                    enabled.set(true);

                    if (!connStarted.get()){
                        connStarted.set(true);
                    }

                    if (heartbeatTimer != null) {
                        heartbeatTimer.cancel(false);
                    }
                    heartbeatTimer = executorService.scheduleWithFixedDelay(this::sendHeartbeat, 1, 1, TimeUnit.SECONDS);

                    testRequestTimer = executorService.schedule(this::sendTestRequest, settings.getTestRequestDelay(), TimeUnit.SECONDS);
                } else {
                    enabled.set(false);
                    reconnectRequestTimer = executorService.schedule(this::sendLogon, settings.getReconnectDelay(), TimeUnit.SECONDS);
                }
                break;
            case MSG_TYPE_LOGOUT: //extract logout reason
                if (LOGGER.isInfoEnabled()) LOGGER.info("Logout received - {}", message.toString(US_ASCII));
                FixField sessionStatus = findField(message, SESSION_STATUS_TAG);

                if(sessionStatus != null) {
                    int statusCode = Integer.parseInt(Objects.requireNonNull(sessionStatus.getValue()));
                    if(statusCode != SUCCESSFUL_LOGOUT_CODE) {
                        FixField text = findField(message, TEXT_TAG);
                        if (text != null) {
                            LOGGER.warn("Received Logout has text (58) tag: {}", text.getValue());
                            String value = StringUtils.substringBetween(text.getValue(), "expecting ", " but received");
                            if (value != null) {
                                msgSeqNum.set(Integer.parseInt(value) - 1);
                            } else {
                                msgSeqNum.set(msgSeqNum.get() - 1);
                            }
                        } else {
                            msgSeqNum.set(msgSeqNum.get() - 1);
                        }
                        serverMsgSeqNum.set(Integer.parseInt(msgSeqNumValue.getValue()) - 1);
                    }
                }
                if (heartbeatTimer != null) {
                    heartbeatTimer.cancel(false);
                }
                if (testRequestTimer != null) {
                    testRequestTimer.cancel(false);
                }
                enabled.set(false);
                context.send(CommonUtil.toEvent("logout for sender - " + settings.getSenderCompID()));//make more useful
                break;
            case MSG_TYPE_RESEND_REQUEST:
                if (LOGGER.isInfoEnabled()) LOGGER.info("Resend request received - {}", message.toString(US_ASCII));
                handleResendRequest(message);
                break;
            case MSG_TYPE_SEQUENCE_RESET: //gap fill
                if (LOGGER.isInfoEnabled()) LOGGER.info("Sequence reset received - {}", message.toString(US_ASCII));
                resetSequence(message);
                break;
        }

        if (testRequestTimer != null && !testRequestTimer.isCancelled()) {
            testRequestTimer.cancel(false);
        }

        metadata.put(STRING_MSG_TYPE, msgTypeValue);

        return metadata;
    }

    private void resetSequence(ByteBuf message) {
        FixField gapFillMode = findField(message, GAP_FILL_FLAG_TAG);
        FixField seqNumValue = findField(message, NEW_SEQ_NO_TAG);

        if(seqNumValue != null) {
            if(gapFillMode == null || gapFillMode.equals("N")) {
                serverMsgSeqNum.set(Integer.parseInt(requireNonNull(seqNumValue.getValue())));
            } else {
                serverMsgSeqNum.set(Integer.parseInt(requireNonNull(seqNumValue.getValue())) - 1);
            }
        } else {
            LOGGER.trace("Failed to reset servers MsgSeqNum. No such tag in message: {}", message.toString(US_ASCII));
        }
    }

    private void reset() {
        msgSeqNum.set(0);
        serverMsgSeqNum.set(0);
        sessionActive.set(true);
        sendLogon();
    }

    public void sendResendRequest(int beginSeqNo, int endSeqNo) { //do private
        lastSendTime = System.currentTimeMillis();
        StringBuilder resendRequest = new StringBuilder();
        setHeader(resendRequest, MSG_TYPE_RESEND_REQUEST, msgSeqNum.incrementAndGet());
        resendRequest.append(BEGIN_SEQ_NO).append(beginSeqNo).append(SOH);
        resendRequest.append(END_SEQ_NO).append(endSeqNo).append(SOH);
        setChecksumAndBodyLength(resendRequest);
        channel.send(Unpooled.wrappedBuffer(resendRequest.toString().getBytes(StandardCharsets.UTF_8)), Collections.emptyMap(), null, IChannel.SendMode.MANGLE);
    }

    void sendResendRequest(int beginSeqNo) { //do private
        lastSendTime = System.currentTimeMillis();
        StringBuilder resendRequest = new StringBuilder();
        setHeader(resendRequest, MSG_TYPE_RESEND_REQUEST, msgSeqNum.incrementAndGet());
        resendRequest.append(BEGIN_SEQ_NO).append(beginSeqNo);
        resendRequest.append(END_SEQ_NO).append(0);
        setChecksumAndBodyLength(resendRequest);

        if (enabled.get()) {
            channel.send(Unpooled.wrappedBuffer(resendRequest.toString().getBytes(StandardCharsets.UTF_8)), Collections.emptyMap(), null, IChannel.SendMode.MANGLE);
        } else {
            sendLogon();
        }
    }

    private void handleResendRequest(ByteBuf message) {

        FixField strBeginSeqNo = findField(message, BEGIN_SEQ_NO_TAG);
        FixField strEndSeqNo = findField(message, END_SEQ_NO_TAG);

        if (strBeginSeqNo != null && strEndSeqNo != null) {
            int beginSeqNo = Integer.parseInt(requireNonNull(strBeginSeqNo.getValue()));
            int endSeqNo = Integer.parseInt(requireNonNull(strEndSeqNo.getValue()));

            try {
                // FIXME: there is not syn on the outgoing sequence. Should make operations with seq more careful
                recovery(beginSeqNo, endSeqNo);
            } catch (Exception e) {
                sendSequenceReset();
            }
        }
    }

    private void recovery(int beginSeqNo, int endSeqNo) {
        if (endSeqNo == 0) {
            endSeqNo = msgSeqNum.get();
        }
        LOGGER.info("Returning messages from {} to {}", beginSeqNo, endSeqNo);
        for (int i = beginSeqNo; i <= endSeqNo; i++) {
            ByteBuf storedMsg = outgoingMessages.get(i);
            if (storedMsg == null) {
                StringBuilder sequenceReset = new StringBuilder();
                setHeader(sequenceReset, MSG_TYPE_SEQUENCE_RESET, i);
                sequenceReset.append(GAP_FILL_FLAG).append("Y");
                sequenceReset.append(NEW_SEQ_NO).append(i + 1);
                setChecksumAndBodyLength(sequenceReset);
                channel.send(Unpooled.wrappedBuffer(sequenceReset.toString().getBytes(StandardCharsets.UTF_8)), Collections.emptyMap(), null, SendMode.MANGLE);
            } else {
                if (LOGGER.isInfoEnabled()) LOGGER.info("Resending message: {}", storedMsg.toString(US_ASCII));
                FixField sendingTime = findField(storedMsg, SENDING_TIME_TAG);
                sendingTime.insertNext(POSS_DUP_TAG, IS_POSS_DUP);
                channel.send(storedMsg, Collections.emptyMap(), null, SendMode.MANGLE);
            }
        }
    }

    private void sendSequenceReset() {
        lastSendTime = System.currentTimeMillis();
        StringBuilder sequenceReset = new StringBuilder();
        setHeader(sequenceReset, MSG_TYPE_SEQUENCE_RESET, msgSeqNum.incrementAndGet());
        sequenceReset.append(NEW_SEQ_NO).append(msgSeqNum.get() + 1);
        setChecksumAndBodyLength(sequenceReset);

        if (enabled.get()) {
            channel.send(Unpooled.wrappedBuffer(sequenceReset.toString().getBytes(StandardCharsets.UTF_8)), Collections.emptyMap(), null, IChannel.SendMode.MANGLE);
        } else {
            sendLogon();
        }
    }

    private void checkHeartbeat(ByteBuf message) {

        FixField receivedTestReqID = findField(message, TEST_REQ_ID_TAG);

        if (receivedTestReqID != null) {
            if (Objects.equals(receivedTestReqID.getValue(), Integer.toString(testReqID.get()))) {
                reconnectRequestTimer.cancel(false);
            }
        }
    }

    private boolean checkLogon(ByteBuf message) {
        FixField sessionStatusField = findField(message, SESSION_STATUS_TAG); //check another options
        if (sessionStatusField == null || requireNonNull(sessionStatusField.getValue()).equals("0")) {
            FixField msgSeqNumValue = findField(message, MSG_SEQ_NUM_TAG);
            if (msgSeqNumValue == null) {
                return false;
            }
            serverMsgSeqNum.set(Integer.parseInt(requireNonNull(msgSeqNumValue.getValue())));
            context.send(CommonUtil.toEvent("successful login"));
            return true;
        }
        return false;
    }

    @Override
    public void onOutgoing(@NotNull IChannel channel, @NotNull ByteBuf message, @NotNull Map<String, String> metadata) {
        lastSendTime = System.currentTimeMillis();
        onOutgoingUpdateTag(message, metadata);

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Outgoing message: {}", message.toString(US_ASCII));
        }
    }

    public void onOutgoingUpdateTag(@NotNull ByteBuf message, @NotNull Map<String, String> metadata) {
        if (isEmpty(message)) {
            return;
        }

        FixField beginString = findField(message, BEGIN_STRING_TAG);

        if (beginString == null) {
            beginString = firstField(message).insertPrevious(BEGIN_STRING_TAG, settings.getBeginString());
        }

        FixField bodyLength = findField(message, BODY_LENGTH_TAG, US_ASCII, beginString);

        if (bodyLength == null) {
            bodyLength = beginString.insertNext(BODY_LENGTH_TAG, STUBBING_VALUE);
        }

        FixField msgType = findField(message, MSG_TYPE_TAG, US_ASCII, bodyLength);

        if (msgType == null) {                                                        //should we interrupt sending message?
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("No msgType in message {}", message.toString(US_ASCII));
            }

            if (metadata.get("MsgType") != null) {
                msgType = bodyLength.insertNext(MSG_TYPE_TAG, metadata.get("MsgType"));
            }
        }

        FixField checksum = findLastField(message, CHECKSUM_TAG);

        if (checksum == null) {
            checksum = lastField(message).insertNext(CHECKSUM_TAG, STUBBING_VALUE); //stubbing until finish checking message
        }

        FixField msgSeqNum = findField(message, MSG_SEQ_NUM_TAG, US_ASCII, bodyLength);

        if (msgSeqNum == null) {
            int msgSeqNumValue = this.msgSeqNum.incrementAndGet();

            if (msgType != null) {
                msgSeqNum = msgType.insertNext(MSG_SEQ_NUM_TAG, Integer.toString(msgSeqNumValue));
            } else {
                msgSeqNum = bodyLength.insertNext(MSG_SEQ_NUM_TAG, Integer.toString(msgSeqNumValue));
            }
        }

        int msgSeqNumValue = Integer.parseInt(msgSeqNum.getValue());

        FixField senderCompID = findField(message, SENDER_COMP_ID_TAG, US_ASCII, bodyLength);

        if (senderCompID == null) {
            senderCompID = msgSeqNum.insertNext(SENDER_COMP_ID_TAG, settings.getSenderCompID());
        } else {
            String value = senderCompID.getValue();

            if (value == null || value.isEmpty() || value.equals("null")) {
                senderCompID.setValue(settings.getSenderCompID());
            }
        }

        FixField targetCompID = findField(message, TARGET_COMP_ID_TAG, US_ASCII, bodyLength);

        if (targetCompID == null) {
            targetCompID = senderCompID.insertNext(TARGET_COMP_ID_TAG, settings.getTargetCompID());
        } else {
            String value = targetCompID.getValue();

            if (value == null || value.isEmpty() || value.equals("null")) {
                targetCompID.setValue(settings.getTargetCompID());
            }
        }

        if (settings.getSenderSubID() != null) {
            FixField senderSubID = findField(message, SENDER_SUB_ID_TAG, US_ASCII, bodyLength);

            if (senderSubID == null) {
                senderSubID = targetCompID.insertNext(SENDER_SUB_ID_TAG, settings.getSenderSubID());
            } else {
                String value = senderSubID.getValue();

                if (value == null || value.isEmpty() || value.equals("null")) {
                    senderSubID.setValue(settings.getSenderSubID());
                }
            }
        }

        FixField sendingTime = findField(message, SENDING_TIME_TAG, US_ASCII, bodyLength);

        if (sendingTime == null) {
            sendingTime = targetCompID.insertNext(SENDING_TIME_TAG, getTime());
        } else {
            String value = sendingTime.getValue();

            if (value == null || value.isEmpty() || value.equals("null")) {
                sendingTime.setValue(getTime());
            }
        }

        if(settings.isSaveAdminMessages()) {
            outgoingMessages.put(msgSeqNumValue, message.copy());
        } else if(msgType != null && !ADMIN_MESSAGES.contains(msgType)) {
            outgoingMessages.put(msgSeqNumValue, message.copy());
        }

        updateLength(message);
        updateChecksum(message);
    }

    @Override
    public void onOpen(@NotNull IChannel channel) {
        this.channel = channel;
        sendLogon();
    }

    public void sendHeartbeat() {
        long secondsSinceLastSend = (System.currentTimeMillis() - lastSendTime) / 1000;

        if (secondsSinceLastSend < settings.getHeartBtInt()) {
            return;
        }

        StringBuilder heartbeat = new StringBuilder();
        int seqNum = msgSeqNum.incrementAndGet();

        setHeader(heartbeat, MSG_TYPE_HEARTBEAT, seqNum);
        setChecksumAndBodyLength(heartbeat);

        if (enabled.get()) {
            LOGGER.info("Send Heartbeat to server - {}", heartbeat);
            channel.send(Unpooled.wrappedBuffer(heartbeat.toString().getBytes(StandardCharsets.UTF_8)), Collections.emptyMap(), null, IChannel.SendMode.MANGLE);
            lastSendTime = System.currentTimeMillis();
        } else {
            sendLogon();
        }
    }

    public void sendTestRequest() { //do private
        lastSendTime = System.currentTimeMillis();
        StringBuilder testRequest = new StringBuilder();
        setHeader(testRequest, MSG_TYPE_TEST_REQUEST, msgSeqNum.get());
        testRequest.append(TEST_REQ_ID).append(testReqID.incrementAndGet());
        setChecksumAndBodyLength(testRequest);
        if (enabled.get()) {
            channel.send(Unpooled.wrappedBuffer(testRequest.toString().getBytes(StandardCharsets.UTF_8)), Collections.emptyMap(), null, IChannel.SendMode.MANGLE);
            LOGGER.info("Send TestRequest to server - {}", testRequest);
        } else {
            sendLogon();
        }
        reconnectRequestTimer = executorService.schedule(this::sendLogon, settings.getReconnectDelay(), TimeUnit.SECONDS);
    }

    public void sendLogon() {
        if(!sessionActive.get() || !channel.isOpen()) {
            LOGGER.info("Logon is not sent to server because session is not active.");
            return;
        }
        lastSendTime = System.currentTimeMillis();
        StringBuilder logon = new StringBuilder();
        Boolean reset;
        if (!connStarted.get()) reset = settings.getResetSeqNumFlag();
        else reset = settings.getResetOnLogon();
        if (reset) msgSeqNum.getAndSet(0);

        setHeader(logon, MSG_TYPE_LOGON, msgSeqNum.incrementAndGet());
        if (settings.useNextExpectedSeqNum()) logon.append(NEXT_EXPECTED_SEQ_NUM).append(serverMsgSeqNum.get() + 1);
        if (settings.getEncryptMethod() != null) logon.append(ENCRYPT_METHOD).append(settings.getEncryptMethod());
        logon.append(HEART_BT_INT).append(settings.getHeartBtInt());
        if (reset) logon.append(RESET_SEQ_NUM).append("Y");
        if (settings.getDefaultApplVerID() != null) logon.append(DEFAULT_APPL_VER_ID).append(settings.getDefaultApplVerID());
        if (settings.getUsername() != null) logon.append(USERNAME).append(settings.getUsername());
        if (settings.getPassword() != null) {
            if (settings.getPasswordEncryptKeyFilePath() != null) {
                logon.append(ENCRYPTED_PASSWORD).append(encrypt(settings.getPassword()));
            } else {
                logon.append(PASSWORD).append(settings.getPassword());
            }
        }
        if (settings.getNewPassword() != null) {
            if (settings.getPasswordEncryptKeyFilePath() != null) {
                logon.append(NEW_ENCRYPTED_PASSWORD).append(encrypt(settings.getNewPassword()));
            } else {
                logon.append(NEW_PASSWORD).append(settings.getNewPassword());
            }
        }

        setChecksumAndBodyLength(logon);
        LOGGER.info("Send logon - {}", logon);
        channel.send(Unpooled.wrappedBuffer(logon.toString().getBytes(StandardCharsets.UTF_8)), Collections.emptyMap(), null, IChannel.SendMode.MANGLE);
    }

    private void sendLogout() {
        if (enabled.get()) {
            StringBuilder logout = new StringBuilder();
            setHeader(logout, MSG_TYPE_LOGOUT, msgSeqNum.incrementAndGet());
            setChecksumAndBodyLength(logout);
            channel.send(Unpooled.wrappedBuffer(logout.toString().getBytes(StandardCharsets.UTF_8)), Collections.emptyMap(), null, IChannel.SendMode.MANGLE);
        }
        if(settings.getStateFilePath() != null) {
            try {
                LOGGER.info("Saving sequences: client - {}, server - {}", msgSeqNum.get(), serverMsgSeqNum.get());
                Util.writeSequences(msgSeqNum.get(), serverMsgSeqNum.get(), settings.getStateFilePath());
            } catch (IOException e) {
                if (LOGGER.isErrorEnabled()) {
                    LOGGER.error("Sequence number were not saved: clientSeq - {}, serverSeq - {}", msgSeqNum.get(), serverMsgSeqNum.get(), e);
                }
            }
        }
        enabled.set(false);
    }

    private String encrypt(String password) {
        return settings.getPasswordEncryptKeyFileType()
                .encrypt(Paths.get(settings.getPasswordEncryptKeyFilePath()),
                        password,
                        settings.getPasswordKeyEncryptAlgorithm(),
                        settings.getPasswordEncryptAlgorithm(),
                        ENCRYPT_MODE);
    }

    @Override
    public void onClose(@NotNull IChannel channel) {
        enabled.set(false);
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel(false);
        }
        if (testRequestTimer != null) {
            testRequestTimer.cancel(false);
        }
    }

    @Override
    public void close() {
        sendLogout();
    }

    private void setHeader(StringBuilder stringBuilder, String msgType, Integer seqNum) {
        stringBuilder.append(BEGIN_STRING_TAG).append("=").append(settings.getBeginString());
        stringBuilder.append(MSG_TYPE).append(msgType);
        stringBuilder.append(MSG_SEQ_NUM).append(seqNum);
        if (settings.getSenderCompID() != null) stringBuilder.append(SENDER_COMP_ID).append(settings.getSenderCompID());
        if (settings.getTargetCompID() != null) stringBuilder.append(TARGET_COMP_ID).append(settings.getTargetCompID());
        if (settings.getSenderSubID() != null) stringBuilder.append(SENDER_SUB_ID).append(settings.getSenderSubID());
        stringBuilder.append(SENDING_TIME).append(getTime());
    }

    private void setChecksumAndBodyLength(StringBuilder stringBuilder) {
        stringBuilder.append(CHECKSUM).append("000").append(SOH);
        stringBuilder.insert(stringBuilder.indexOf(MSG_TYPE),
                BODY_LENGTH + getBodyLength(stringBuilder));
        stringBuilder.replace(stringBuilder.lastIndexOf("000" + SOH), stringBuilder.lastIndexOf(SOH), getChecksum(stringBuilder));
    }

    public String getChecksum(StringBuilder message) { //do private

        String substring = message.substring(0, message.indexOf(CHECKSUM) + 1);
        return calculateChecksum(substring.getBytes(US_ASCII));
    }

    public String getChecksum(ByteBuf message) {

        int checksumIdx = indexOf(message, CHECKSUM) + 1;
        if (checksumIdx <= 0) {
            checksumIdx = message.capacity();
        }

        ByteBuf data = message.copy(0, checksumIdx);
        return calculateChecksum(data.array());
    }

    private String calculateChecksum(byte[] data) {
        int total = 0;
        for (byte item : data) {
            total += item;
        }
        int checksum = total % 256;
        return String.format("%03d", checksum);
    }

    public int getBodyLength(StringBuilder message) { //do private

        int start = message.indexOf(SOH, message.indexOf(BODY_LENGTH) + 1);
        int end = message.indexOf(CHECKSUM);
        return end - start;
    }

    public int getBodyLength(ByteBuf message) {
        int bodyLengthIdx = indexOf(message, BODY_LENGTH);
        int start = findByte(message, bodyLengthIdx + 1, BYTE_SOH);
        int end = indexOf(message, CHECKSUM);
        return end - start;
    }

    public String getTime() {
        String FIX_DATE_TIME_FORMAT_MS = "yyyyMMdd-HH:mm:ss.SSSSSSSSS";
        LocalDateTime datetime = LocalDateTime.now();
        return DateTimeFormatter.ofPattern(FIX_DATE_TIME_FORMAT_MS).format(datetime);
    }

    public AtomicBoolean getEnabled() {
        return enabled;
    }

    public Log getOutgoingMessages() {
        return outgoingMessages;
    }
}