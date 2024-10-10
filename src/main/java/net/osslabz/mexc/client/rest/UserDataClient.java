package net.osslabz.mexc.client.rest;

import net.osslabz.mexc.client.rest.dto.ListenKey;
import net.osslabz.mexc.client.rest.dto.ListenKeys;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class UserDataClient {


    public static final String USER_DATA_STREAM_LISTEN_KEY = "/api/v3/userDataStream";
    private final MexcRestClient restClient;

    private final ScheduledExecutorService scheduler;

    public UserDataClient(String acessKey, String secretKey) {
        this.restClient = new MexcRestClient(acessKey, secretKey);

        this.scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> this.getListenKeys().forEach(this::keepAliveListenKey), 0, 30, TimeUnit.MINUTES);
    }

    public void close() {
        scheduler.shutdown();
    }

    public List<String> getListenKeys() {
        return this.restClient.get(USER_DATA_STREAM_LISTEN_KEY, Map.of(), ListenKeys.class).getListenKey();
    }

    public String createListenKey() {
        return this.restClient.post(USER_DATA_STREAM_LISTEN_KEY, Map.of(), ListenKey.class).getListenKey();
    }

    private void keepAliveListenKey(String listenKey) {
        this.restClient.put(USER_DATA_STREAM_LISTEN_KEY, Map.of("listenKey", listenKey), ListenKeys.class);
    }
}