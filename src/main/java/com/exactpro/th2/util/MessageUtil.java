package com.exactpro.th2.util;

import com.exactpro.th2.constants.Constants;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;

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

    public static ByteBuf updateTag(ByteBuf message, String tag, String value) {
        byte[] toInsert = value.getBytes(StandardCharsets.US_ASCII);
        byte[] result = new byte[message.capacity() + toInsert.length - getTagValue(message, tag).length()];

        int firstSoh = 1;

        if (tag.equals("8")) {
            firstSoh = 0;
        }

        int start = findTag(message, tag) + firstSoh + tag.length() + 1;
        int end = findByte(message, start, BYTE_SOH);

        message.readerIndex(0);
        message.readBytes(result, 0, start);
        System.arraycopy(toInsert, 0, result, start, toInsert.length);
        message.readerIndex(end);
        message.readBytes(result, start + toInsert.length, message.writerIndex() - message.readerIndex());
        return Unpooled.wrappedBuffer(result);
    }


    public static void putTag(ByteBuf message, String tag, String value) {
        byte[] toInsert;

        if (tag.equals(Constants.BEGIN_STRING_TAG)) {
            toInsert = (Constants.BEGIN_STRING_TAG + "=" + value + SOH).getBytes(StandardCharsets.US_ASCII);
            getSupplementedMessage(message, toInsert, 0);
            return;
        }

        if (tag.equals(Constants.BODY_LENGTH_TAG)) {
            toInsert = (Constants.BODY_LENGTH_TAG + "=" + value + SOH).getBytes(StandardCharsets.US_ASCII);
            int toIdx = findByte(message, 0, BYTE_SOH) + 1;
            getSupplementedMessage(message, toInsert, toIdx);
            return;
        }

        if (tag.equals(Constants.MSG_TYPE_TAG)) {
            toInsert = (Constants.MSG_TYPE_TAG + "=" + value + SOH).getBytes(StandardCharsets.US_ASCII);
            int toIdx = message.indexOf(findTag(message, 0, Constants.BODY_LENGTH_TAG) + 1, message.readableBytes(), BYTE_SOH) + 1;
            getSupplementedMessage(message, toInsert, toIdx);
            return;
        }

        if (tag.equals(Constants.MSG_SEQ_NUM_TAG)) {
            toInsert = (Constants.MSG_SEQ_NUM_TAG + "=" + value + SOH).getBytes(StandardCharsets.US_ASCII);
            int start = findTag(message, 0, Constants.MSG_TYPE_TAG)+1;
            int toIdx;
            if (start == 0){
                toIdx = message.indexOf(findTag(message, 0, Constants.BODY_LENGTH_TAG) + 1, message.readableBytes(), BYTE_SOH) + 1;
            }
            else{
                toIdx = message.indexOf(start, message.readableBytes(), BYTE_SOH) + 1;
            }
            getSupplementedMessage(message, toInsert, toIdx);
            return;
        }

        if (tag.equals(Constants.CHECKSUM_TAG)) {
            toInsert = (Constants.CHECKSUM_TAG + "=" + value + SOH).getBytes(StandardCharsets.US_ASCII);
            getSupplementedMessage(message, toInsert, message.readableBytes());
            return;
        }

        toInsert = (tag + "=" + value + SOH).getBytes(StandardCharsets.US_ASCII);
        int toIdx = findTag(message, 0, Constants.CHECKSUM_TAG) + 1;
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
}
