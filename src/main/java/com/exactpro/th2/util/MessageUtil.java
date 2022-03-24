package com.exactpro.th2.util;

import com.exactpro.th2.constants.Constants;
import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import static com.exactpro.th2.constants.Constants.*;

public class MessageUtil {

    private static final String SOH = "\001";

    private static final byte BYTE_EIGHT = 56;
    private static final byte BYTE_SOH = 1;
    private static final byte BYTE_EQUAL = 61;

    public static String getTagValue(ByteBuf message, String tag) {

        int skipEqualSign = 1;
        int skipSoh = 1;

        if (tag.equals("8")) {
            skipSoh = 0;
        }

        int start = findTag(message, 0, tag);
        int end = findByte(message, start + 1, BYTE_SOH);

        if (start == -1 || end == -1) {
            return null;
        }
        ByteBuf buf = message.retainedSlice(start + tag.length() + skipSoh + skipEqualSign, end - start - skipSoh - skipEqualSign - tag.length());

        byte[] result = new byte[buf.readableBytes()];
        buf.getBytes(0, result);
        return new String(result);
    }

    public static int findTag(ByteBuf message, String tag) {
        return findTag(message, 0, tag);
    }

    public static int findTag(ByteBuf message, int offset, String tag) {

        byte[] byteTag = tag.getBytes(StandardCharsets.US_ASCII);
        int start;
        int firstSoh = 1;
        byte delim = BYTE_SOH;

        if (tag.equals("8")) {
            delim = BYTE_EIGHT;
            firstSoh = 0;
        }

        start = findByte(message, offset, delim);
        boolean interrupted;

        while (start != -1) {
            interrupted = false;
            int i = 0;
            while (i < byteTag.length) {
                if (message.getByte(start + firstSoh + i) != byteTag[i]) {
                    start = findByte(message, start + 1, delim);
                    if (start + 1 == message.writerIndex()) {
                        return -1;
                    }
                    interrupted = true;
                    break;
                }
                i++;
            }

            if (interrupted) {
                continue;
            }

            if (message.getByte(start + firstSoh + i) == BYTE_EQUAL) {
                return start;
            } else {
                start = findByte(message, start + 1, delim);
            }
        }

        return -1;
    }

    public static int findByte(ByteBuf buffer, int fromIndex, int toIndex, byte value) {
        return buffer.indexOf(fromIndex, toIndex, value);
    }

    public static int findByte(ByteBuf buffer, int fromIndex, byte value) {
        return buffer.indexOf(fromIndex, buffer.writerIndex(), value);
    }

    public static void updateTag(ByteBuf message, String tag, String value) {
        byte[] toInsert = value.getBytes(StandardCharsets.US_ASCII);

        int firstSoh = 1;

        if (tag.equals("8")) {
            firstSoh = 0;
        }

        int start = findTag(message, tag) + 1;
        if (start > 0) {
            start += firstSoh + tag.length();
            int end = findByte(message, start , BYTE_SOH);
            if (end > -1) {
                ByteBuf copyMessage = message.copy(end, message.readableBytes() - end);
                message.writerIndex(start);
                message.writeBytes(toInsert);
                message.writeBytes(copyMessage);
            }
        }
    }


    public static void putTag(ByteBuf message, String tag, String value) {
        byte[] toInsert;

        if (tag.equals(BEGIN_STRING_TAG.toString())) {
            toInsert = (BEGIN_STRING_TAG + "=" + value + SOH).getBytes(StandardCharsets.US_ASCII);
            getSupplementedMessage(message, toInsert, 0);
            return;
        }

        if (tag.equals(BODY_LENGTH_TAG.toString())) {
            toInsert = (BODY_LENGTH_TAG + "=" + value + SOH).getBytes(StandardCharsets.US_ASCII);
            int toIdx = findByte(message, 0, BYTE_SOH) + 1;
            getSupplementedMessage(message, toInsert, toIdx);
            return;
        }

        if (tag.equals(MSG_TYPE_TAG.toString())) {
            toInsert = (MSG_TYPE_TAG + "=" + value + SOH).getBytes(StandardCharsets.US_ASCII);
            int toIdx = message.indexOf(findTag(message, 0, BODY_LENGTH_TAG.toString()) + 1, message.readableBytes(), BYTE_SOH) + 1;
            getSupplementedMessage(message, toInsert, toIdx);
            return;
        }

        if (tag.equals(MSG_SEQ_NUM_TAG.toString())) {
            toInsert = (MSG_SEQ_NUM_TAG + "=" + value + SOH).getBytes(StandardCharsets.US_ASCII);
            int start = findTag(message, 0, MSG_TYPE_TAG.toString())+1;
            int toIdx;
            if (start == 0){
                toIdx = message.indexOf(findTag(message, 0, BODY_LENGTH_TAG.toString()) + 1, message.readableBytes(), BYTE_SOH) + 1;
            }
            else{
                toIdx = message.indexOf(start, message.readableBytes(), BYTE_SOH) + 1;
            }
            getSupplementedMessage(message, toInsert, toIdx);
            return;
        }

        if (tag.equals(SENDER_COMP_ID_TAG.toString())) {
            putAddTag(message, value, SENDER_COMP_ID_TAG.toString(), MSG_SEQ_NUM_TAG);
            return;
        }

        if (tag.equals(TARGET_COMP_ID_TAG.toString())) {
            putAddTag(message, value, TARGET_COMP_ID_TAG.toString(), SENDER_COMP_ID_TAG);
            return;
        }

        if (tag.equals(SENDING_TIME_TAG.toString())) {
            putAddTag(message, value, SENDING_TIME_TAG.toString(), TARGET_COMP_ID_TAG);
            return;
        }

        if (tag.equals(CHECKSUM_TAG.toString())) {
            toInsert = (CHECKSUM_TAG + "=" + value + SOH).getBytes(StandardCharsets.US_ASCII);
            getSupplementedMessage(message, toInsert, message.readableBytes());
            return;
        }

        toInsert = (tag + "=" + value + SOH).getBytes(StandardCharsets.US_ASCII);
        int toIdx = findTag(message, 0, CHECKSUM_TAG.toString()) + 1;

        getSupplementedMessage(message, toInsert, toIdx);
    }

    private static void putAddTag(ByteBuf message, String value, String tag, Integer previousTag){
        byte[] toInsert = (tag + "=" + value + SOH).getBytes(StandardCharsets.US_ASCII);
        int start = findTag(message, 0, previousTag.toString()) + 1;
        int toIdx = message.indexOf(start, message.readableBytes(), BYTE_SOH) + 1;

        getSupplementedMessage(message, toInsert, toIdx);
    }

    private static void getSupplementedMessage(ByteBuf message, byte[] toInsert, int toIdx) {
        message.capacity(message.readableBytes() + toInsert.length);
        ByteBuf copyMessage = message.copy(toIdx, message.readableBytes()-toIdx);
        message.writerIndex(toIdx);
        message.writeBytes(toInsert);
        message.writeBytes(copyMessage);
        message.readerIndex(0);
    }

    public static void moveTag(ByteBuf message, int fromIdx, String tag, String value){
        int start = MessageUtil.findByte(message, fromIdx, BYTE_SOH)+1;
        int end = message.readableBytes()-start;
        message.writerIndex(fromIdx);
        message.writeBytes(message.copy(start, end));
        MessageUtil.putTag(message, tag, value);
    }
}
