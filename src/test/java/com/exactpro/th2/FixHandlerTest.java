package com.exactpro.th2;

import com.exactpro.th2.common.grpc.MessageID;
import com.exactpro.th2.conn.dirty.tcp.core.api.IChannel;
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerSettings;
import com.exactpro.th2.constants.Constants;
import com.exactpro.th2.util.MessageUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;


class FixHandlerTest {

    private static ByteBuf buffer;
    private static ByteBuf oneMessageBuffer;
    private static ByteBuf brokenBuffer;
    private final Client client = new Client();
    private final FixHandler fixHandler = client.getFixHandler();

    @BeforeAll
    static void init() {

        oneMessageBuffer = Unpooled.wrappedBuffer("8=FIXT.1.1\0019=13\00135=AE\001552=1\00110=169\001".getBytes(StandardCharsets.US_ASCII));
        buffer = Unpooled.wrappedBuffer(("8=FIXT.1.1\0019=13\00135=AE\001552=1\00110=169\0018=FIXT.1.1\0019=13\00135=NN" +
                "\001552=2\00110=100\0018=FIXT.1.1\0019=13\00135=NN\001552=2\00110=100\001").getBytes(StandardCharsets.US_ASCII));
        brokenBuffer = Unpooled.wrappedBuffer("A8=FIXT.1.1\0019=13\00135=AE\001552=1\00110=16913138=FIXT.1.1\0019=13\00135=AE\001552=1\00110=169\001".getBytes(StandardCharsets.US_ASCII));
    }

    @BeforeEach
    void beforeEach() {
        fixHandler.getEnabled().set(true);
    }

    @Test
    void onDataBrokenMessageTest() {
        ByteBuf result0 = fixHandler.onReceive(brokenBuffer);
        ByteBuf result1 = fixHandler.onReceive(brokenBuffer);
        ByteBuf result2 = fixHandler.onReceive(brokenBuffer);
        String expected0 = "A";
        String expected1 = "8=FIXT.1.19=1335=AE552=110=1691313";
        String expected2 = "8=FIXT.1.1\0019=13\00135=AE\001552=1\00110=169\001";

        assertNotNull(result0);
        assertEquals(expected0, new String(result0.array()));
        assertNotNull(result1);
        assertEquals(expected1, new String(result1.array()));
        assertNotNull(result2);
        assertEquals(expected2, new String(result2.array()));
    }

    @Test
    void onReceiveCorrectMessagesTest() {

        ByteBuf result1 = fixHandler.onReceive(buffer);
        ByteBuf result2 = fixHandler.onReceive(buffer);
        ByteBuf result3 = fixHandler.onReceive(buffer);

        String expected1 = "8=FIXT.1.1\0019=13\00135=AE\001552=1\00110=169\001";
        String expected2 = "8=FIXT.1.1\0019=13\00135=NN\001552=2\00110=100\001";
        String expected3 = "8=FIXT.1.1\0019=13\00135=NN\001552=2\00110=100\001";

        assertNotNull(result1);
        assertEquals(expected1, new String(result1.array()));

        assertNotNull(result2);
        assertEquals(expected2, new String(result2.array()));

        assertNotNull(result3);
        assertEquals(expected3, new String(result3.array()));
    }


    @Test
    void sendResendRequestTest() {
        String expectedLogon = "8=FIXT.1.1\u00019=90\u000135=A\u000149=client\u000156=server\u0001" +         // #1 sent logon
                "52=2014-12-22T10:15:30Z\u000198=0\u0001108=30\u00011137=9\001553=username\u0001554=pass\u000110=163\u0001";
        String expectedHeartbeat = "8=FIXT.1.1\u00019=49\u000135=0\u000149=client\u000156=server\u0001" +     // #2 sent heartbeat
                "52=2014-12-22T10:15:30Z\u000110=108\u0001";
        String expectedResendRequest = "8=FIXT.1.1\u00019=58\u000135=2\u000149=client\u000156=server" +       // #3 sent resendRequest
                "\u000152=2014-12-22T10:15:30Z\u00017=1\u000116=0\u000110=233\u0001";

        fixHandler.onOpen();
        fixHandler.sendHeartbeat();
        fixHandler.sendResendRequest(1);
        fixHandler.close();
        assertEquals(expectedLogon, new String(client.getQueue().get(0).array()));
        assertEquals(expectedHeartbeat, new String(client.getQueue().get(1).array()));
        assertEquals(expectedResendRequest, new String(client.getQueue().get(2).array()));
    }

