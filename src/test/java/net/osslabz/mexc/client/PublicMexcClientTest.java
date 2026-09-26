package net.osslabz.mexc.client;

import static net.osslabz.mexc.client.LocalExchange.await;
import static net.osslabz.mexc.client.LocalExchange.awaitRelease;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.osslabz.crypto.CurrencyPair;
import net.osslabz.crypto.Interval;
import net.osslabz.crypto.Ohlc;
import net.osslabz.mexc.client.ws.MexcWebSocketClient;
import net.osslabz.mexc.client.ws.dto.SubscriptionInfo;
import net.osslabz.mexc.client.ws.dto.SubscriptionState;
import net.osslabz.mexc.proto.PublicDealsV3Api;
import net.osslabz.mexc.proto.PublicSpotKlineV3Api;
import net.osslabz.mexc.proto.PushDataV3ApiWrapper;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PublicMexcClientTest {

    private static final CurrencyPair BTC_USDT = new CurrencyPair("BTC", "USDT");

    private static final String CHANNEL = "spot@public.kline.v3.api.pb@BTCUSDT@Min1";

    /** A push of CHANNEL as wbs-api.mexc.com sent it on 2026-09-25 at 17:19:26 UTC. */
    private static final String CAPTURED_KLINE_FRAME = "0a2873706f74407075626c69632e6b6c696e652e76332e6170692e7062"
            + "4042544355534454404d696e311a07425443555344542220326662393432313534656634346134616232656639386338616662"
            + "366134613728e3fbd0cc8d34a213500a044d696e311084dadad5061a0838333935382e3231220838333936312e32312a083833"
            + "3936312e3231320838333935352e30393a0a302e3335333838363333420832393731312e343148c0dadad506";

    private final BlockingQueue<Ohlc> received = new LinkedBlockingQueue<>();

    private LocalExchange exchange;

    private PublicMexcClient client;

    private CapturedLog clientLog;

    @BeforeEach
    void captureClientLog() {
        clientLog = CapturedLog.of(MexcClient.class);
    }

    @AfterEach
    void stop() throws InterruptedException {
        client.close();
        exchange.close();
        clientLog.close();
    }

    @Test
    void subscribeToOhlcSendsASubscriptionForTheKlineChannel() throws Exception {
        connect(0);

        client.subscribeToOhlc(BTC_USDT, Interval.PT1M, received::add);

        JsonNode command = exchange.takeCommand();
        assertEquals("SUBSCRIPTION", command.get("method").asText());
        assertEquals(CHANNEL, command.get("params").get(0).asText());
        await(() -> state() == SubscriptionState.SUBSCRIBED);
    }

    @Test
    void theFirstSubscriptionSendsASingleCommand() throws Exception {
        connect(0);

        client.subscribeToOhlc(BTC_USDT, Interval.PT1M, received::add);

        assertEquals(CHANNEL, exchange.takeCommand().get("params").get(0).asText());
        await(() -> state() == SubscriptionState.SUBSCRIBED);
        assertNull(exchange.takeCommand(Duration.ofSeconds(1)));
        assertEquals(SubscriptionState.SUBSCRIBED, state());
    }

    @Test
    void subscribeFailsWhenTheExchangeIsUnreachable() throws Exception {
        connect(0);
        exchange.close();

        try (CapturedLog connectionLog = CapturedLog.of(MexcWebSocketClient.class)) {
            assertThrows(
                    WebsocketNotConnectedException.class,
                    () -> client.subscribeToOhlc(BTC_USDT, Interval.PT1M, received::add));
            connectionLog.await(Level.WARN, "connection error with message=", 1);
        }
    }

    @Test
    void closeAfterAFailedFirstConnectOpensNoConnection() throws Exception {
        connect(0);
        exchange.close();
        try (CapturedLog connectionLog = CapturedLog.of(MexcWebSocketClient.class)) {
            assertThrows(
                    WebsocketNotConnectedException.class,
                    () -> client.subscribeToOhlc(BTC_USDT, Interval.PT1M, received::add));

            client.close();

            assertEquals(1, connectionLog.messages(Level.WARN).size());
        }
        assertTrue(clientLog.messages(Level.WARN).isEmpty());
    }

    @Test
    void subscribeConnectsAgainAfterAFailedFirstConnect() throws Exception {
        connect(0);
        exchange.rejectNextHandshake();
        try (CapturedLog connectionLog = CapturedLog.of(MexcWebSocketClient.class)) {
            assertThrows(
                    WebsocketNotConnectedException.class,
                    () -> client.subscribeToOhlc(BTC_USDT, Interval.PT1M, received::add));
            connectionLog.await(
                    Level.INFO, "connection closed with code=1002, reason=Invalid status code received: 404", 1);

            client.subscribeToOhlc(BTC_USDT, Interval.PT1M, received::add);

            await(() -> state() == SubscriptionState.SUBSCRIBED);
            assertTrue(connectionLog.messages(Level.WARN).isEmpty());
        }
    }

    @Test
    void pingsKeepAQuietConnectionOpen() throws Exception {
        exchange = LocalExchange.startClosingIdleConnections(Duration.ofMillis(500));
        client = new PublicMexcClient(exchange.uri(), Duration.ofMillis(100));
        client.subscribeToOhlc(BTC_USDT, Interval.PT1M, received::add);
        await(() -> state() == SubscriptionState.SUBSCRIBED);

        Thread.sleep(1500);

        assertEquals(0, exchange.clientCloses());
        assertTrue(exchange.pings() >= 5, "only " + exchange.pings() + " pings");
        clientLog.await(Level.TRACE, "Received PONG", 5);
        assertTrue(clientLog.messages(Level.DEBUG).isEmpty());
    }

    @Test
    void deliversTheKlinesOfASubscribedChannel() throws Exception {
        connect(0);
        client.subscribeToOhlc(BTC_USDT, Interval.PT1M, received::add);
        await(() -> state() == SubscriptionState.SUBSCRIBED);

        exchange.push(kline("spot@public.kline.v3.api.pb@ETHUSDT@Min1", "2000"));
        exchange.push(kline(CHANNEL, "75"));

        Ohlc ohlc = received.poll(5, TimeUnit.SECONDS);
        assertEquals(new BigDecimal("75"), ohlc.getClosePrice());
        assertEquals(BTC_USDT, ohlc.currencyPair());
        assertEquals(Interval.PT1M, ohlc.interval());
        assertTrue(received.isEmpty());
        clientLog.await(
                Level.WARN,
                "Received a push for an unmanaged channel, won't be processed: spot@public.kline.v3.api.pb@ETHUSDT@Min1",
                1);
    }

    @Test
    void mapsAKlineFrameCapturedFromMexc() throws Exception {
        connect(0);
        client.subscribeToOhlc(BTC_USDT, Interval.PT1M, received::add);
        await(() -> state() == SubscriptionState.SUBSCRIBED);

        exchange.push(HexFormat.of().parseHex(CAPTURED_KLINE_FRAME));

        Ohlc ohlc = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(ohlc);
        assertEquals(ZonedDateTime.parse("2026-09-25T17:19:26.179Z[UTC]"), ohlc.getUpdateTime());
        assertEquals(ZonedDateTime.parse("2026-09-25T17:19Z[UTC]"), ohlc.getOpenTime());
        assertEquals(ZonedDateTime.parse("2026-09-25T17:20Z[UTC]"), ohlc.getCloseTime());
        assertEquals(new BigDecimal("83958.21"), ohlc.getOpenPrice());
        assertEquals(new BigDecimal("83961.21"), ohlc.getHighPrice());
        assertEquals(new BigDecimal("83955.09"), ohlc.getLowPrice());
        assertEquals(new BigDecimal("83961.21"), ohlc.getClosePrice());
        assertEquals(new BigDecimal("29711.41"), ohlc.getVolume());
        assertEquals(new BigDecimal("0.35388633"), ohlc.getQuantity());
        assertEquals(new BigDecimal("83957.49561731"), ohlc.getAvgPrice());
    }

    @Test
    void ignoresAPushWithoutAKline() throws Exception {
        connect(0);
        client.subscribeToOhlc(BTC_USDT, Interval.PT1M, received::add);
        await(() -> state() == SubscriptionState.SUBSCRIBED);

        exchange.push(PushDataV3ApiWrapper.newBuilder()
                .setChannel(CHANNEL)
                .setPublicDeals(PublicDealsV3Api.getDefaultInstance())
                .build()
                .toByteArray());

        clientLog.await(
                Level.WARN,
                "Received a push with an unexpected PUBLICDEALS body on " + CHANNEL + ", won't be processed",
                1);
        assertTrue(received.isEmpty());
    }

    @Test
    void ignoresAFrameThatIsNoProtobufMessage() throws Exception {
        connect(0);
        client.subscribeToOhlc(BTC_USDT, Interval.PT1M, received::add);
        await(() -> state() == SubscriptionState.SUBSCRIBED);

        // Field 1 announces five bytes of channel name, and the frame ends after one.
        exchange.push(new byte[] {0x0a, 0x05, 0x61});

        clientLog.await(Level.WARN, "Received a push that is no protobuf message, won't be processed", 1);
        assertTrue(received.isEmpty());
    }

    @Test
    void ignoresATextMessageThatAnswersNoCommand() throws Exception {
        connect(0);
        client.subscribeToOhlc(BTC_USDT, Interval.PT1M, received::add);
        await(() -> state() == SubscriptionState.SUBSCRIBED);

        exchange.push("{\"channel\":\"%s\"}".formatted(CHANNEL));

        clientLog.await(
                Level.WARN,
                "Received a text message that answers no command, won't be processed: {\"channel\":\"%s\"}"
                        .formatted(CHANNEL),
                1);
        assertTrue(received.isEmpty());
    }

    @Test
    void marksASubscriptionTheExchangeRejectsAsFailed() throws Exception {
        connect(1);

        client.subscribeToOhlc(BTC_USDT, Interval.PT1M, received::add);

        await(() -> state() == SubscriptionState.SUBSCRIBE_FAILED);
        clientLog.await(Level.WARN, "Subscribing to " + CHANNEL + " failed with code=1", 1);
    }

    @Test
    void marksASubscriptionTheExchangeBlocksAsFailed() throws Exception {
        exchange = LocalExchange.startBlocking();
        client = new PublicMexcClient(exchange.uri());

        client.subscribeToOhlc(BTC_USDT, Interval.PT1M, received::add);

        await(() -> state() == SubscriptionState.SUBSCRIBE_FAILED);
        clientLog.await(
                Level.WARN,
                "Subscribing to %s failed with code=0: %s".formatted(CHANNEL, LocalExchange.blockedAnswer(CHANNEL)),
                1);
    }

    @Test
    void ignoresAnAnswerToARequestItDidNotSend() throws Exception {
        connect(0);
        client.subscribeToOhlc(BTC_USDT, Interval.PT1M, received::add);
        await(() -> state() == SubscriptionState.SUBSCRIBED);

        exchange.push("{\"id\":99,\"code\":1,\"msg\":\"%s\"}".formatted(CHANNEL));

        clientLog.await(Level.DEBUG, "Ignoring an answer to request id=99", 1);
        assertEquals(SubscriptionState.SUBSCRIBED, state());
    }

    @Test
    void resubscribesEachTimeTheExchangeDropsTheConnection() throws Exception {
        connect(0);
        client.subscribeToOhlc(BTC_USDT, Interval.PT1M, received::add);
        await(() -> state() == SubscriptionState.SUBSCRIBED);
        exchange.takeCommand();

        for (int drop = 1; drop <= 2; drop++) {
            exchange.dropConnections();

            JsonNode command = exchange.takeCommand();
            assertNotNull(command, "no resubscription after drop " + drop);
            assertEquals("SUBSCRIPTION", command.get("method").asText());
            assertEquals(CHANNEL, command.get("params").get(0).asText());
        }
    }

    @Test
    void closeDuringAResubscriptionOpensNoNewConnection() throws Exception {
        connect(0);
        CountDownLatch resubscribing = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicInteger subscriptions = new AtomicInteger();
        SubscriptionInfo subscription = new SubscriptionInfo(CHANNEL, null, null, SubscriptionState.INIT, push -> {}) {
            @Override
            public void setSubscribeRequestId(Integer subscribeRequestId) {
                super.setSubscribeRequestId(subscribeRequestId);
                if (subscriptions.incrementAndGet() == 2) {
                    resubscribing.countDown();
                    awaitRelease(released);
                }
            }
        };
        client.subscribe(subscription);
        await(() -> state() == SubscriptionState.SUBSCRIBED);
        exchange.takeCommand();

        exchange.dropConnections();
        assertTrue(resubscribing.await(10, TimeUnit.SECONDS), "no resubscription after the drop");
        client.close();
        released.countDown();

        clientLog.await(Level.DEBUG, "Stopped resubscribing, the connection closed meanwhile", 1);
        // One period of the reconnect monitor, which checks every three seconds.
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        for (JsonNode command = exchange.takeCommand(Duration.ofSeconds(4));
                command != null;
                command = exchange.takeCommand(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())))) {
            assertNotEquals("SUBSCRIPTION", command.get("method").asText());
        }
        assertEquals(2, exchange.handshakes());
    }

    @Test
    void unsubscribingTheLastChannelClosesTheConnection() throws Exception {
        connect(0);
        client.subscribeToOhlc(BTC_USDT, Interval.PT1M, received::add);
        await(() -> state() == SubscriptionState.SUBSCRIBED);

        client.unsubscribeFromOhlc(BTC_USDT, Interval.PT1M);

        exchange.awaitClientCloses(1);
        assertTrue(client.activeSubscriptions.isEmpty());
    }

    @Test
    void closeSurvivesTheExchangeConfirmingTheLastUnsubscriptionFirst() throws Exception {
        exchange = LocalExchange.start(0);
        client = new PublicMexcClient(exchange.uri()) {
            @Override
            protected void unsubscribe(String subscriptionIdentifier) {
                super.unsubscribe(subscriptionIdentifier);
                // The confirmation closes the client from the read thread; let that finish before close() goes on.
                try {
                    exchange.awaitClientCloses(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        };
        client.subscribeToOhlc(BTC_USDT, Interval.PT1M, received::add);
        await(() -> state() == SubscriptionState.SUBSCRIBED);

        assertDoesNotThrow(client::close);
    }

    private void connect(int answerCode) throws InterruptedException {
        exchange = LocalExchange.start(answerCode);
        client = new PublicMexcClient(exchange.uri());
    }

    private SubscriptionState state() {
        SubscriptionInfo subscription = client.activeSubscriptions.get(CHANNEL);
        return subscription == null ? null : subscription.getState();
    }

    private static byte[] kline(String channel, String closePrice) {
        return PushDataV3ApiWrapper.newBuilder()
                .setChannel(channel)
                .setSymbol("BTCUSDT")
                .setCreateTime(1_700_000_000_123L)
                .setPublicSpotKline(PublicSpotKlineV3Api.newBuilder()
                        .setInterval("Min1")
                        .setWindowStart(1_699_999_980L)
                        .setWindowEnd(1_700_000_040L)
                        .setOpeningPrice("70")
                        .setHighestPrice("80")
                        .setLowestPrice("60")
                        .setClosingPrice(closePrice)
                        .setVolume("4")
                        .setAmount("300"))
                .build()
                .toByteArray();
    }
}
