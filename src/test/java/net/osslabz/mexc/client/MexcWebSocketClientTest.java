package net.osslabz.mexc.client;

import static net.osslabz.mexc.client.LocalExchange.awaitRelease;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.osslabz.mexc.client.ws.MexcWebSocketClient;
import net.osslabz.mexc.client.ws.WebSocketListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MexcWebSocketClientTest {

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
