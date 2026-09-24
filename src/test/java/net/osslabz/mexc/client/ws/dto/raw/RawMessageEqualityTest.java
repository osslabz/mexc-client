package net.osslabz.mexc.client.ws.dto.raw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class RawMessageEqualityTest {

    @Test
    void ordersOfDifferentSymbolsDiffer() {
        assertNotEquals(order("BTCUSDT"), order("ETHUSDT"));
    }

    @Test
    void ordersWithTheSameContentAreEqual() {
        assertEquals(order("BTCUSDT"), order("BTCUSDT"));
        assertEquals(order("BTCUSDT").hashCode(), order("BTCUSDT").hashCode());
    }

    @Test
    void klinesOfDifferentSymbolsDiffer() {
        assertNotEquals(kline("BTCUSDT"), kline("ETHUSDT"));
    }

    @Test
    void klinesWithTheSameContentAreEqual() {
        assertEquals(kline("BTCUSDT"), kline("BTCUSDT"));
        assertEquals(kline("BTCUSDT").hashCode(), kline("BTCUSDT").hashCode());
    }

    private static RawOrder order(String symbol) {
        RawOrder order = new RawOrder();
        order.setSymbol(symbol);
        order.setData(new RawOrder.OrderData());
        return order;
    }

    private static RawOhlc kline(String symbol) {
        RawOhlc kline = new RawOhlc();
        kline.setSymbol(symbol);
        return kline;
    }
}
