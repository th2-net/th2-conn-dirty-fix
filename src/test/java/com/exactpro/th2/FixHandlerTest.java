package com.exactpro.th2;

import com.exactpro.th2.util.MessageUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;


class FixHandlerTest {

    private static ByteBuf buffer;
    private static ByteBuf oneMessageBuffer;
    private static ByteBuf brokenBuffer;
    private final Client client = new Client();
    private final FixHandler fixHandler = client.getFixHandler();

    @BeforeAll
    static void init() {

        oneMessageBuffer = Unpooled.wrappedBuffer("8=FIXT.1.1\0019=13\00135=AE\001552=1\00110=169\001".getBytes(StandardCharsets.US_ASCII));
        buffer = Unpooled.wrappedBuffer(("0008=FIXT.1.1\0019=13\00135=AE\001552=1\00110=169\0018=FIXT.1.1\0019=13\00135=NN" +
                "\001552=2\00110=100\0018=FIXT.1.1\0019=13\00135=NN\001552=2\00110=100\001").getBytes(StandardCharsets.US_ASCII));
        brokenBuffer = Unpooled.wrappedBuffer("8=FIXT.1.1\0019=13\00135=AE\001552=1\00110=16913138=FIXT.1.1\0019=13\00135=AE\001552=1\00110=169\001".getBytes(StandardCharsets.US_ASCII));
    }

    @BeforeEach
    void beforeEach() {
        fixHandler.getEnabled().set(true);
    }

    @Test
    void onDataBrokenMessageTest() {
        byte[] result1 = fixHandler.onData(brokenBuffer);
        byte[] result2 = fixHandler.onData(brokenBuffer);
        int expectedOffset = 73;
        String expected1 = "8=FIXT.1.1\0019=13\00135=AE\001552=1\00110=169\001";

        assertEquals(expectedOffset, brokenBuffer.readerIndex());
        assertNotNull(result1);
        assertEquals(expected1, new String(result1));
        assertNull(result2);
    }

    @Test
    void onDataCorrectMessagesTest() {

        byte[] result1 = fixHandler.onData(buffer);
        byte[] result2 = fixHandler.onData(buffer);
        byte[] result3 = fixHandler.onData(buffer);

        String expected1 = "8=FIXT.1.1\0019=13\00135=AE\001552=1\00110=169\001";
        String expected2 = "8=FIXT.1.1\0019=13\00135=NN\001552=2\00110=100\001";
        String expected3 = "8=FIXT.1.1\0019=13\00135=NN\001552=2\00110=100\001";

        assertNotNull(result1);
        assertEquals(expected1, new String(result1));

        assertNotNull(result2);
        assertEquals(expected2, new String(result2));

        assertNotNull(result3);
        assertEquals(expected3, new String(result3));
    }


    @Test
    void sendResendRequestTest() {
        String expectedLogon = "8=FIXT.1.1\u00019=88\u000135=A\u000149=client\u000156=server\u000134=1\u0001" +         // #1 sent logon
                "52=2014-12-22T10:15:30Z\u000198=0\u0001108=30\u0001553=username\u0001554=pass\u000110=061\u0001";
        String expectedHeartbeat = "8=FIXT.1.1\u00019=54\u000135=0\u000149=client\u000156=server\u000134=2\u0001" +     // #2 sent heartbeat
                "52=2014-12-22T10:15:30Z\u000110=063\u0001";
        String expectedResendRequest = "8=FIXT.1.1\u00019=63\u000135=2\u000149=client\u000156=server\u000134=3" +       // #3 sent resendRequest
                "\u000152=2014-12-22T10:15:30Z\u00017=1\u000116=1\u000110=190\u0001";

        fixHandler.onConnect();
        fixHandler.sendHeartbeat();
        fixHandler.sendResendRequest(0);
        fixHandler.close();
        assertEquals(expectedLogon, new String(client.getQueue().get(0).array()));
        assertEquals(expectedHeartbeat, new String(client.getQueue().get(1).array()));

    }

    @Test
    void onConnectionTest() {
        fixHandler.onConnect();
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        assertEquals("8=FIXT.1.1\u00019=88\u000135=A\u000149=client\u000156=server\u000134=1\u000152=2014-12-22T10:15:30Z\u000198=0\u0001108=30\u0001553=username\u0001554=pass\u000110=061\u0001",
                new String(client.getQueue().get(0).array()));
    }

    @Test
    void prepareMessageTest() {
        ByteBuf bufferForPrepareMessage = Unpooled.wrappedBuffer("8=FIXT.1.1\0019=13\00135=A\u000134=1\001552=1\00110=169\001".getBytes(StandardCharsets.US_ASCII));
        Map<String, String> expected = new HashMap<>();
        expected.put("MsgType", "A");
        Map<String, String> actual = fixHandler.prepareMessage(bufferForPrepareMessage);
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
    void getBodyLengthTest() {
        StringBuilder messageForBodyLength = new StringBuilder("8=FIXT.1.1\0019=13\00135=AE\00110=169\001");
        int expected = 6;
        int actual = fixHandler.getBodyLength(messageForBodyLength);
        assertEquals(expected, actual);
    }

    @Test
    void sendTestRequestTest() {
        String expected = "8=FIXT.1.1\u00019=60\u000135=1\u000149=client\u000156=server\u000134=1\u000152=2014-12-22T10:15:30Z\u0001112=1\u000110=063\u0001";
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
}


class Client implements IClient {
    private final FixHandlerSettings fixHandlerSettings;
    private final MyFixHandler fixHandler;
    private final List<ByteBuf> queue = new ArrayList<>();

    Client() {
        this.fixHandlerSettings = new FixHandlerSettings();
        this.fixHandler = new MyFixHandler(this, fixHandlerSettings);
    }

    @Override
    public boolean isConnected() {
        return false;
    }

    @Override
    public void connect() {

    }

    @Override
    public void send(@NotNull ByteBuf message) {

        queue.add(message);
        fixHandler.prepareMessage(message);
    }

    @Override
    public void disconnect() {

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
}


class MyFixHandler extends FixHandler {

    public MyFixHandler(IClient client, IProtocolHandlerSettings settings) {
        super(client, settings);
    }

    @Override
    public Instant getTime() {
        String instantExpected = "2014-12-22T10:15:30Z";
        Clock clock = Clock.fixed(Instant.parse(instantExpected), ZoneId.of("UTC"));
        return Instant.now(clock);
    }
}