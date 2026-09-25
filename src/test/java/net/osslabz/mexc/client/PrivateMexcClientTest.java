package net.osslabz.mexc.client;

import static net.osslabz.mexc.client.rest.LocalServer.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import net.osslabz.crypto.CurrencyPair;
import net.osslabz.crypto.Exchange;
import net.osslabz.crypto.Order;
import net.osslabz.crypto.OrderAction;
import net.osslabz.crypto.OrderStatus;
import net.osslabz.crypto.OrderType;
import net.osslabz.crypto.TradingAsset;
import net.osslabz.mexc.client.rest.LocalServer;
import net.osslabz.mexc.client.rest.MexcRestClient;
import net.osslabz.mexc.client.rest.UserDataClient;
import net.osslabz.mexc.client.ws.dto.SubscriptionInfo;
import net.osslabz.mexc.proto.PrivateDealsV3Api;
import net.osslabz.mexc.proto.PrivateOrdersV3Api;
import net.osslabz.mexc.proto.PushDataV3ApiWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrivateMexcClientTest {

    private static final String CHANNEL = "spot@private.orders.v3.api.pb";

    /** The sample push of https://www.mexc.com/api-docs/spot-v3/websocket-user-data-streams/spot-account-orders */
    private static final PushDataV3ApiWrapper ORDER_UPDATE = PushDataV3ApiWrapper.newBuilder()
            .setChannel(CHANNEL)
            .setSymbol("MXUSDT")
            .setSendTime(1_736_417_034_281L)
            .setPrivateOrders(PrivateOrdersV3Api.newBuilder()
                    .setId("C02__505979017439002624115")
                    .setPrice("3.5121")
                    .setQuantity("1")
                    .setAmount("0")
                    .setAvgPrice("3.6962")
                    .setOrderType(5)
                    .setTradeType(2)
                    .setRemainAmount("0")
                    .setRemainQuantity("0")
                    .setLastDealQuantity("1")
                    .setCumulativeQuantity("1")
                    .setCumulativeAmount("3.6962")
                    .setStatus(2)
                    .setCreateTime(1_736_417_034_259L))
            .build();

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
        try (CapturedLog keepAliveLog = CapturedLog.of(UserDataClient.class)) {
            userDataClient = LocalServer.userDataClient(restServer);
            client = new PrivateMexcClient(exchange.uri(), userDataClient);

            RuntimeException e = assertThrows(RuntimeException.class, () -> client.subscribeToOrders(ignored -> {}));
            assertEquals("Api key info invalid", e.getMessage());
            awaitResponses(2);
            assertEquals(
                    List.of("Keeping the listen keys alive failed, next try in PT30M: Api key info invalid"),
                    keepAliveLog.await(Level.WARN, "Keeping the listen keys alive failed", 1));
        }
    }

    @Test
    void subscribeToOrdersDeliversOrderUpdatesToTheCallback() throws Exception {
        connect("{\"listenKey\":[\"key-1\"]}");
        BlockingQueue<Order> orders = new LinkedBlockingQueue<>();

        client.subscribeToOrders((Order order) -> orders.add(order));
        exchange.takeCommand();
        exchange.push(ORDER_UPDATE.toByteArray());

        Order order = orders.poll(5, TimeUnit.SECONDS);
        assertNotNull(order);
        assertEquals("C02__505979017439002624115", order.getExchangeOrderId());
        awaitResponses(3);
    }

    @Test
    void closeStopsTheListenKeyKeepAlive() throws Exception {
        connect("{\"listenKey\":[\"key-1\"]}");
        client.subscribeToOrders(ignored -> {});
        awaitResponses(3);

        client.close();

        LocalExchange.await(() -> !keepAliveRunning());
    }

    @Test
    void subscribeToOrdersAfterCloseThrows() throws Exception {
        connect("{\"listenKey\":[\"key-1\"]}");
        awaitResponses(2);

        client.close();

        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> client.subscribeToOrders(ignored -> {}));
        assertEquals("The client is closed", e.getMessage());
    }

    @Test
    void unsubscribingFromOrdersKeepsTheListenKeyAlive() throws Exception {
        connect("{\"listenKey\":[\"key-1\"]}");
        client.subscribeToOrders(ignored -> {});
        exchange.takeCommand();

        client.unsubscribeFromOrders();
        exchange.awaitClientCloses(1);

        assertKeepAliveKeepsRunning();
        awaitResponses(3);
    }

    @Test
    void mapsAnOrderUpdate() throws Exception {
        connect("{\"listenKey\":[]}");

        Order order = (Order) client.doHandleMessage(
                SubscriptionInfo.builder().subscriptionIdentifier(CHANNEL).build(), ORDER_UPDATE);

        assertEquals("C02__505979017439002624115", order.getExchangeOrderId());
        assertEquals(new TradingAsset(Exchange.MEXC, new CurrencyPair("MX", "USDT")), order.getAsset());
        assertEquals(OrderAction.SELL, order.getAction());
        assertEquals(OrderType.MARKET, order.getType());
        assertEquals(OrderStatus.FILLED, order.getStatus());
        assertEquals(new BigDecimal("3.5121"), order.getPrice());
        assertEquals(new BigDecimal("1"), order.getQuantity());
        assertEquals(new BigDecimal("0"), order.getAmount());
        assertEquals(new BigDecimal("3.6962"), order.getAvgPrice());
        assertEquals(new BigDecimal("1"), order.getCumulativeQuantity());
        assertEquals(new BigDecimal("3.6962"), order.getCumulativeAmount());
        assertEquals(ZonedDateTime.parse("2025-01-09T10:03:54.259Z[UTC]"), order.getCreatedAt());
        assertEquals(ZonedDateTime.parse("2025-01-09T10:03:54.281Z[UTC]"), order.getUpdatedAt());
        awaitResponses(1);
    }

    @Test
    void mapsNothingButOrders() throws Exception {
        connect("{\"listenKey\":[]}");
        SubscriptionInfo orders =
                SubscriptionInfo.builder().subscriptionIdentifier(CHANNEL).build();
        SubscriptionInfo deals = SubscriptionInfo.builder()
                .subscriptionIdentifier("spot@private.deals.v3.api.pb")
                .build();
        PushDataV3ApiWrapper deal = PushDataV3ApiWrapper.newBuilder()
                .setChannel(CHANNEL)
                .setPrivateDeals(PrivateDealsV3Api.getDefaultInstance())
                .build();

        assertNull(client.doHandleMessage(orders, deal));
        assertNull(client.doHandleMessage(deals, ORDER_UPDATE));
        awaitResponses(1);
    }

    /** The connection closes before the keep-alive would stop, so this watches for a while. */
    private static void assertKeepAliveKeepsRunning() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            assertTrue(keepAliveRunning());
            Thread.sleep(10);
        }
    }

    private static boolean keepAliveRunning() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> "mexc-listen-key-keep-alive".equals(thread.getName()) && thread.isAlive());
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
