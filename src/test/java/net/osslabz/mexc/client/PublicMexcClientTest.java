package net.osslabz.mexc.client;

import static net.osslabz.mexc.client.LocalExchange.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.osslabz.crypto.CurrencyPair;
import net.osslabz.crypto.Interval;
import net.osslabz.crypto.Ohlc;
import net.osslabz.mexc.client.ws.dto.SubscriptionInfo;
import net.osslabz.mexc.client.ws.dto.SubscriptionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PublicMexcClientTest {

    private static final CurrencyPair BTC_USDT = new CurrencyPair("BTC", "USDT");

    private static final String CHANNEL = "spot@public.kline.v3.api@BTCUSDT@Min1";

    private final BlockingQueue<Ohlc> received = new LinkedBlockingQueue<>();

    private LocalExchange exchange;

    private PublicMexcClient client;

    @AfterEach
    void stop() throws InterruptedException {
        client.close();
        exchange.close();
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
    void deliversTheKlinesOfASubscribedChannel() throws Exception {
        connect(0);
        client.subscribeToOhlc(BTC_USDT, Interval.PT1M, received::add);
        await(() -> state() == SubscriptionState.SUBSCRIBED);

        exchange.push(kline("spot@public.kline.v3.api@ETHUSDT@Min1", "2000"));
        exchange.push(kline(CHANNEL, "75"));

        Ohlc ohlc = received.poll(5, TimeUnit.SECONDS);
        assertEquals(new BigDecimal("75"), ohlc.getClosePrice());
        assertEquals(BTC_USDT, ohlc.currencyPair());
        assertEquals(Interval.PT1M, ohlc.interval());
        assertTrue(received.isEmpty());
    }

    @Test
    void marksASubscriptionTheExchangeRejectsAsFailed() throws Exception {
        connect(1);

        client.subscribeToOhlc(BTC_USDT, Interval.PT1M, received::add);

        await(() -> state() == SubscriptionState.SUBSCRIBE_FAILED);
    }

    @Test
    void resubscribesAfterTheExchangeDropsTheConnection() throws Exception {
        connect(0);
        client.subscribeToOhlc(BTC_USDT, Interval.PT1M, received::add);
        await(() -> state() == SubscriptionState.SUBSCRIBED);
        // The first connect subscribes twice: from subscribe itself and from the resubscribe in onOpen.
        exchange.takeCommand();
        exchange.takeCommand();

        exchange.dropConnections();

        JsonNode command = exchange.takeCommand();
        assertEquals("SUBSCRIPTION", command.get("method").asText());
        assertEquals(CHANNEL, command.get("params").get(0).asText());
    }

    @Test
    void unsubscribingTheLastChannelClosesTheConnection() throws Exception {
        connect(0);
        client.subscribeToOhlc(BTC_USDT, Interval.PT1M, received::add);
        await(() -> state() == SubscriptionState.SUBSCRIBED);

        client.unsubscribeFromOhlc(BTC_USDT, Interval.PT1M);

        assertTrue(exchange.awaitClientClose());
        assertTrue(client.activeSubscriptions.isEmpty());
    }

    private void connect(int answerCode) throws InterruptedException {
        exchange = LocalExchange.start(answerCode);
        client = new PublicMexcClient(exchange.uri());
    }

    private SubscriptionState state() {
        SubscriptionInfo subscription = client.activeSubscriptions.get(CHANNEL);
        return subscription == null ? null : subscription.getState();
    }

    private static String kline(String channel, String closePrice) {
        return """
                {"c":"%s","s":"BTCUSDT","t":1700000000123,
                 "d":{"k":{"t":1699999980,"T":1700000040,"o":"70","h":"80","l":"60","c":"%s","a":"300","v":"4"}}}
                """.formatted(channel, closePrice);
    }
}
