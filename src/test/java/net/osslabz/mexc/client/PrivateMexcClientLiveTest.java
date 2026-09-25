package net.osslabz.mexc.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import net.osslabz.crypto.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Needs MEXC_API_KEY and MEXC_SECRET_KEY, and a new order placed on that account while it waits. */
@Slf4j
@Tag("live")
class PrivateMexcClientLiveTest {

    @Test
    void deliversAnOrderUpdateWithTimesInMilliseconds() throws InterruptedException {
        BlockingQueue<Order> orders = new LinkedBlockingQueue<>();
        PrivateMexcClient client =
                new PrivateMexcClient(System.getenv("MEXC_API_KEY"), System.getenv("MEXC_SECRET_KEY"));
        try {
            client.subscribeToOrders(orders::add);
            log.info("Subscribed; place a new order within the next two minutes");

            Order order = orders.poll(2, TimeUnit.MINUTES);

            assertNotNull(order, "no order update within two minutes");
            log.info("Received {}", order);
            // Seconds read as milliseconds would land in January 1970.
            assertCloseToNow(order.getCreatedAt());
            assertCloseToNow(order.getUpdatedAt());
        } finally {
            client.close();
        }
    }

    private static void assertCloseToNow(ZonedDateTime time) {
        Duration offset = Duration.between(time, ZonedDateTime.now(MexcMapper.ZONE_ID_UTC))
                .abs();
        assertTrue(offset.compareTo(Duration.ofMinutes(5)) < 0, "%s is %s away from now".formatted(time, offset));
    }
}
