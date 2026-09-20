package net.osslabz.mexc.client.rest;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("live")
public class UserDataClientTest {

    @Test
    void testListenKeys() {

        UserDataClient client = new UserDataClient("MEXC_API_KEY_REMOVED", "MEXC_SECRET_KEY_REMOVED");
        assertEquals(0, client.getListenKeys().size());

        String listenKey = client.createListenKey();
        assertNotNull(listenKey);

        assertEquals(1, client.getListenKeys().size());
        assertEquals(listenKey, client.getListenKeys().get(0));


    }
}