package com.exactpro.th2;

import com.exactpro.th2.constants.Constants;
import com.exactpro.th2.util.MessageUtil;
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


public class FixHandler implements AutoCloseable, IProtocolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(FixHandler.class);
    private static final String SOH = "\001";
    private static final byte BYTE_SOH = (byte) '\001';
    private static final String STRING_MSG_TYPE = "MsgType";
    private final Log outgoingMessages = new Log(10000);
    private final AtomicInteger msgSeqNum = new AtomicInteger(0);
    private final AtomicInteger serverMsgSeqNum = new AtomicInteger(0);
    private final AtomicInteger testReqID = new AtomicInteger(0);
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final ScheduledExecutorService executorService;
    private final IClient client;
    private Future<?> heartbeatTimer;
    private Future<?> testRequestTimer;
    private Future<?> reconnectRequestTimer;
    private Future<?> disconnectRequest;


    protected FixHandlerSettings settings;

    public FixHandler(IClient client, IProtocolHandlerSettings settings) {
        this.client = client;
        this.settings = (FixHandlerSettings) settings;

        executorService = Executors.newScheduledThreadPool(1);
    }

    public byte[] onData(ByteBuf buffer) {

        int offset = buffer.readerIndex();

        int beginStringIdx = MessageUtil.findTag(buffer, offset, BEGIN_STRING_TAG);
        if (beginStringIdx == -1) {
            if (offset == 0) {
                LOGGER.trace("Failed to parse data. BeginString not found in message: {}", buffer);//to string?
            }
            return null;
        }

        int nextBeginString = MessageUtil.findTag(buffer, beginStringIdx + 1, BEGIN_STRING_TAG);
        int checksum = MessageUtil.findTag(buffer, beginStringIdx, CHECKSUM_TAG);
        int endOfMessageIdx = checksum + 7; //checksum is always 3 digits // or we should search next soh?
        byte messageEnd;

        try {
            messageEnd = buffer.getByte(endOfMessageIdx);
        } catch (Exception e) {
            return null;
        }

        if (checksum == -1 || messageEnd != BYTE_SOH || (nextBeginString != -1 && nextBeginString < checksum)) {
            LOGGER.trace("Failed to parse message: {}. No Checksum or no tag separator at the end of the message with index {}", buffer, beginStringIdx);
            if (nextBeginString != -1) {
                buffer.readerIndex(nextBeginString);
                return onData(buffer);
            } else {
                buffer.readerIndex(offset);
                return null;
            }
        }

        buffer.readerIndex(endOfMessageIdx + 1);

        return MessageUtil.getSubSequence(buffer, beginStringIdx, endOfMessageIdx + 1);
    }

    @NotNull
    @Override
    public Map<String, String> onMessage(@NotNull ByteBuf message) {

        serverMsgSeqNum.incrementAndGet();

        String msgSeqNumValue = MessageUtil.getTagValue(message, MSG_SEQ_NUM_TAG);

        if (msgSeqNumValue == null) {
            LOGGER.trace("Invalid message. No MsgSeqNum in message: {}", message);//convert
            return Collections.emptyMap();
        }

        int receivedMsgSeqNum = Integer.parseInt(msgSeqNumValue);
        if (serverMsgSeqNum.get() != receivedMsgSeqNum) {
            sendResendRequest(serverMsgSeqNum.get(), receivedMsgSeqNum);
        }


        String msgType = MessageUtil.getTagValue(message, MSG_TYPE_TAG);

        if (msgType == null) {
            LOGGER.trace("Invalid message. No MsgType in message: {}", message);// convert
            return Collections.emptyMap();
        }

        if (sessionTypes.contains(msgType)) {

            switch (msgType) {
                case MSG_TYPE_HEARTBEAT:
                    checkHeartbeat(message);
                case MSG_TYPE_LOGON:
                    boolean connectionSuccessful = checkLogon(message);
                    enabled.set(connectionSuccessful);
                    if (connectionSuccessful) {
                        heartbeatTimer = executorService.scheduleWithFixedDelay(this::sendHeartbeat, settings.getHeartBtInt(), settings.getHeartBtInt(), TimeUnit.SECONDS);
                    }
                    break;
                case MSG_TYPE_LOGOUT: // should we process logout? e g extract logout reason?
                    if (!disconnectRequest.isCancelled() && disconnectRequest != null) {
                        disconnectRequest.cancel(false);
                    }
                    enabled.set(false);
                    break;
                case MSG_TYPE_RESEND_REQUEST:
                    handleResendRequest(message);
                    break;
                case MSG_TYPE_SEQUENCE_RESET: //gap fill
                    resetSequence(message);
                    break;
            }
        }

        if (testRequestTimer != null && !testRequestTimer.isCancelled()) {
            testRequestTimer.cancel(false);
        }

        Map<String, String> meta = new HashMap<>();
        meta.put(STRING_MSG_TYPE, msgType);

        return meta;
    }

    private void resetSequence(ByteBuf strMessage) {

        String gapFillFlagValue = MessageUtil.getTagValue(strMessage, GAP_FILL_FLAG_TAG);
        String seqNumValue = MessageUtil.getTagValue(strMessage, NEW_SEQ_NO_TAG);

        if (seqNumValue != null && (gapFillFlagValue == null || gapFillFlagValue.equals("N"))) {
            serverMsgSeqNum.set(Integer.parseInt(seqNumValue));
        } else { //todo +gapFillTag
            LOGGER.trace("Failed to reset servers MsgSeqNum. No such tag in message: {}", strMessage);
        }
    }

    public void sendResendRequest(int beginSeqNo, int endSeqNo) { //do private

        StringBuilder resendRequest = new StringBuilder();
        setHeader(resendRequest, MSG_TYPE_RESEND_REQUEST);
        resendRequest.append(Constants.BEGIN_SEQ_NO).append(beginSeqNo).append(SOH);
        resendRequest.append(Constants.END_SEQ_NO).append(endSeqNo).append(SOH);
        setChecksumAndBodyLength(resendRequest);

        if (enabled.get()) {
            client.send(Unpooled.wrappedBuffer(resendRequest.toString().getBytes(StandardCharsets.UTF_8)));
        } else {
            sendLogon();
        }
    }


    void sendResendRequest(int beginSeqNo) { //do private

        StringBuilder resendRequest = new StringBuilder();
        setHeader(resendRequest, MSG_TYPE_RESEND_REQUEST);
        resendRequest.append(Constants.BEGIN_SEQ_NO).append(beginSeqNo).append(SOH);
        resendRequest.append(Constants.END_SEQ_NO).append(0).append(SOH);
        setChecksumAndBodyLength(resendRequest);

        if (enabled.get()) {
            client.send(Unpooled.wrappedBuffer(resendRequest.toString().getBytes(StandardCharsets.UTF_8)));
        } else {
            sendLogon();
        }
    }

    private void handleResendRequest(ByteBuf message) {

        if (!disconnectRequest.isCancelled() && disconnectRequest != null) {
            disconnectRequest.cancel(false);
        }

        String strBeginSeqNo = MessageUtil.getTagValue(message, BEGIN_SEQ_NO_TAG);
        String strEndSeqNo = MessageUtil.getTagValue(message, END_SEQ_NO_TAG);

        if (strBeginSeqNo != null && strEndSeqNo != null) {
            int beginSeqNo = Integer.parseInt(strBeginSeqNo);
            int endSeqNo = Integer.parseInt(strBeginSeqNo);

            try {

                for (int i = beginSeqNo; i <= endSeqNo; i++) {
                    client.send(outgoingMessages.get(i));
                }
            } catch (Exception e) {
                sendSequenceReset();
            }
        }
    }

    private void sendSequenceReset() {
        StringBuilder sequenceReset = new StringBuilder();
        setHeader(sequenceReset, MSG_TYPE_SEQUENCE_RESET);
        sequenceReset.append(Constants.NEW_SEQ_NO).append(msgSeqNum.get() + 1).append(SOH);
        setChecksumAndBodyLength(sequenceReset);

        if (enabled.get()) {
            client.send(Unpooled.wrappedBuffer(sequenceReset.toString().getBytes(StandardCharsets.UTF_8)));
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

        String sessionStatusField = MessageUtil.getTagValue(message, SESSION_STATUS_TAG); //check another options todo
        if (sessionStatusField != null && sessionStatusField.equals("0")) {
            String msgSeqNumValue = MessageUtil.getTagValue(message, MSG_SEQ_NUM_TAG);
            if (msgSeqNumValue == null) return false;
            serverMsgSeqNum.set(Integer.parseInt(msgSeqNumValue));
            return true;
        }
        return false;
    }

    public @NotNull Map<String, String> prepareMessage(@NotNull ByteBuf message) {
//         should fill messages without required header fields
//        add msgSeqNum here

//        msgSeqNum.incrementAndGet();

        String msgType = MessageUtil.getTagValue(message, MSG_TYPE_TAG);

        if (heartbeatTimer != null) {
            heartbeatTimer.cancel(false);
            heartbeatTimer = executorService.scheduleWithFixedDelay(this::sendHeartbeat, settings.getHeartBtInt(), settings.getHeartBtInt(), TimeUnit.SECONDS);
        }

        String clientMsgSeqNum = MessageUtil.getTagValue(message, MSG_SEQ_NUM_TAG);
        if (clientMsgSeqNum != null) {
            outgoingMessages.put(Integer.parseInt(clientMsgSeqNum), message);
        }

        Map<String, String> metadata = new HashMap<>();
        metadata.put(STRING_MSG_TYPE, msgType);

        return metadata;
    }

    @Override
    public void onConnect() {
        sendLogon();
    }

    public void sendHeartbeat() {

        StringBuilder heartbeat = new StringBuilder();
        setHeader(heartbeat, MSG_TYPE_HEARTBEAT);
        setChecksumAndBodyLength(heartbeat);

        if (enabled.get()) {
            client.send(Unpooled.wrappedBuffer(heartbeat.toString().getBytes(StandardCharsets.UTF_8)));
        } else {
            sendLogon();
        }
        testRequestTimer = executorService.schedule(this::sendTestRequest, settings.getTestRequestDelay(), TimeUnit.SECONDS); // which time?
    }

    public void sendTestRequest() { //do private

        StringBuilder testRequest = new StringBuilder();
        setHeader(testRequest, MSG_TYPE_TEST_REQUEST);
        testRequest.append(Constants.TEST_REQ_ID).append(testReqID.incrementAndGet()).append(SOH);
        setChecksumAndBodyLength(testRequest);
        if (enabled.get()) {
            client.send(Unpooled.wrappedBuffer(testRequest.toString().getBytes(StandardCharsets.UTF_8)));
        } else {
            sendLogon();
        }
        reconnectRequestTimer = executorService.schedule(this::sendLogon, settings.getReconnectDelay(), TimeUnit.SECONDS);
    }


    public void sendLogon() {

        StringBuilder logon = new StringBuilder();// add defaultApplVerID for fix5+

        setHeader(logon, MSG_TYPE_LOGON);
        logon.append(Constants.ENCRYPT_METHOD).append(settings.getEncryptMethod()).append(SOH);
        logon.append(Constants.HEART_BT_INT).append(settings.getHeartBtInt()).append(SOH);
        logon.append(Constants.USERNAME).append(settings.getUsername()).append(SOH);
        logon.append(Constants.PASSWORD).append(settings.getPassword()).append(SOH);
        setChecksumAndBodyLength(logon);

        client.send(Unpooled.wrappedBuffer(logon.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public void onDisconnect() {

        sendTestRequest();

        StringBuilder logout = new StringBuilder();
        setHeader(logout, MSG_TYPE_LOGOUT);
        setChecksumAndBodyLength(logout);

        if (enabled.get()) {
            client.send(Unpooled.wrappedBuffer(logout.toString().getBytes(StandardCharsets.UTF_8)));
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

        stringBuilder.append(Constants.BEGIN_STRING).append(settings.getBeginString()).append(SOH);
        stringBuilder.append(Constants.MSG_TYPE).append(msgType).append(SOH);
        stringBuilder.append(Constants.SENDER_COMP_ID).append(settings.getSenderCompID()).append(SOH);
        stringBuilder.append(Constants.TARGET_COMP_ID).append(settings.getTargetCompID()).append(SOH);
        stringBuilder.append(Constants.MSG_SEQ_NUM).append(msgSeqNum.incrementAndGet()).append(SOH); //todo locate in prepare msg
        stringBuilder.append(Constants.SENDING_TIME).append(getTime()).append(SOH);
    }

    private void setChecksumAndBodyLength(StringBuilder stringBuilder) {
        stringBuilder.append(Constants.CHECKSUM).append("000").append(SOH);
        stringBuilder.insert(stringBuilder.indexOf(SOH + Constants.MSG_TYPE) + 1,
                Constants.BODY_LENGTH + getBodyLength(stringBuilder) + SOH);
        stringBuilder.replace(stringBuilder.lastIndexOf("000" + SOH), stringBuilder.lastIndexOf(SOH), getChecksum(stringBuilder));
    }

    public String getChecksum(StringBuilder message) { //do private

        String substring = message.substring(0, message.indexOf(SOH + Constants.CHECKSUM) + 1);
        byte[] bytes = substring.getBytes(StandardCharsets.US_ASCII);
        int total = 0;
        for (byte item : bytes) {
            total += item;
        }
        int checksum = total % 256;
        return String.format("%03d", checksum);
    }

    public int getBodyLength(StringBuilder message) { //do private

        int start = message.indexOf(SOH + Constants.MSG_TYPE);
        int end = message.indexOf(SOH + Constants.CHECKSUM);
        return end - start;
    }

    public Instant getTime() {
        return Instant.now();
    }

    public AtomicBoolean getEnabled() {
        return enabled;
    }
}
