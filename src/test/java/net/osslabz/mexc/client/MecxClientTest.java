package net.osslabz.mexc.client;

import lombok.extern.slf4j.Slf4j;
import net.osslabz.crypto.CurrencyPair;
import net.osslabz.crypto.Interval;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@Slf4j
public class MecxClientTest {

    private static PublicMexcClient client;

    @BeforeAll
    static void init() {
        client = new PublicMexcClient();
    }

    @AfterAll
    static void close() {
        client.close();
    }

    @Test
    void testSubscribeKline() throws InterruptedException {
        client.subscribeToOhlc(new CurrencyPair("BTC", "USDT"), Interval.PT1M, ohlc -> {
            log.debug("{}", ohlc);
        });

        Thread.sleep(10000);

        client.unsubscribeFromOhlc(new CurrencyPair("BTC", "USDT"), Interval.PT1M);

        Thread.sleep(10000);

        client.subscribeToOhlc(new CurrencyPair("ETH", "USDT"), Interval.PT1M, ohlc -> {
            log.debug("{}", ohlc);
        });

        Thread.sleep(10000);

        client.unsubscribeFromOhlc(new CurrencyPair("ETH", "USDT"), Interval.PT1M);

        Thread.sleep(10000);
    }
}