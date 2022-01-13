package com.exactpro.th2;

import com.exactpro.th2.conn.dirty.tcp.core.api.IChannel;
import com.exactpro.th2.conn.dirty.tcp.core.api.IContext;
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandler;
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerSettings;
import com.exactpro.th2.conn.dirty.tcp.core.util.ByteBufUtil;
import com.exactpro.th2.conn.dirty.tcp.core.util.CommonUtil;
import com.exactpro.th2.util.MessageUtil;
import com.google.auto.service.AutoService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.exactpro.th2.constants.Constants.*;

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
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final ScheduledExecutorService executorService;
    private final IContext<IProtocolHandlerSettings> context;
    private Future<?> heartbeatTimer;
    private Future<?> testRequestTimer;
    private Future<?> reconnectRequestTimer;
    private Future<?> disconnectRequest;
    private IChannel client;
    protected FixHandlerSettings settings;

    public FixHandler(IContext<IProtocolHandlerSettings> context) {
        this.context = context;
        executorService = Executors.newScheduledThreadPool(1);
    }

    @Override
    public ByteBuf onReceive(ByteBuf buffer) {

        int offset = buffer.readerIndex();
        if (offset == buffer.writerIndex()) return null;
        int beginStringIdx = ByteBufUtil.indexOf(buffer,"8=FIX");
        if (beginStringIdx == -1) {
            if (buffer.writerIndex() > 0) {
                buffer.readerIndex(buffer.writerIndex());
                return buffer.copy(offset, buffer.writerIndex() - offset);
            }
            return null;
        }

        if (beginStringIdx > offset) {
            buffer.readerIndex(beginStringIdx);
            return buffer.copy(offset, beginStringIdx - offset);
        }

        int nextBeginString = ByteBufUtil.indexOf(buffer, SOH + "8=FIX") + 1;
        int checksum = ByteBufUtil.indexOf(buffer, SOH + CHECKSUM, beginStringIdx);
        int endOfMessageIdx = checksum + 7; //checksum is always 3 digits // or we should search next soh?

        try {
            if (checksum == -1 || buffer.getByte(endOfMessageIdx) != BYTE_SOH || (nextBeginString > 0 && nextBeginString < endOfMessageIdx)) {
                LOGGER.trace("Failed to parse message: {}. No Checksum or no tag separator at the end of the message with index {}", buffer.toString(StandardCharsets.US_ASCII), beginStringIdx);
                throw new Exception();
            }
        } catch (Exception e) {
            if (nextBeginString > 0) {
                buffer.readerIndex(nextBeginString);
                return buffer.copy(beginStringIdx, nextBeginString - beginStringIdx);
            } else {
                buffer.readerIndex(buffer.writerIndex());
                return buffer.copy(beginStringIdx, buffer.writerIndex() - beginStringIdx);
            }
        }

        buffer.readerIndex(endOfMessageIdx + 1);
        return buffer.copy(beginStringIdx, endOfMessageIdx + 1 - beginStringIdx);
    }

    @NotNull
    @Override
    public Map<String, String> onIncoming(@NotNull ByteBuf message) {

        Map<String, String> metadata = new HashMap<>();

        int beginString = ByteBufUtil.indexOf(message, "8=FIX");

        if (beginString == -1) {
            metadata.put(REJECT_REASON, "Not a FIX message");
            return metadata;
        }

        String msgSeqNumValue = MessageUtil.getTagValue(message, MSG_SEQ_NUM_TAG);
        if (msgSeqNumValue == null) {
            metadata.put(REJECT_REASON, "No msgSeqNum Field");
            LOGGER.error("Invalid message. No MsgSeqNum in message: {}", message.toString(StandardCharsets.US_ASCII));
            return metadata;
        }

        String msgType = MessageUtil.getTagValue(message, MSG_TYPE_TAG);
        if (msgType == null) {
            metadata.put(REJECT_REASON, "No msgType Field");
            LOGGER.error("Invalid message. No MsgType in message: {}", message.toString(StandardCharsets.US_ASCII));
            return metadata;
        }

        serverMsgSeqNum.incrementAndGet();
        int receivedMsgSeqNum = Integer.parseInt(msgSeqNumValue);
        if (serverMsgSeqNum.get() < receivedMsgSeqNum) {
            sendResendRequest(serverMsgSeqNum.get(), receivedMsgSeqNum);
        }

        switch (msgType) {
            case MSG_TYPE_HEARTBEAT:
                checkHeartbeat(message);
            case MSG_TYPE_LOGON:
                boolean connectionSuccessful = checkLogon(message);
                enabled.set(connectionSuccessful);
                if (connectionSuccessful) {
                    heartbeatTimer = executorService.scheduleWithFixedDelay(this::sendHeartbeat, settings.getHeartBtInt(), settings.getHeartBtInt(), TimeUnit.SECONDS);
                } else {
                    reconnectRequestTimer = executorService.schedule(this::sendLogon, settings.getReconnectDelay(), TimeUnit.SECONDS);
                }
                break;
            case MSG_TYPE_LOGOUT: //extract logout reason
                if (disconnectRequest != null && !disconnectRequest.isCancelled()) {
                    disconnectRequest.cancel(false);
                }
                enabled.set(false);
                context.send(CommonUtil.toEvent("logout for sender - " + settings.getSenderCompID()));//make more useful
                break;
            case MSG_TYPE_RESEND_REQUEST:
                handleResendRequest(message);
                break;
            case MSG_TYPE_SEQUENCE_RESET: //gap fill
                resetSequence(message);
                break;
        }

        if (testRequestTimer != null && !testRequestTimer.isCancelled()) {
            testRequestTimer.cancel(false);
        }

        metadata.put(STRING_MSG_TYPE, msgType);

        return metadata;
    }

    private void resetSequence(ByteBuf message) {

        String gapFillFlagValue = MessageUtil.getTagValue(message, GAP_FILL_FLAG_TAG);
        String seqNumValue = MessageUtil.getTagValue(message, NEW_SEQ_NO_TAG);

        if (seqNumValue != null && (gapFillFlagValue == null || gapFillFlagValue.equals("N"))) {
            serverMsgSeqNum.set(Integer.parseInt(seqNumValue));
        } else {
            LOGGER.trace("Failed to reset servers MsgSeqNum. No such tag in message: {}", message.toString(StandardCharsets.US_ASCII));
        }
    }

    public void sendResendRequest(int beginSeqNo, int endSeqNo) { //do private

        StringBuilder resendRequest = new StringBuilder();
        setHeader(resendRequest, MSG_TYPE_RESEND_REQUEST);
        resendRequest.append(BEGIN_SEQ_NO).append(beginSeqNo).append(SOH);
        resendRequest.append(END_SEQ_NO).append(endSeqNo).append(SOH);
        setChecksumAndBodyLength(resendRequest);

        if (enabled.get()) {
            client.send(Unpooled.wrappedBuffer(resendRequest.toString().getBytes(StandardCharsets.UTF_8)), Collections.emptyMap(), IChannel.SendMode.MANGLE);
        } else {
            sendLogon();
        }
    }


    void sendResendRequest(int beginSeqNo) { //do private

        StringBuilder resendRequest = new StringBuilder();
        setHeader(resendRequest, MSG_TYPE_RESEND_REQUEST);
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

        String strBeginSeqNo = MessageUtil.getTagValue(message, BEGIN_SEQ_NO_TAG);
        String strEndSeqNo = MessageUtil.getTagValue(message, END_SEQ_NO_TAG);

        if (strBeginSeqNo != null && strEndSeqNo != null) {
            int beginSeqNo = Integer.parseInt(strBeginSeqNo);
            int endSeqNo = Integer.parseInt(strBeginSeqNo);

            try {
                for (int i = beginSeqNo; i <= endSeqNo; i++) {
                    client.send(outgoingMessages.get(i), Collections.emptyMap(), IChannel.SendMode.MANGLE);
                }
            } catch (Exception e) {
                sendSequenceReset();
            }
        }
    }

    private void sendSequenceReset() {
        StringBuilder sequenceReset = new StringBuilder();
        setHeader(sequenceReset, MSG_TYPE_SEQUENCE_RESET);
        sequenceReset.append(NEW_SEQ_NO).append(msgSeqNum.get() + 1).append(SOH);
        setChecksumAndBodyLength(sequenceReset);

        if (enabled.get()) {
            client.send(Unpooled.wrappedBuffer(sequenceReset.toString().getBytes(StandardCharsets.UTF_8)), Collections.emptyMap(), IChannel.SendMode.MANGLE);
        } else {
            sendLogon();
        }
    }

    private void checkHeartbeat(ByteBuf message) {

        String receivedTestReqID = MessageUtil.getTagValue(message, TEST_REQ_ID_TAG);

        if (receivedTestReqID != null) {
            if (receivedTestReqID.equals(Integer.toString(testReqID.get()))) {
                reconnectRequestTimer.cancel(false);
            }
        }
    }

    private boolean checkLogon(ByteBuf message) {

        String sessionStatusField = MessageUtil.getTagValue(message, SESSION_STATUS_TAG); //check another options
        if (sessionStatusField != null && sessionStatusField.equals("0")) {
            String msgSeqNumValue = MessageUtil.getTagValue(message, MSG_SEQ_NUM_TAG);
            if (msgSeqNumValue == null) {
                return false;
            }
            serverMsgSeqNum.set(Integer.parseInt(msgSeqNumValue));
            context.send(CommonUtil.toEvent("successful login"));
            return true;
        }
        return false;
    }

    @NotNull
    @Override
    public Map<String, String> onOutgoing(@NotNull ByteBuf message, @NotNull Map<String, String> metadata) {

        if (heartbeatTimer != null) {
            heartbeatTimer.cancel(false);
            heartbeatTimer = executorService.scheduleWithFixedDelay(this::sendHeartbeat, settings.getHeartBtInt(), settings.getHeartBtInt(), TimeUnit.SECONDS);
        }

        message.readerIndex(0);

        int beginString = ByteBufUtil.indexOf(message, BEGIN_STRING_TAG + "=");
        if (beginString < 0) {
            message = MessageUtil.putTag(message, BEGIN_STRING_TAG, settings.getBeginString());
        }

        int bodyLength = ByteBufUtil.indexOf(message, BODY_LENGTH);
        if (bodyLength < 0) {
            message = MessageUtil.putTag(message, BODY_LENGTH_TAG, STUBBING_VALUE); //stubbing until finish checking message
        }

        int msgType = ByteBufUtil.indexOf(message, MSG_TYPE);
        if (msgType < 0) {                                                        //should we interrupt sending message?
            LOGGER.error("No msgType in message {}", new String(message.array()));
        } else {
            metadata.put(STRING_MSG_TYPE, MessageUtil.getTagValue(message, MSG_TYPE_TAG));
        }

        int checksum = ByteBufUtil.indexOf(message, CHECKSUM);
        if (checksum < 0) {
            message = MessageUtil.putTag(message, CHECKSUM_TAG, STUBBING_VALUE); //stubbing until finish checking message
        }

        int senderCompID = ByteBufUtil.indexOf(message, SENDER_COMP_ID);
        if (senderCompID < 0) {
            message = MessageUtil.putTag(message, SENDER_COMP_ID_TAG, settings.getSenderCompID());
        }

        int targetCompID = ByteBufUtil.indexOf(message, TARGET_COMP_ID);
        if (targetCompID < 0) {
            message = MessageUtil.putTag(message, TARGET_COMP_ID_TAG, settings.getTargetCompID());
        }

        int sendingTime = ByteBufUtil.indexOf(message, SENDING_TIME);
        if (sendingTime < 0) {
            message = MessageUtil.putTag(message, SENDING_TIME_TAG, getTime().toString());
        }

        int msgSeqNumValue = msgSeqNum.incrementAndGet();
        int msgSeqNum = ByteBufUtil.indexOf(message, MSG_SEQ_NUM);
        if (msgSeqNum < 0) {
            message = MessageUtil.putTag(message, MSG_SEQ_NUM_TAG, Integer.toString(msgSeqNumValue));
        }else {
            ByteBufUtil.insert(message, Integer.toString(msgSeqNumValue), msgSeqNum);
            message = MessageUtil.updateTag(message, MSG_SEQ_NUM_TAG, Integer.toString(msgSeqNumValue));
        }

        message = MessageUtil.updateTag(message, BODY_LENGTH_TAG, Integer.toString(getBodyLength(message)));
        message = MessageUtil.updateTag(message, CHECKSUM_TAG, getChecksum(message));

        outgoingMessages.put(msgSeqNumValue, message);

        return metadata;
    }

    @Override
    public void onOpen() {
        this.client = context.getChannel();
        this.settings = (FixHandlerSettings) context.getSettings();
        sendLogon();
    }

    public void sendHeartbeat() {

        StringBuilder heartbeat = new StringBuilder();
        setHeader(heartbeat, MSG_TYPE_HEARTBEAT);
        setChecksumAndBodyLength(heartbeat);

        if (enabled.get()) {
            client.send(Unpooled.wrappedBuffer(heartbeat.toString().getBytes(StandardCharsets.UTF_8)), Collections.emptyMap(), IChannel.SendMode.MANGLE);
        } else {
            sendLogon();
        }
        testRequestTimer = executorService.schedule(this::sendTestRequest, settings.getTestRequestDelay(), TimeUnit.SECONDS);
    }

    public void sendTestRequest() { //do private

        StringBuilder testRequest = new StringBuilder();
        setHeader(testRequest, MSG_TYPE_TEST_REQUEST);
        testRequest.append(TEST_REQ_ID).append(testReqID.incrementAndGet());
        setChecksumAndBodyLength(testRequest);
        if (enabled.get()) {
            client.send(Unpooled.wrappedBuffer(testRequest.toString().getBytes(StandardCharsets.UTF_8)), Collections.emptyMap(), IChannel.SendMode.MANGLE);
        } else {
            sendLogon();
        }
        reconnectRequestTimer = executorService.schedule(this::sendLogon, settings.getReconnectDelay(), TimeUnit.SECONDS);
    }


    public void sendLogon() {

        StringBuilder logon = new StringBuilder();

        setHeader(logon, MSG_TYPE_LOGON);
        logon.append(ENCRYPT_METHOD).append(settings.getEncryptMethod());
        logon.append(HEART_BT_INT).append(settings.getHeartBtInt());
        logon.append(DEFAULT_APPL_VER_ID).append(settings.getDefaultApplVerID());
        logon.append(USERNAME).append(settings.getUsername());
        logon.append(PASSWORD).append(settings.getPassword());
        setChecksumAndBodyLength(logon);

        client.send(Unpooled.wrappedBuffer(logon.toString().getBytes(StandardCharsets.UTF_8)), Collections.emptyMap(), IChannel.SendMode.MANGLE);
    }

    @Override
    public void onClose() {

        sendTestRequest();

        StringBuilder logout = new StringBuilder();
        setHeader(logout, MSG_TYPE_LOGOUT);
        setChecksumAndBodyLength(logout);

        if (enabled.get()) {
            client.send(Unpooled.wrappedBuffer(logout.toString().getBytes(StandardCharsets.UTF_8)), Collections.emptyMap(), IChannel.SendMode.MANGLE);
        }
        disconnectRequest = executorService.schedule(() -> enabled.set(false), settings.getDisconnectRequestDelay(), TimeUnit.SECONDS);
    }

    @Override
    public void close() {

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(3000, TimeUnit.MILLISECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
    }

    private void setHeader(StringBuilder stringBuilder, String msgType) {

        stringBuilder.append(BEGIN_STRING_TAG).append("=").append(settings.getBeginString());
        stringBuilder.append(MSG_TYPE).append(msgType);
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

        int checksumIdx = ByteBufUtil.indexOf(message, CHECKSUM) + 1;
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
        int bodyLengthIdx = ByteBufUtil.indexOf(message, BODY_LENGTH);
        int start = MessageUtil.findByte(message, bodyLengthIdx + 1, BYTE_SOH);
        int end = ByteBufUtil.indexOf(message, CHECKSUM);
        return end - start;
    }

    public Instant getTime() {
        return Instant.now();
    }

    public AtomicBoolean getEnabled() {
        return enabled;
    }

    public Log getOutgoingMessages() {
        return outgoingMessages;
    }
}
