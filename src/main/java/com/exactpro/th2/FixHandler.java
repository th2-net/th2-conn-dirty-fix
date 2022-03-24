package com.exactpro.th2;

import com.exactpro.th2.conn.dirty.fix.FixField;
import com.exactpro.th2.conn.dirty.tcp.core.api.IChannel;
import com.exactpro.th2.conn.dirty.tcp.core.api.IContext;
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandler;
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerSettings;
import com.exactpro.th2.conn.dirty.tcp.core.util.CommonUtil;
import com.google.auto.service.AutoService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.exactpro.th2.conn.dirty.fix.FixByteBufUtilKt.*;
import static com.exactpro.th2.conn.dirty.tcp.core.util.ByteBufUtil.indexOf;
import static com.exactpro.th2.constants.Constants.*;
import static com.exactpro.th2.util.MessageUtil.findByte;

//todo parse logout
//todo gapFillTag
//todo ring buffer as cache
//todo add events

@AutoService(IProtocolHandler.class)
public class FixHandler implements AutoCloseable, IProtocolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(FixHandler.class);
    private static final String SOH = "\001";
    private static final byte BYTE_SOH = 1;
    private static final String STRING_MSG_TYPE = "MsgType";
    private static final String REJECT_REASON = "Reject reason";
    private static final String STUBBING_VALUE = "XXX";
    private final Log outgoingMessages = new Log(10000);
    private final AtomicInteger msgSeqNum = new AtomicInteger(0);
    private final AtomicInteger serverMsgSeqNum = new AtomicInteger(0);
    private final AtomicInteger testReqID = new AtomicInteger(0);
    private final AtomicInteger resendRequestSeqNum = new AtomicInteger(0);
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicBoolean connStarted = new AtomicBoolean(false);
    private final ScheduledExecutorService executorService;
    private final IContext<IProtocolHandlerSettings> context;
    private Future<?> heartbeatTimer = CompletableFuture.completedFuture(null);
    private Future<?> testRequestTimer = CompletableFuture.completedFuture(null);
    private Future<?> reconnectRequestTimer = CompletableFuture.completedFuture(null);
    private Future<?> disconnectRequest = CompletableFuture.completedFuture(null);
    private IChannel client;
    protected FixHandlerSettings settings;

    public FixHandler(IContext<IProtocolHandlerSettings> context) {
        this.context = context;
        executorService = Executors.newScheduledThreadPool(1);
        this.settings = (FixHandlerSettings) context.getSettings();
        Objects.requireNonNull(settings.getBeginString(), "BeginString can not be null");
        Objects.requireNonNull(settings.getSenderCompID(), "SenderCompID can not be null");
        Objects.requireNonNull(settings.getTargetCompID(), "TargetCompID can not be null");
        Objects.requireNonNull(settings.getEncryptMethod(), "EncryptMethod can not be null");
        Objects.requireNonNull(settings.getUsername(), "Username can not be null");
        Objects.requireNonNull(settings.getPassword(), "Password can not be null");
        Objects.requireNonNull(settings.getResetSeqNumFlag(), "ResetSeqNumFlag can not be null");
        Objects.requireNonNull(settings.getResetOnLogon(), "ResetOnLogon can not be null");
        if(settings.getHeartBtInt() <= 0) throw new IllegalArgumentException("HeartBtInt cannot be negative or zero");
        if(settings.getDefaultApplVerID() <= 0) throw new IllegalArgumentException("DefaultApplVerID cannot be negative or zero");
        if(settings.getTestRequestDelay() <= 0) throw new IllegalArgumentException("TestRequestDelay cannot be negative or zero");
        if(settings.getDisconnectRequestDelay() <= 0) throw new IllegalArgumentException("DisconnectRequestDelay cannot be negative or zero");
    }

    @Override
    public ByteBuf onReceive(ByteBuf buffer) {
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
                LOGGER.trace("Failed to parse message: {}. No Checksum or no tag separator at the end of the message with index {}", buffer.toString(StandardCharsets.US_ASCII), beginStringIdx);
                throw new Exception();
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
    public Map<String, String> onIncoming(@NotNull ByteBuf message) {
        Map<String, String> metadata = new HashMap<>();

        int beginString = indexOf(message, "8=FIX");

        if (beginString == -1) {
            metadata.put(REJECT_REASON, "Not a FIX message");
            return metadata;
        }

        FixField msgSeqNumValue = findField(message, MSG_SEQ_NUM_TAG);
        if (msgSeqNumValue == null) {
            metadata.put(REJECT_REASON, "No msgSeqNum Field");
            LOGGER.error("Invalid message. No MsgSeqNum in message: {}", message.toString(StandardCharsets.US_ASCII));
            return metadata;
        }

        FixField msgType = findField(message, MSG_TYPE_TAG);
        if (msgType == null) {
            metadata.put(REJECT_REASON, "No msgType Field");
            LOGGER.error("Invalid message. No MsgType in message: {}", message.toString(StandardCharsets.US_ASCII));
            return metadata;
        }

        serverMsgSeqNum.incrementAndGet();
        int receivedMsgSeqNum = Integer.parseInt(Objects.requireNonNull(msgSeqNumValue.getValue()));

        if (serverMsgSeqNum.get() < receivedMsgSeqNum) {
            if (enabled.get()) {
                sendResendRequest(serverMsgSeqNum.get(), receivedMsgSeqNum);
            }
        }

        String msgTypeValue = Objects.requireNonNull(msgType.getValue());
        switch (msgTypeValue) {
            case MSG_TYPE_HEARTBEAT:
                LOGGER.info("Heartbeat received - " + message.toString(StandardCharsets.US_ASCII));
                checkHeartbeat(message);
                testRequestTimer = executorService.schedule(this::sendTestRequest, settings.getTestRequestDelay(), TimeUnit.SECONDS);
                break;
            case MSG_TYPE_LOGON:
                LOGGER.info("Logon received - " + message.toString(StandardCharsets.US_ASCII));
                boolean connectionSuccessful = checkLogon(message);
                enabled.set(connectionSuccessful);
                if (connectionSuccessful) {
                    if (!connStarted.get()){
                        connStarted.set(true);
                    }

                    if (heartbeatTimer != null) {
                        heartbeatTimer.cancel(false);
                    }
                    heartbeatTimer = executorService.scheduleWithFixedDelay(this::sendHeartbeat, settings.getHeartBtInt(), settings.getHeartBtInt(), TimeUnit.SECONDS);

                    testRequestTimer = executorService.schedule(this::sendTestRequest, settings.getTestRequestDelay(), TimeUnit.SECONDS);
                } else {
                    reconnectRequestTimer = executorService.schedule(this::sendLogon, settings.getReconnectDelay(), TimeUnit.SECONDS);
                }
                break;
            case MSG_TYPE_LOGOUT: //extract logout reason
                LOGGER.info("Logout received - " + message.toString(StandardCharsets.US_ASCII));
                FixField text = findField(message, TEXT_TAG);
                if (text != null) {
                    LOGGER.warn("Received Logout has text (58) tag: " + text.getValue());
                    String value = StringUtils.substringBetween(text.getValue(), "expecting ", " but received");
                    if (value != null) {
                        msgSeqNum.getAndSet(Integer.parseInt(value)-2);
                        serverMsgSeqNum.getAndSet(Integer.parseInt(msgSeqNumValue.getValue()));
                    }
                }
                if (disconnectRequest != null && !disconnectRequest.isCancelled()) {
                    disconnectRequest.cancel(false);
                }
                enabled.set(false);
                context.send(CommonUtil.toEvent("logout for sender - " + settings.getSenderCompID()));//make more useful
                break;
            case MSG_TYPE_RESEND_REQUEST:
                LOGGER.info("Resend_request received - " + message.toString(StandardCharsets.US_ASCII));
                handleResendRequest(message);
                break;
            case MSG_TYPE_SEQUENCE_RESET: //gap fill
                LOGGER.info("Sequence_reset received - " + message.toString(StandardCharsets.US_ASCII));
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

        FixField gapFillFlagValue = findField(message, GAP_FILL_FLAG_TAG);
        FixField seqNumValue = findField(message, NEW_SEQ_NO_TAG);

        if (seqNumValue != null && (gapFillFlagValue == null || Objects.requireNonNull(gapFillFlagValue.getValue()).equals("N"))) {
            serverMsgSeqNum.set(Integer.parseInt(Objects.requireNonNull(seqNumValue.getValue())));
        } else {
            LOGGER.trace("Failed to reset servers MsgSeqNum. No such tag in message: {}", message.toString(StandardCharsets.US_ASCII));
        }
    }

    public void sendResendRequest(int beginSeqNo, int endSeqNo) { //do private
        StringBuilder resendRequest = new StringBuilder();
        setHeader(resendRequest, MSG_TYPE_RESEND_REQUEST, msgSeqNum.incrementAndGet());
        resendRequest.append(BEGIN_SEQ_NO).append(beginSeqNo).append(SOH);
        resendRequest.append(END_SEQ_NO).append(endSeqNo).append(SOH);
        setChecksumAndBodyLength(resendRequest);
        client.send(Unpooled.wrappedBuffer(resendRequest.toString().getBytes(StandardCharsets.UTF_8)), Collections.emptyMap(), IChannel.SendMode.MANGLE);
    }


    void sendResendRequest(int beginSeqNo) { //do private

        StringBuilder resendRequest = new StringBuilder();
        setHeader(resendRequest, MSG_TYPE_RESEND_REQUEST, msgSeqNum.incrementAndGet());
        resendRequest.append(BEGIN_SEQ_NO).append(beginSeqNo);
        resendRequest.append(END_SEQ_NO).append(0);
        setChecksumAndBodyLength(resendRequest);

        if (enabled.get()) {
            client.send(Unpooled.wrappedBuffer(resendRequest.toString().getBytes(StandardCharsets.UTF_8)), Collections.emptyMap(), IChannel.SendMode.MANGLE);
        } else {
            sendLogon();
        }
    }

    private void handleResendRequest(ByteBuf message) {
        if (disconnectRequest != null && !disconnectRequest.isCancelled()) {
            disconnectRequest.cancel(false);
        }

        FixField strBeginSeqNo = findField(message, BEGIN_SEQ_NO_TAG);
        FixField strEndSeqNo = findField(message, END_SEQ_NO_TAG);

        if (strBeginSeqNo != null && strEndSeqNo != null) {
            int beginSeqNo = Integer.parseInt(Objects.requireNonNull(strBeginSeqNo.getValue()));
            int endSeqNo = Integer.parseInt(Objects.requireNonNull(strEndSeqNo.getValue()));

            try {
                if (endSeqNo == 0) endSeqNo = msgSeqNum.get() - 1;
                LOGGER.info("Returning messages from " + beginSeqNo + " to " + endSeqNo);
                for (int i = beginSeqNo; i <= endSeqNo; i++) {
                    if (outgoingMessages.get(i) != null) {
                        LOGGER.info("Returning message - " + outgoingMessages.get(i).toString(StandardCharsets.US_ASCII));
                        client.send(outgoingMessages.get(i), Collections.emptyMap(), IChannel.SendMode.MANGLE);
                    }
                    else {
                        resendRequestSeqNum.getAndSet(i);
                        sendHeartbeat();
                    }
                }
                resendRequestSeqNum.getAndSet(0);
            } catch (Exception e) {
                sendSequenceReset();
            }
        }
    }

    private void sendSequenceReset() {
        StringBuilder sequenceReset = new StringBuilder();
        setHeader(sequenceReset, MSG_TYPE_SEQUENCE_RESET, msgSeqNum.incrementAndGet());
        sequenceReset.append(NEW_SEQ_NO).append(msgSeqNum.get() + 1);
        setChecksumAndBodyLength(sequenceReset);

        if (enabled.get()) {
            client.send(Unpooled.wrappedBuffer(sequenceReset.toString().getBytes(StandardCharsets.UTF_8)), Collections.emptyMap(), IChannel.SendMode.MANGLE);
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
        if (sessionStatusField == null || Objects.requireNonNull(sessionStatusField.getValue()).equals("0")) {
            FixField msgSeqNumValue = findField(message, MSG_SEQ_NUM_TAG);
            if (msgSeqNumValue == null) {
                return false;
            }
            serverMsgSeqNum.set(Integer.parseInt(Objects.requireNonNull(msgSeqNumValue.getValue())));
            context.send(CommonUtil.toEvent("successful login"));
            return true;
        }
        return false;
    }

    @NotNull
    @Override
    public Map<String, String> onOutgoing(@NotNull ByteBuf message, @NotNull Map<String, String> metadata) {
        String sendMode = metadata.get("send-mode");

        if (sendMode == null || Objects.equals(sendMode, "")) {
            onOutgoingUpdateTag(message, metadata);
        }
        else if (Objects.equals(sendMode, "semi-manual")){
            onOutgoingNotUpdateTag(message, metadata);
        }
        LOGGER.info("Outgoing message - " + message.toString(StandardCharsets.US_ASCII));

        return metadata;
    }

    public void onOutgoingUpdateTag(@NotNull ByteBuf message, @NotNull Map<String, String> metadata){
        message.readerIndex(0);
        FixField beginString = findField(message, BEGIN_STRING_TAG);
        if (beginString == null) {
            addFieldBefore(message, BEGIN_STRING_TAG, settings.getBeginString());
        }

        FixField bodyLength = findField(message, BODY_LENGTH_TAG);
        if (bodyLength == null) {
            addFieldAfter(message, BODY_LENGTH_TAG, STUBBING_VALUE, BEGIN_STRING_TAG); //stubbing until finish checking message
        }

        FixField msgType = findField(message, MSG_TYPE_TAG);
        if (msgType == null) {                                                        //should we interrupt sending message?
            LOGGER.error("No msgType in message {}", new String(message.array()));
            if (metadata.get("MsgType") != null) {
                addFieldAfter(message, MSG_TYPE_TAG, metadata.get("MsgType"), BODY_LENGTH_TAG);
            }
        }

        FixField checksum = findField(message, CHECKSUM_TAG);
        if (checksum == null) {
            addFieldAfter(message, CHECKSUM_TAG, STUBBING_VALUE); //stubbing until finish checking message
        }

        int msgSeqNumValue = msgSeqNum.incrementAndGet();
        FixField msgSeqNum = findField(message, MSG_SEQ_NUM_TAG);
        if (msgSeqNum == null) {
            if (findField(message, MSG_TYPE_TAG) != null) {
                addFieldAfter(message, MSG_SEQ_NUM_TAG, Integer.toString(msgSeqNumValue), MSG_TYPE_TAG);
            } else {
                addFieldAfter(message, MSG_SEQ_NUM_TAG, Integer.toString(msgSeqNumValue), BODY_LENGTH_TAG);
            }
        } else {
            replaceAndMoveFieldValue(message, msgSeqNum, Integer.toString(msgSeqNumValue), MSG_TYPE_TAG);
        }

        FixField senderCompID = findField(message, SENDER_COMP_ID_TAG);
        if (senderCompID == null) {
            addFieldAfter(message, SENDER_COMP_ID_TAG, settings.getSenderCompID(), MSG_SEQ_NUM_TAG);
        } else {
            String value = senderCompID.getValue();
            if (!Objects.equals(value, "null") && !Objects.equals(value, "") && !Objects.equals(value, null)) {
                replaceAndMoveFieldValue(message, senderCompID, value, MSG_SEQ_NUM_TAG);
            } else {
                replaceAndMoveFieldValue(message, senderCompID, settings.getSenderCompID(), MSG_TYPE_TAG);
            }
        }

        FixField targetCompID = findField(message, TARGET_COMP_ID_TAG);
        if (targetCompID == null) {
            addFieldAfter(message, TARGET_COMP_ID_TAG, settings.getTargetCompID(), SENDER_COMP_ID_TAG);
        } else {
            String value = targetCompID.getValue();
            if (!Objects.equals(value, "null") && !Objects.equals(value, "") && !Objects.equals(value, null)) {
                replaceAndMoveFieldValue(message, targetCompID, value, SENDER_COMP_ID_TAG);
            } else {
                replaceAndMoveFieldValue(message, targetCompID, settings.getTargetCompID(), SENDER_COMP_ID_TAG);
            }
        }

        FixField sendingTime = findField(message, SENDING_TIME_TAG);
        if (sendingTime == null) {
            addFieldAfter(message, SENDING_TIME_TAG, getTime(), TARGET_COMP_ID_TAG);
        } else {
            String value = sendingTime.getValue();
            if (!Objects.equals(value, "null") && !Objects.equals(value, "") && !Objects.equals(value, null)) {
                replaceAndMoveFieldValue(message, sendingTime, value, TARGET_COMP_ID_TAG);
            } else {
                replaceAndMoveFieldValue(message, sendingTime, getTime(), TARGET_COMP_ID_TAG);
            }
        }

        replaceFieldValue(message, BODY_LENGTH_TAG, bodyLength != null ? bodyLength.getValue() : STUBBING_VALUE, Integer.toString(getBodyLength(message)));
        updateChecksum(message);
    }

    public void onOutgoingNotUpdateTag(@NotNull ByteBuf message, @NotNull Map<String, String> metadata){
        message.readerIndex(0);
        FixField beginString = findField(message, BEGIN_STRING_TAG);
        if (beginString == null) {
            addFieldBefore(message, BEGIN_STRING_TAG, settings.getBeginString());
        }

        FixField bodyLength = findField(message, BODY_LENGTH_TAG);
        if (bodyLength == null) {
            addFieldAfter(message, BODY_LENGTH_TAG, STUBBING_VALUE, BEGIN_STRING_TAG); //stubbing until finish checking message
        }

        FixField msgType = findField(message, MSG_TYPE_TAG);
        if (msgType == null) {                                                        //should we interrupt sending message?
            LOGGER.error("No msgType in message {}", new String(message.array()));
            if (metadata.get("MsgType") != null) {
                addFieldAfter(message, MSG_TYPE_TAG, metadata.get("MsgType"), BODY_LENGTH_TAG);
            }
        }

        FixField checksum = findField(message, CHECKSUM_TAG);
        if (checksum == null) {
            addFieldAfter(message, CHECKSUM_TAG, STUBBING_VALUE); //stubbing until finish checking message
        }

        int msgSeqNumValue = msgSeqNum.incrementAndGet();
        FixField msgSeqNum = findField(message, MSG_SEQ_NUM_TAG);
        if (msgSeqNum == null) {
            if (findField(message, MSG_TYPE_TAG)!=null) {
                addFieldAfter(message, MSG_SEQ_NUM_TAG, Integer.toString(msgSeqNumValue),MSG_TYPE_TAG);
            }
            else {
                addFieldAfter(message, MSG_SEQ_NUM_TAG, Integer.toString(msgSeqNumValue), BODY_LENGTH_TAG);
            }
        } else {
            moveFieldValue(message, msgSeqNum, MSG_TYPE_TAG);
        }

        FixField senderCompID = findField(message, SENDER_COMP_ID_TAG);
        if (senderCompID == null) {
            addFieldAfter(message, SENDER_COMP_ID_TAG, settings.getSenderCompID(), MSG_SEQ_NUM_TAG);
        }
        else {
            moveFieldValue(message, senderCompID, MSG_TYPE_TAG);
        }

        FixField targetCompID = findField(message, TARGET_COMP_ID_TAG);
        if (targetCompID == null) {
            addFieldAfter(message, TARGET_COMP_ID_TAG, settings.getTargetCompID(), SENDER_COMP_ID_TAG);
        }
        else {
            moveFieldValue(message, targetCompID, SENDER_COMP_ID_TAG);
        }

        FixField sendingTime = findField(message, SENDING_TIME_TAG);
        if (sendingTime == null) {
            addFieldAfter(message, SENDING_TIME_TAG, getTime(), TARGET_COMP_ID_TAG);
        }
        else {
            moveFieldValue(message, sendingTime, TARGET_COMP_ID_TAG);
        }

        if (bodyLength == null){
            replaceFieldValue(message, BODY_LENGTH_TAG, bodyLength != null ? bodyLength.getValue() : STUBBING_VALUE, Integer.toString(getBodyLength(message)));
        }
        if(checksum == null){
            updateChecksum(message);
        }

        outgoingMessages.put(msgSeqNumValue, message);
    }

    @Override
    public void onOpen() {
        this.client = context.getChannel();
        sendLogon();
    }

    public void sendHeartbeat() {
        StringBuilder heartbeat = new StringBuilder();
        int seqNum = resendRequestSeqNum.get();
        if (seqNum==0) seqNum = msgSeqNum.incrementAndGet();

        setHeader(heartbeat, MSG_TYPE_HEARTBEAT, seqNum);

        setChecksumAndBodyLength(heartbeat);
        if (enabled.get()) {
            LOGGER.info("Send Heartbeat to server - " + heartbeat);
            client.send(Unpooled.wrappedBuffer(heartbeat.toString().getBytes(StandardCharsets.UTF_8)), Collections.emptyMap(), IChannel.SendMode.MANGLE);
        } else {
            sendLogon();
        }
    }

    public void sendTestRequest() { //do private
        StringBuilder testRequest = new StringBuilder();
        setHeader(testRequest, MSG_TYPE_TEST_REQUEST, msgSeqNum.get());
        testRequest.append(TEST_REQ_ID).append(testReqID.incrementAndGet());
        setChecksumAndBodyLength(testRequest);
        if (enabled.get()) {
            client.send(Unpooled.wrappedBuffer(testRequest.toString().getBytes(StandardCharsets.UTF_8)), Collections.emptyMap(), IChannel.SendMode.MANGLE);
            LOGGER.info("Send TestRequest to server - " + testRequest);
        } else {
            sendLogon();
        }
        reconnectRequestTimer = executorService.schedule(this::sendLogon, settings.getReconnectDelay(), TimeUnit.SECONDS);
    }

    public void sendLogon() {
        StringBuilder logon = new StringBuilder();
        Boolean reset;
        if (!connStarted.get()) reset = settings.getResetSeqNumFlag();
        else reset = settings.getResetOnLogon();
        if (reset) msgSeqNum.getAndSet(0);

        setHeader(logon, MSG_TYPE_LOGON, msgSeqNum.incrementAndGet());
        logon.append(ENCRYPT_METHOD).append(settings.getEncryptMethod());
        logon.append(HEART_BT_INT).append(settings.getHeartBtInt());
        if (reset) logon.append(RESET_SEQ_NUM).append("Y");
        logon.append(DEFAULT_APPL_VER_ID).append(settings.getDefaultApplVerID());
        logon.append(USERNAME).append(settings.getUsername());
        logon.append(PASSWORD).append(settings.getPassword());

        setChecksumAndBodyLength(logon);
        LOGGER.info("Send logon - " + logon);
        client.send(Unpooled.wrappedBuffer(logon.toString().getBytes(StandardCharsets.UTF_8)), Collections.emptyMap(), IChannel.SendMode.MANGLE);
    }

    @Override
    public void onClose() {
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
        StringBuilder logout = new StringBuilder();
        setHeader(logout, MSG_TYPE_LOGOUT, msgSeqNum.incrementAndGet());
        setChecksumAndBodyLength(logout);

        if (enabled.get()) {
            client.send(Unpooled.wrappedBuffer(logout.toString().getBytes(StandardCharsets.UTF_8)), Collections.emptyMap(), IChannel.SendMode.MANGLE);
        }
        disconnectRequest = executorService.schedule(() -> enabled.set(false), settings.getDisconnectRequestDelay(), TimeUnit.SECONDS);
    }

    private void setHeader(StringBuilder stringBuilder, String msgType, Integer seqNum) {
        stringBuilder.append(BEGIN_STRING_TAG).append("=").append(settings.getBeginString());
        stringBuilder.append(MSG_TYPE).append(msgType);
        stringBuilder.append(MSG_SEQ_NUM).append(seqNum);
        stringBuilder.append(SENDER_COMP_ID).append(settings.getSenderCompID());
        stringBuilder.append(TARGET_COMP_ID).append(settings.getTargetCompID());
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
        return calculateChecksum(substring.getBytes(StandardCharsets.US_ASCII));
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
        String FIX_DATE_TIME_FORMAT_MS = "yyyyMMdd-HH:mm:ss.SSS";
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