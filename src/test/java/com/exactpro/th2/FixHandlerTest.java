package com.exactpro.th2;

import com.exactpro.th2.common.grpc.MessageID;
import com.exactpro.th2.conn.dirty.tcp.core.api.IChannel;
import com.exactpro.th2.conn.dirty.tcp.core.api.IContext;
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerSettings;
import com.exactpro.th2.conn.dirty.tcp.core.api.impl.Channel.Security;
import com.exactpro.th2.util.MessageUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.exactpro.th2.constants.Constants.BEGIN_STRING_TAG;
import static com.exactpro.th2.constants.Constants.BODY_LENGTH_TAG;
import static com.exactpro.th2.constants.Constants.CHECKSUM_TAG;
import static com.exactpro.th2.constants.Constants.DEFAULT_APPL_VER_ID_TAG;
import static com.exactpro.th2.constants.Constants.MSG_TYPE_TAG;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;


class FixHandlerTest {

    private static final Client client = new Client();
    private static ByteBuf buffer;
    private static ByteBuf oneMessageBuffer;
    private static ByteBuf brokenBuffer;
    private static FixHandler fixHandler = client.getFixHandler();

    @BeforeAll
    static void init() {
        fixHandler.onOpen();
        oneMessageBuffer = Unpooled.wrappedBuffer("8=FIXT.1.1\0019=13\00135=AE\001552=1\00110=169\001".getBytes(StandardCharsets.US_ASCII));
        buffer = Unpooled.wrappedBuffer(("8=FIXT.1.1\0019=13\00135=AE\001552=1\00110=169\0018=FIXT.1.1\0019=13\00135=NN" +
                "\001552=2\00110=100\0018=FIXT.1.1\0019=13\00135=NN\001552=2\00110=100\001").getBytes(StandardCharsets.US_ASCII));
        brokenBuffer = Unpooled.wrappedBuffer("A8=FIXT.1.1\0019=13\00135=AE\001552=1\00110=16913138=FIXT.1.1\0019=13\00135=AE\001552=1\00110=169\001".getBytes(StandardCharsets.US_ASCII));
    }

    @BeforeEach
    void beforeEach() {
        fixHandler.getEnabled().set(true);
    }

    @AfterAll
    static void afterAll() {
        //fixHandler.close();
    }

    @Test
    void test3188(){
        String body1 = "8=F";
        String body2 = "IXT.1.1\0019=13\00135=AE\001552=1\00158=11111\00110=169\001";
        ByteBuf byteBuf1 = Unpooled.buffer().writeBytes(body1.getBytes(StandardCharsets.UTF_8));
        fixHandler.onReceive(byteBuf1);
        assertEquals("8=F", byteBuf1.toString(StandardCharsets.US_ASCII));
        byteBuf1.writeBytes(body2.getBytes(StandardCharsets.UTF_8));
        fixHandler.onReceive(byteBuf1);
        assertEquals("", byteBuf1.toString(StandardCharsets.US_ASCII));
    }

    @Test
    void onDataBrokenMessageTest() {
        ByteBuf result0 = fixHandler.onReceive(brokenBuffer);
        ByteBuf result1 = fixHandler.onReceive(brokenBuffer);
        ByteBuf result2 = fixHandler.onReceive(brokenBuffer);
        String expected0 = "A";

        assertNotNull(result0);
        assertEquals(expected0, result0.toString(StandardCharsets.US_ASCII));
        assertNull(result1);
        assertNull(result2);
    }

    @Test
    void onReceiveCorrectMessagesTest() {

        buffer = Unpooled.wrappedBuffer(("8=FIXT.1.1\0019=13\00135=AE\001552=1\00158=11111\00110=169\0018=FIXT.1.1\0019=13\00135=NN" +
                "\001552=2\00110=100\0018=FIXT.1.1\0019=13\00135=NN\001552=2\00110=100\001").getBytes(StandardCharsets.US_ASCII));

        ByteBuf result0 = fixHandler.onReceive(buffer);
        ByteBuf result1 = fixHandler.onReceive(buffer);
        ByteBuf result2 = fixHandler.onReceive(buffer);

        String expected1 = "8=FIXT.1.1\0019=13\00135=AE\001552=1\00158=11111\00110=169\001";
        String expected2 = "8=FIXT.1.1\0019=13\00135=NN\001552=2\00110=100\001";
        String expected3 = "8=FIXT.1.1\0019=13\00135=NN\001552=2\00110=100\001";

        assertNotNull(result0);
        assertEquals(expected1, result0.toString(StandardCharsets.US_ASCII));

        assertNotNull(result1);
        assertEquals(expected2, result1.toString(StandardCharsets.US_ASCII));

        assertNotNull(result2);
        assertEquals(expected3, result2.toString(StandardCharsets.US_ASCII));
    }


