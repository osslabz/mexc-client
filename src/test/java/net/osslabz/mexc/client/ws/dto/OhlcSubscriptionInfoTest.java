package net.osslabz.mexc.client.ws.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import net.osslabz.crypto.CurrencyPair;
import net.osslabz.crypto.Interval;
import org.junit.jupiter.api.Test;

class OhlcSubscriptionInfoTest {

    @Test
    void subscriptionsOfDifferentIntervalsDiffer() {
        assertNotEquals(subscription(Interval.PT1M), subscription(Interval.PT5M));
    }

    @Test
    void subscriptionsWithTheSameContentAreEqual() {
        assertEquals(subscription(Interval.PT1M), subscription(Interval.PT1M));
        assertEquals(
                subscription(Interval.PT1M).hashCode(),
                subscription(Interval.PT1M).hashCode());
    }

    private static OhlcSubscriptionInfo subscription(Interval interval) {
        return OhlcSubscriptionInfo.builder()
                .subscriptionIdentifier("spot@public.kline.v3.api@BTCUSDT")
                .currencyPair(new CurrencyPair("BTC", "USDT"))
                .interval(interval)
                .build();
    }
}
