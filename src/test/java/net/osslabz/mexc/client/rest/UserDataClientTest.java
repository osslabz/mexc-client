package net.osslabz.mexc.client.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("live")
public class UserDataClientTest {

    @Test
    void testListenKeys() {

        UserDataClient client = new UserDataClient(System.getenv("MEXC_API_KEY"), System.getenv("MEXC_SECRET_KEY"));
        assertEquals(0, client.getListenKeys().size());

        String listenKey = client.createListenKey();
        assertNotNull(listenKey);

        assertEquals(1, client.getListenKeys().size());
        assertEquals(listenKey, client.getListenKeys().get(0));
    }
}