    @Test
    void sendResendRequestTest() {
        String expectedLogon = "8=FIXT.1.1\u00019=95\u000135=A\u000134=2\u000149=client\u000156=server\u0001" +         // #1 sent logon
                "52=2014-12-22T10:15:30Z\u000198=0\u0001108=30\u00011137=9\001553=username\u0001554=pass\u000110=127\u0001";
        String expectedResendRequest = "8=FIXT.1.1\u00019=63\u000135=2\u000134=3\u000149=client\u000156=server" +       // #2 sent resendRequest
                "\u000152=2014-12-22T10:15:30Z\u00017=1\u000116=0\u000110=189\u0001";

        client.clearQueue();
        fixHandler.sendLogon();
        fixHandler.sendResendRequest(1);
        assertEquals(expectedLogon, new String(client.getQueue().get(0).array()));
        //assertEquals(expectedHeartbeat, new String(client.getQueue().get(1).array()));
        assertEquals(expectedResendRequest, new String(client.getQueue().get(1).array()));
    }

    @Test
    void onConnectionTest() {
        client.clearQueue();
        fixHandler.onOpen();
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        assertEquals("8=FIXT.1.1\u00019=95\u000135=A\u000134=7\u000149=client\u000156=server\u000152=2014-12-22T10:15:30Z\u000198=0\u0001108=30\u00011137=9\001553=username\u0001554=pass\u000110=132\u0001",
                new String(client.getQueue().get(0).array()));
    }

    @Test
    void onOutgoingMessageTest() {
        ByteBuf bufferForPrepareMessage1 = Unpooled.buffer().writeBytes("8=FIXT.1.1\0019=13\001552=1\00149=client\00134=8\00156=null\00110=169\001".getBytes(StandardCharsets.US_ASCII));
        ByteBuf bufferForPrepareMessage2 = Unpooled.buffer(11).writeBytes("552=1\001".getBytes(StandardCharsets.US_ASCII));
        ByteBuf bufferForPrepareMessage3 = Unpooled.buffer().writeBytes("8=FIXT.1.1\00111=9977764\00122=8\00138=100\00140=2\00144=55\00152=20220127-12:00:40.775\00148=INSTR2\00154=2\00159=3\00160=20220127-15:00:36\001528=A\001581=1\001453=4\001448=DEMO-CONN2\001447=D\001452=76\001448=0\001447=P\001452=3\001448=0\001447=P\001452=122\001448=3\001447=P\001452=12\00110=157\001".getBytes(StandardCharsets.US_ASCII));
        ByteBuf bufferForPrepareMessage4 = Unpooled.buffer().writeBytes("8=FIXT.1.1\0019=192\00135=A\00111=3428785\00122=8\00138=30\00140=2\00144=55\00148=INSTR1\00154=1\00159=0\00160=20220127-18:38:35\001526=11111\001528=A\001581=1\001453=4\001448=DEMO-CONN1\001447=D\001452=76\001448=0\001447=P\001452=3\001448=0\00147=P\001452=122\001448=3\001447=P\001452=12\00110=228\001".getBytes(StandardCharsets.US_ASCII));

        String expectedMessage1 = "8=FIXT.1.1\0019=60\00135=A\001552=1\00149=client\00134=8\00156=server\00152=2014-12-22T10:15:30Z\00110=094\001";
        String expectedMessage2 = "8=FIXT.1.1\0019=55\00134=4\00149=client\00156=server\00152=2014-12-22T10:15:30Z\001552=1\00110=119\001";
        String expectedMessage3 = "8=FIXT.1.1\0019=233\00135=A\00134=5\00149=client\00156=server\00111=9977764\00122=8\00138=100\00140=2\00144=55\00152=20220127-12:00:40.775\00148=INSTR2\00154=2\00159=3\00160=20220127-15:00:36\001528=A\001581=1\001453=4\001448=DEMO-CONN2\001447=D\001452=76\001448=0\001447=P\001452=3\001448=0\001447=P\001452=122\001448=3\001447=P\001452=12\00110=082\001";
        String expectedMessage4 = "8=FIXT.1.1\0019=240\00135=A\00134=6\00149=client\00156=server\00152=2014-12-22T10:15:30Z\00111=3428785\00122=8\00138=30\00140=2\00144=55\00148=INSTR1\00154=1\00159=0\00160=20220127-18:38:35\001526=11111\001528=A\001581=1\001453=4\001448=DEMO-CONN1\001447=D\001452=76\001448=0\001447=P\001452=3\001448=0\00147=P\001452=122\001448=3\001447=P\001452=12\00110=197\001";
        Map<String, String> expected = new HashMap<>();
        expected.put("MsgType", "A");
        Map<String, String> expected2 = new HashMap<>();
        Map<String, String> expected3 = new HashMap<>();
        expected3.put("MsgType", "A");
        Map<String, String> expected4 = new HashMap<>();
        expected4.put("MsgType", "A");

        Map<String, String> actual = new HashMap<>(expected);
        fixHandler.onOutgoing(bufferForPrepareMessage1, actual);
        assertEquals(expected, actual);

        Map<String, String> actual2 = new HashMap<>();
        fixHandler.onOutgoing(bufferForPrepareMessage2, actual2);
        assertEquals(expected2, actual2);

        fixHandler.onOutgoing(bufferForPrepareMessage3, expected3);
        fixHandler.onOutgoing(bufferForPrepareMessage4, expected4);

        bufferForPrepareMessage1.readerIndex(0);
        bufferForPrepareMessage2.readerIndex(0);

        assertEquals(expectedMessage1, bufferForPrepareMessage1.toString(StandardCharsets.US_ASCII));
        assertEquals(expectedMessage2, bufferForPrepareMessage2.toString(StandardCharsets.US_ASCII));
        assertEquals(expectedMessage3, bufferForPrepareMessage3.toString(StandardCharsets.US_ASCII));
        assertEquals(expectedMessage4, bufferForPrepareMessage4.toString(StandardCharsets.US_ASCII));
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
        String expected = "8=FIXT.1.1\u00019=60\u000135=1\u000134=1\u000149=client\u000156=server\u000152=2014-12-22T10:15:30Z\u0001112=1\u000110=063\u0001";
        client.clearQueue();
        fixHandler.sendTestRequest();
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

        ByteBuf buf = Unpooled.buffer().writeBytes("552=1\001".getBytes(StandardCharsets.UTF_8));
        String expected = "8=FIX.2.2\001552=1\001";
        String expected2 = "8=FIX.2.2\0019=19\001552=1\001";
        String expected3 = "8=FIX.2.2\0019=19\001552=1\00110=009\001";

        MessageUtil.putTag(buf, BEGIN_STRING_TAG.toString(), "FIX.2.2");
        assertNotNull(buf);
        assertEquals(expected, new String(buf.array()));

        MessageUtil.putTag(buf, BODY_LENGTH_TAG.toString(), "19");
        assertNotNull(buf);
        assertEquals(expected2, new String(buf.array()));

        MessageUtil.putTag(buf, CHECKSUM_TAG.toString(), fixHandler.getChecksum(buf));
        assertNotNull(buf);
        assertEquals(expected3, new String(buf.array()));
    }

