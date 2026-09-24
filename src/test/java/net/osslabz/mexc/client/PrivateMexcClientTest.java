package net.osslabz.mexc.client;

import static net.osslabz.mexc.client.rest.LocalServer.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import net.osslabz.crypto.Order;
import net.osslabz.crypto.OrderAction;
import net.osslabz.crypto.OrderStatus;
import net.osslabz.crypto.OrderType;
import net.osslabz.mexc.client.rest.LocalServer;
import net.osslabz.mexc.client.rest.MexcRestClient;
import net.osslabz.mexc.client.rest.UserDataClient;
import net.osslabz.mexc.client.ws.dto.SubscriptionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrivateMexcClientTest {

    private static final String CHANNEL = "spot@private.orders.v3.api";

    private MockWebServer restServer;

    private LocalExchange exchange;

    private UserDataClient userDataClient;

    private PrivateMexcClient client;

    private CapturedLog restLog;

    @BeforeEach
    void start() throws IOException, InterruptedException {
        restLog = CapturedLog.of(MexcRestClient.class);
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
        restLog.close();
    }

    @Test
    void subscribeToOrdersConnectsWithAValidListenKey() throws Exception {
        connect("{\"listenKey\":[\"key-1\"]}");

        client.subscribeToOrders(ignored -> {});

        assertEquals("/?listenKey=key-1", exchange.takeOpenedResource());
        JsonNode command = exchange.takeCommand();
        assertEquals("SUBSCRIPTION", command.get("method").asText());
        assertEquals(CHANNEL, command.get("params").get(0).asText());
        awaitResponses(3);
    }

    @Test
    void subscribeToOrdersCreatesAListenKeyWhenNoneIsValid() throws Exception {
        connect("{\"listenKey\":[]}");

        client.subscribeToOrders(ignored -> {});

        assertEquals("/?listenKey=key-new", exchange.takeOpenedResource());
        awaitResponses(3);
    }

    @Test
    void subscribeToOrdersFailsWhenTheExchangeRejectsTheApiKey() throws Exception {
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
        awaitResponses(2);
    }

    @Test
    void mapsAnOrderUpdate() throws Exception {
        connect("{\"listenKey\":[]}");
        JsonNode message = new ObjectMapper().readTree("""
                {"c":"spot@private.orders.v3.api","s":"BTCUSDT","t":1700000000123,
                 "d":{"i":"order-1","c":"client-1","S":1,"o":1,"s":1,"p":"76","v":"2","a":"152",
                      "ap":"0","cv":"0","ca":"0","O":1700000000100}}
                """);

        Order order = (Order) client.doHandleMessage(
                SubscriptionInfo.builder().subscriptionIdentifier(CHANNEL).build(), message);

        assertEquals("order-1", order.getExchangeOrderId());
        assertEquals(OrderAction.BUY, order.getAction());
        assertEquals(OrderType.LIMIT, order.getType());
        assertEquals(OrderStatus.NEW, order.getStatus());
        assertEquals(new BigDecimal("76"), order.getPrice());
        assertEquals(ZonedDateTime.parse("2023-11-14T22:13:20.100Z[UTC]"), order.getCreatedAt());
        awaitResponses(1);
    }

    /** Waits until the listen key requests, including the keep-alive round at start-up, are answered. */
    private void awaitResponses(int count) throws InterruptedException {
        restLog.await(Level.TRACE, "<-- END HTTP", count);
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