    @Test
    void onConnectionTest() {
        fixHandler.onOpen();
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        assertEquals("8=FIXT.1.1\u00019=90\u000135=A\u000149=client\u000156=server\u000152=2014-12-22T10:15:30Z\u000198=0\u0001108=30\u00011137=9\001553=username\u0001554=pass\u000110=163\u0001",
                new String(client.getQueue().get(0).array()));
    }

    @Test
    void prepareMessageTest() {
        ByteBuf bufferForPrepareMessage = Unpooled.wrappedBuffer("8=FIXT.1.1\0019=13\00135=A\u000134=1\001552=1\00110=169\001".getBytes(StandardCharsets.US_ASCII));
        Map<String, String> expected = new HashMap<>();
        expected.put("MsgType", "A");
        Map<String, String> actual = fixHandler.onOutgoing(bufferForPrepareMessage, Collections.emptyMap());
        assertEquals(expected, actual);
    }

    @Test
    void getChecksumTest() {
        StringBuilder messageForChecksum = new StringBuilder("UUU\00110=169\001"); // U == 85 in ASCII str == 256
        String actual = fixHandler.getChecksum(messageForChecksum);
        String expectedString = "000";
        assertEquals(expectedString, actual);
    }

    @Test
    void getByteBufChecksumTest() {
        ByteBuf messageForChecksum = Unpooled.wrappedBuffer("UUU\00110=169\001".getBytes(StandardCharsets.US_ASCII)); // U == 85 in ASCII str == 256
        String actual = fixHandler.getChecksum(messageForChecksum);
        String expectedString = "000";
        assertEquals(expectedString, actual);
    }

    @Test
    void getBodyLengthTest() {
        StringBuilder messageForBodyLength = new StringBuilder("8=FIXT.1.1\0019=13\00135=AE\00110=169\001");
        int expected = 6;
        int actual = fixHandler.getBodyLength(messageForBodyLength);
        assertEquals(expected, actual);
    }

    @Test
    void getByteByfBodyLengthTest() {
        ByteBuf byteBuf = Unpooled.wrappedBuffer("8=FIX.2.2\0019=19\00135=AE\001552=1\00110=053\001".getBytes(StandardCharsets.US_ASCII));
        int expected = 12;
        int actual = fixHandler.getBodyLength(byteBuf);
        assertEquals(expected, actual);
    }

    @Test
    void sendTestRequestTest() {
        String expected = "8=FIXT.1.1\u00019=55\u000135=1\u000149=client\u000156=server\u000152=2014-12-22T10:15:30Z\u0001112=1\u000110=109\u0001";
        fixHandler.sendTestRequest();
        fixHandler.close();
        assertEquals(expected, new String(client.getQueue().get(0).array()));
    }

    @Test
    void handleResendRequestTest() {
        //later
    }

    @Test
    void logTest() {
        Log log = new Log(3);
        log.put(1, oneMessageBuffer);
        log.put(2, oneMessageBuffer);
        log.put(3, oneMessageBuffer);
        log.put(4, oneMessageBuffer);

        assertArrayEquals(new Integer[]{2, 3, 4}, log.getLog().keySet().toArray());
    }

