package net.osslabz.mexc.client.rest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class UserDataClientTest {

    @Test
    void testListenKeys() {

        UserDataClient client = new UserDataClient("mx0vglEXT8A66CVbFX", "8500961100d54edfb11e0196f412597b");
        assertEquals(0, client.getListenKeys().size());

        String listenKey = client.createListenKey();
        assertNotNull(listenKey);

        assertEquals(1, client.getListenKeys().size());
        assertEquals(listenKey, client.getListenKeys().get(0));


    }
}