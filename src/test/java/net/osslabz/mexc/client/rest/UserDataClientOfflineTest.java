package net.osslabz.mexc.client.rest;

import static net.osslabz.mexc.client.rest.LocalServer.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserDataClientOfflineTest {

    private MockWebServer server;

    private UserDataClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (client != null) {
            client.close();
        }
        server.close();
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
    void getListenKeysThrowsWhenTheExchangeRejectsTheRequest() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return json(401, "{\"code\":\"10072\",\"msg\":\"Api key info invalid\"}");
            }
        });
        client = LocalServer.userDataClient(server);

        RuntimeException e = assertThrows(RuntimeException.class, client::getListenKeys);
        assertEquals("Api key info invalid", e.getMessage());
        assertEquals(List.of("GET", "GET"), takeMethods(2));
    }

    /** Waits for the requests the test and the keep-alive round at start-up send, in sorted order. */
    private List<String> takeMethods(int count) throws InterruptedException {
        List<String> methods = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            methods.add(server.takeRequest(5, TimeUnit.SECONDS).getMethod());
        }
        methods.sort(null);
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
