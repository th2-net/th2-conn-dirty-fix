package com.exactpro.th2.util;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

public class MessageUtil {

    private static final byte BYTE_EIGHT = (byte) 56;
    private static final byte BYTE_SOH = (byte) 1;
    private static final byte BYTE_EQUAL = (byte) 61;

    public static String getTagValue(ByteBuf message, String tag) {

        int skipSoh = 1;
        if (tag.equals("8")) {
            skipSoh = 0;
        }

        int start = findTag(message, 0, tag);
        int end = findByte(message, start + 1, BYTE_SOH);

        if (start == -1 || end == -1) {
            return null;
        }
        return new String(getSubSequence(message, start + tag.length() + skipSoh + 1, end));
    }

    public static int findTag(ByteBuf message, int offset, String tag) {

        byte[] byteTag = tag.getBytes(StandardCharsets.US_ASCII);
        int start;
        int skipSymbols = 1;
        byte tagSeparator = BYTE_SOH;

        if (tag.equals("8")) {
            tagSeparator = BYTE_EIGHT;
            skipSymbols = 0;
        }

        start = findByte(message, offset, tagSeparator);
        boolean interrupted;

        while (start != -1) {
            interrupted = false;
            int i = 0;
            while (i < byteTag.length) {
                if (message.getByte(start + skipSymbols + i) != byteTag[i]) {
                    start = findByte(message, start + 1, tagSeparator);
                    interrupted = true;
                    break;
                }
                i++;
            }

            if (interrupted) {
                continue;
            }

            if (message.getByte(start + skipSymbols + i) == BYTE_EQUAL) {
                return start;
            } else {
                start = findByte(message, start + 1, tagSeparator);
            }
        }

        return -1;
    }

    public static byte[] getSubSequence(ByteBuf message, int fromIdx, int toIdx) {
        byte[] result = new byte[toIdx - fromIdx];
        int j = 0;

        for (int i = fromIdx; i < toIdx; i++) {
            result[j] = message.getByte(i);
            j++;
        }
        return result;
    }

    public static int findByte(ByteBuf buffer, int fromIndex, int toIndex, byte value) {
        return buffer.indexOf(fromIndex, toIndex, value);
    }

    public static int findByte(ByteBuf buffer, int fromIndex, byte value) {
        return buffer.indexOf(fromIndex, buffer.writerIndex(), value);
    }
}