    @Test
    void updateTagTest() {
        ByteBuf buf = Unpooled.buffer().writeBytes("8=FIX.2.2\00135=AE\001".getBytes(StandardCharsets.UTF_8));
        String expected = "8=FIX.2.2\00135=AEE\001";
        String expected2 = "8=FIX.2.3\00135=AEE\001";

        MessageUtil.updateTag(buf, MSG_TYPE_TAG.toString(), "AEE");
        assertEquals(expected, buf.toString(StandardCharsets.US_ASCII));

        MessageUtil.updateTag(buf, BEGIN_STRING_TAG.toString(), "FIX.2.3");
        assertEquals(expected2, buf.toString(StandardCharsets.US_ASCII));

        MessageUtil.updateTag(buf, DEFAULT_APPL_VER_ID_TAG.toString(), "1");
        assertEquals(expected2, buf.toString(StandardCharsets.US_ASCII));
    }

}

class Client implements IChannel {
    private final FixHandlerSettings fixHandlerSettings;
    private final MyFixHandler fixHandler;
    private final List<ByteBuf> queue = new ArrayList<>();

    Client() {
        this.fixHandlerSettings = new FixHandlerSettings();
        fixHandlerSettings.setBeginString("FIXT.1.1");
        fixHandlerSettings.setHeartBtInt(30);
        fixHandlerSettings.setSenderCompID("client");
        fixHandlerSettings.setTargetCompID("server");
        fixHandlerSettings.setEncryptMethod("0");
        fixHandlerSettings.setUsername("username");
        fixHandlerSettings.setPassword("pass");
        fixHandlerSettings.setTestRequestDelay(10);
        fixHandlerSettings.setReconnectDelay(5);
        fixHandlerSettings.setDisconnectRequestDelay(5);
        fixHandlerSettings.setResetSeqNumFlag(false);
        fixHandlerSettings.setResetOnLogon(false);
        fixHandlerSettings.setDefaultApplVerID(9);
        IContext<IProtocolHandlerSettings> context = Mockito.mock(IContext.class);
        Mockito.when(context.getSettings()).thenReturn(fixHandlerSettings);
        Mockito.when(context.getChannel()).thenReturn(this);

        this.fixHandler = new MyFixHandler(context);
    }

    @Override
    public void open() {

    }

    @Override
    public void open(InetSocketAddress address, Security security) {

    }

    @NotNull
    @Override
    public MessageID send(@NotNull ByteBuf byteBuf, @NotNull Map<String, String> map, @NotNull IChannel.SendMode sendMode) {
        queue.add(byteBuf);
        return MessageID.getDefaultInstance();
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

    public void clearQueue() {
        this.queue.clear();
    }

    @NotNull
    @Override
    public InetSocketAddress getAddress() {
        return null;
    }

    @Override
    public Security getSecurity() {
        return new Security();
    }
}

class MyFixHandler extends FixHandler {

    public MyFixHandler(IContext<IProtocolHandlerSettings> context) {
        super(context);
    }

    @Override
    public String getTime() {
        String instantExpected = "2014-12-22T10:15:30Z";
        Clock clock = Clock.fixed(Instant.parse(instantExpected), ZoneId.of("UTC"));
        return Instant.now(clock).toString();
    }
}
