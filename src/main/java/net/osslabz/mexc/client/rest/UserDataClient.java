package net.osslabz.mexc.client.rest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.osslabz.mexc.client.rest.dto.ListenKey;
import net.osslabz.mexc.client.rest.dto.ListenKeys;

public class UserDataClient {

    public static final String USER_DATA_STREAM_LISTEN_KEY = "/api/v3/userDataStream";

    private static final String KEEP_ALIVE_THREAD_NAME = "mexc-listen-key-keep-alive";

    private final MexcRestClient restClient;

    private final ScheduledExecutorService scheduler;

    private final ScheduledFuture<?> listenKeyKeepAlive;

    public UserDataClient(String acessKey, String secretKey) {
        this(new MexcRestClient(acessKey, secretKey));
    }

    UserDataClient(MexcRestClient restClient) {
        this.restClient = restClient;

        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> new Thread(task, KEEP_ALIVE_THREAD_NAME));
        this.listenKeyKeepAlive = scheduler.scheduleAtFixedRate(
                () -> this.getListenKeys().forEach(this::keepAliveListenKey), 0, 30, TimeUnit.MINUTES);
    }

    public void close() {
        listenKeyKeepAlive.cancel(false);
        scheduler.shutdown();
    }

    public List<String> getListenKeys() {
        return this.restClient
                .get(USER_DATA_STREAM_LISTEN_KEY, Map.of(), ListenKeys.class)
                .getListenKey();
    }

    public String createListenKey() {
        return this.restClient
                .post(USER_DATA_STREAM_LISTEN_KEY, Map.of(), ListenKey.class)
                .getListenKey();
    }

    private void keepAliveListenKey(String listenKey) {
        this.restClient.put(USER_DATA_STREAM_LISTEN_KEY, Map.of("listenKey", listenKey), ListenKey.class);
    }
}
