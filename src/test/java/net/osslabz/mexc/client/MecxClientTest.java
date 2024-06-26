package net.osslabz.mexc.client;

import lombok.extern.slf4j.Slf4j;
import net.osslabz.crypto.CurrencyPair;
import net.osslabz.crypto.Interval;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@Slf4j
public class MecxClientTest {

    private static MexcClient client;

    @BeforeAll
    static void init() {
        client = new MexcClient();
    }

    @AfterAll
    static void close() {
        client.close();
    }

    @Test
    void testSubscribeKline() throws InterruptedException {
        client.subscribe(new CurrencyPair("BTC", "USDT"), Interval.PT1M, ohlc -> {
            log.debug("{}", ohlc);
        });

        Thread.sleep(10000);

        client.unsubscribe(new CurrencyPair("BTC", "USDT"), Interval.PT1M);
    }
}