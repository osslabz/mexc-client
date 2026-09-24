package net.osslabz.mexc.client;

import static net.osslabz.mexc.client.rest.LocalServer.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import net.osslabz.mexc.client.rest.LocalServer;
import net.osslabz.mexc.client.rest.UserDataClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrivateMexcClientTest {

    private static final String CHANNEL = "spot@private.orders.v3.api";

    private MockWebServer restServer;

    private LocalExchange exchange;

    private UserDataClient userDataClient;

    private PrivateMexcClient client;

    @BeforeEach
    void start() throws IOException, InterruptedException {
        restServer = new MockWebServer();
        restServer.start();
        exchange = LocalExchange.start(0);
    }

    @AfterEach
    void stop() throws InterruptedException {
        client.close();
        userDataClient.close();
        exchange.close();
        restServer.close();
    }

    @Test
    void subscribeToOrdersConnectsWithAValidListenKey() throws Exception {
        connect("{\"listenKey\":[\"key-1\"]}");

        client.subscribeToOrders(ignored -> {});

        assertEquals("/?listenKey=key-1", exchange.takeOpenedResource());
        JsonNode command = exchange.takeCommand();
        assertEquals("SUBSCRIPTION", command.get("method").asText());
        assertEquals(CHANNEL, command.get("params").get(0).asText());
    }

    @Test
    void subscribeToOrdersCreatesAListenKeyWhenNoneIsValid() throws Exception {
        connect("{\"listenKey\":[]}");

        client.subscribeToOrders(ignored -> {});

        assertEquals("/?listenKey=key-new", exchange.takeOpenedResource());
    }

    @Test
    void subscribeToOrdersFailsWhenTheExchangeRejectsTheApiKey() {
        restServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return json(401, "{\"code\":\"10072\",\"msg\":\"Api key info invalid\"}");
            }
        });
        userDataClient = LocalServer.userDataClient(restServer);
        client = new PrivateMexcClient(exchange.uri(), userDataClient);

        RuntimeException e = assertThrows(RuntimeException.class, () -> client.subscribeToOrders(ignored -> {}));
        assertEquals("Api key info invalid", e.getMessage());
    }

    private void connect(String listenKeys) {
        restServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return switch (request.getMethod()) {
                    case "GET" -> json(listenKeys);
                    case "POST" -> json("{\"listenKey\":\"key-new\"}");
                    default -> json("{\"listenKey\":\"key-1\"}");
                };
            }
        });
        userDataClient = LocalServer.userDataClient(restServer);
        client = new PrivateMexcClient(exchange.uri(), userDataClient);
    }
}
