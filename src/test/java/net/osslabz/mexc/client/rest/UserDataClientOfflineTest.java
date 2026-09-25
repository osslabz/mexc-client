package net.osslabz.mexc.client.rest;

import static net.osslabz.mexc.client.rest.LocalServer.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Level;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import net.osslabz.mexc.client.CapturedLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserDataClientOfflineTest {

    private MockWebServer server;

    private UserDataClient client;

    private CapturedLog restLog;

    @BeforeEach
    void startServer() throws IOException {
        restLog = CapturedLog.of(MexcRestClient.class);
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (client != null) {
            client.close();
        }
        server.close();
        restLog.close();
    }

    @Test
    void getListenKeysReturnsTheValidKeys() throws Exception {
        server.setDispatcher(new ExchangeDispatcher("{\"listenKey\":[\"key-1\"]}"));
        client = LocalServer.userDataClient(server);

        assertEquals(List.of("key-1"), client.getListenKeys());
        assertEquals(List.of("GET", "GET", "PUT"), takeMethods(3));
    }

    @Test
    void createListenKeyReturnsTheNewKey() throws Exception {
        server.setDispatcher(new ExchangeDispatcher("{\"listenKey\":[]}"));
        client = LocalServer.userDataClient(server);

        assertEquals("key-new", client.createListenKey());
        assertEquals(List.of("GET", "POST"), takeMethods(2));
    }

    @Test
    void keepsEveryValidListenKeyAliveOnStart() throws Exception {
        server.setDispatcher(new ExchangeDispatcher("{\"listenKey\":[\"key-1\",\"key-2\"]}"));
        client = LocalServer.userDataClient(server);

        assertEquals("GET", server.takeRequest(5, TimeUnit.SECONDS).getMethod());
        assertEquals("key-1", server.takeRequest(5, TimeUnit.SECONDS).getUrl().queryParameter("listenKey"));
        assertEquals("key-2", server.takeRequest(5, TimeUnit.SECONDS).getUrl().queryParameter("listenKey"));
        restLog.await(Level.TRACE, "<-- END HTTP", 3);
    }

    @Test
    void keepsTheListenKeysAliveAfterAFailedRound() throws Exception {
        AtomicInteger gets = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("GET".equals(request.getMethod()) && gets.incrementAndGet() == 1) {
                    return json(503, "{\"code\":\"700003\",\"msg\":\"Server busy\"}");
                }
                return new ExchangeDispatcher("{\"listenKey\":[\"key-1\"]}").dispatch(request);
            }
        });
        try (CapturedLog keepAliveLog = CapturedLog.of(UserDataClient.class)) {
            client = new UserDataClient(LocalServer.restClient(server), Duration.ofMillis(250));

            assertEquals(List.of("GET", "GET", "PUT"), takeMethodsInOrder(3));
            client.close();

            restLog.await(Level.TRACE, "<-- END HTTP", 3);
            assertEquals(
                    List.of("Keeping the listen keys alive failed, next try in PT0.25S: Server busy"),
                    keepAliveLog.messages(Level.WARN));
        }
    }

    @Test
    void getListenKeysThrowsWhenTheExchangeRejectsTheRequest() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return json(401, "{\"code\":\"10072\",\"msg\":\"Api key info invalid\"}");
            }
        });
        try (CapturedLog keepAliveLog = CapturedLog.of(UserDataClient.class)) {
            client = LocalServer.userDataClient(server);

            RuntimeException e = assertThrows(RuntimeException.class, client::getListenKeys);
            assertEquals("Api key info invalid", e.getMessage());
            assertEquals(List.of("GET", "GET"), takeMethods(2));
            assertEquals(
                    List.of("Keeping the listen keys alive failed, next try in PT30M: Api key info invalid"),
                    keepAliveLog.await(Level.WARN, "Keeping the listen keys alive failed", 1));
        }
    }

    private List<String> takeMethodsInOrder(int count) throws InterruptedException {
        List<String> methods = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            methods.add(request == null ? null : request.getMethod());
        }
        return methods;
    }

    /**
     * Waits until the requests the test and the keep-alive round at start-up send are answered, and returns their
     * methods in sorted order.
     */
    private List<String> takeMethods(int count) throws InterruptedException {
        List<String> methods = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            methods.add(server.takeRequest(5, TimeUnit.SECONDS).getMethod());
        }
        methods.sort(null);
        restLog.await(Level.TRACE, "<-- END HTTP", count);
        return methods;
    }

    /** Answers like the exchange: the given key list on GET, a new key on POST, the kept key on PUT. */
    private static final class ExchangeDispatcher extends Dispatcher {

        private final String listenKeys;

        ExchangeDispatcher(String listenKeys) {
            this.listenKeys = listenKeys;
        }

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            return switch (request.getMethod()) {
                case "GET" -> json(listenKeys);
                case "POST" -> json("{\"listenKey\":\"key-new\"}");
                default -> json("{\"listenKey\":\"key-1\"}");
            };
        }
    }
}