    @Test
    void findBeginStringTest() {
        int expected1 = 0;
        int expected2 = 8;
        ByteBuf buf = Unpooled.wrappedBuffer("812345678=F".getBytes(StandardCharsets.UTF_8));

        int actual = MessageUtil.findByte(buf, 0, (byte) 56);
        int actual2 = MessageUtil.findByte(buf, 1, (byte) 56);

        assertEquals(expected1, actual);
        assertEquals(expected2, actual2);
    }

    @Test
    void getTagValueTest() {

        String expected = "AE";
        String expected2 = "FIXT.1.1";

        String actual = MessageUtil.getTagValue(oneMessageBuffer, "35");
        String actual2 = MessageUtil.getTagValue(oneMessageBuffer, "8");

        assertEquals(expected, actual);
        assertEquals(expected2, actual2);
    }


    @Test
    void putTagTest() {

        ByteBuf buf = Unpooled.wrappedBuffer("552=1\001".getBytes(StandardCharsets.UTF_8));
        String expected = "8=FIX.2.2\001552=1\001";
        String expected2 = "8=FIX.2.2\0019=19\001552=1\001";
        String expected3 = "8=FIX.2.2\0019=19\001552=1\00110=009\001";

        buf = MessageUtil.putTag(buf, Constants.BEGIN_STRING_TAG, "FIX.2.2");
        assertNotNull(buf);
        assertEquals(expected, new String(buf.array()));

        buf = MessageUtil.putTag(buf, Constants.BODY_LENGTH_TAG, "19");
        assertNotNull(buf);
        assertEquals(expected2, new String(buf.array()));

        buf = MessageUtil.putTag(buf, Constants.CHECKSUM_TAG, fixHandler.getChecksum(buf));
        assertNotNull(buf);
        assertEquals(expected3, new String(buf.array()));
    }

    @Test
    void updateTagTest() {
        ByteBuf buf = Unpooled.wrappedBuffer("8=FIX.2.2\00135=AE\001".getBytes(StandardCharsets.UTF_8));
        String expected = "8=FIX.2.2\00135=AEE\001";
        String expected2 = "8=FIX.2.3\00135=AEE\001";

        buf = MessageUtil.updateTag(buf, Constants.MSG_TYPE_TAG, "AEE");
        assertEquals(expected, new String(buf.array()));

        buf = MessageUtil.updateTag(buf, Constants.BEGIN_STRING_TAG, "FIX.2.3");
        assertEquals(expected2, new String(buf.array()));
    }

}

class Client implements IChannel {
    private final FixHandlerSettings fixHandlerSettings;
    private final MyFixHandler fixHandler;
    private final List<ByteBuf> queue = new ArrayList<>();

    Client() {
        this.fixHandlerSettings = new FixHandlerSettings();
        this.fixHandler = new MyFixHandler(this, fixHandlerSettings);
    }

    @Override
    public void open() {

    }

    @Override
    public void open(InetSocketAddress address, boolean secure) {

    }

    @NotNull
    @Override
    public MessageID send(@NotNull ByteBuf byteBuf, @NotNull Map<String, String> map, @NotNull IChannel.SendMode sendMode) {
        queue.add(byteBuf);
        return null;
    }

    @Override
    public boolean isOpen() {
        return false;
    }

    @Override
    public void close() {

    }

    public FixHandlerSettings getFixHandlerSettings() {
        return fixHandlerSettings;
    }

    public MyFixHandler getFixHandler() {
        return fixHandler;
    }

    public List<ByteBuf> getQueue() {
        return queue;
    }

    @NotNull
    @Override
    public InetSocketAddress getAddress() {
        return null;
    }

    @Override
    public boolean isSecure() {
        return false;
    }
}

class MyFixHandler extends FixHandler {

    public MyFixHandler(IChannel client, IProtocolHandlerSettings settings) {
        super(client, settings);
    }

    @Override
    public Instant getTime() {
        String instantExpected = "2014-12-22T10:15:30Z";
        Clock clock = Clock.fixed(Instant.parse(instantExpected), ZoneId.of("UTC"));
        return Instant.now(clock);
    }
}
