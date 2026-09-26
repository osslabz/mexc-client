package net.osslabz.mexc.client;

import static net.osslabz.mexc.client.LocalExchange.await;
import static net.osslabz.mexc.client.LocalExchange.awaitRelease;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.osslabz.mexc.client.ws.MexcWebSocketClient;
import net.osslabz.mexc.client.ws.WebSocketListener;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MexcWebSocketClientTest {

    private static final String UNSUBSCRIPTION =
            "{\"id\":1,\"method\":\"UNSUBSCRIPTION\",\"params\":[\"spot@public.kline.v3.api.pb@BTCUSDT@Min1\"]}";

    private LocalExchange exchange;

    private MexcWebSocketClient client;

    private CapturedLog connectionLog;

    @BeforeEach
    void start() throws InterruptedException {
        connectionLog = CapturedLog.of(MexcWebSocketClient.class);
        exchange = LocalExchange.start(0);
    }

    @AfterEach
    void stop() throws InterruptedException {
        client.close();
        exchange.close();
        connectionLog.close();
    }

    @Test
    void closeDuringTheReconnectOpenLeavesNoMonitorRunning() throws Exception {
        CountDownLatch reopened = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicInteger opens = new AtomicInteger();
        client = new MexcWebSocketClient(URI.create(exchange.uri()), new SilentListener() {
            @Override
            public void onOpen() {
                if (opens.incrementAndGet() == 2) {
                    reopened.countDown();
                    awaitRelease(released);
                }
            }
        });
        client.open();
        exchange.awaitOpenConnections(1);

        exchange.dropConnections();
        assertTrue(reopened.await(10, TimeUnit.SECONDS), "the monitor did not reconnect");
        client.close();
        released.countDown();

        exchange.awaitClientCloses(2);
        assertEquals(2, opens.get());
        assertEquals(List.of(), connectionLog.messages(Level.WARN));
    }

    @Test
    void closeDuringAReconnectLeavesNoConnectionOpen() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        client = new MexcWebSocketClient(URI.create(exchange.uri()), new SilentListener() {
            @Override
            public void onOpen() {
                opens.incrementAndGet();
            }
        });
        CountDownLatch reconnecting = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicInteger resolves = new AtomicInteger();
        // The reconnect resolves the host after it reset the old connection and before it opens the new one.
        client.setDnsResolver(uri -> {
            if (resolves.incrementAndGet() == 2) {
                reconnecting.countDown();
                awaitRelease(released);
            }
            return InetAddress.getByName(uri.getHost());
        });
        client.open();
        exchange.awaitOpenConnections(1);

        exchange.dropConnections();
        assertTrue(reconnecting.await(10, TimeUnit.SECONDS), "the monitor did not reconnect");
        client.close();
        released.countDown();

        exchange.awaitClientCloses(2);
        await(() -> !client.isOpen());
        assertEquals(1, opens.get());
        assertEquals(List.of(), connectionLog.messages(Level.WARN));
    }

    @Test
    void sendAfterCloseOpensNoConnection() {
        client = new MexcWebSocketClient(URI.create(exchange.uri()), new SilentListener());
        client.close();

        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> assertThrows(WebsocketNotConnectedException.class, () -> client.send(UNSUBSCRIPTION)));
        assertEquals(0, exchange.handshakes());
    }

    private static class SilentListener implements WebSocketListener {

        @Override
        public void onOpen() {}

        @Override
        public void onMessage(String message) {}

        @Override
        public void onMessage(ByteBuffer bytes) {}

        @Override
        public void onError(Exception e) {}

        @Override
        public void onClose(int code, String reason, boolean remote) {}
    }
}
