package net.osslabz.mexc.client.rest;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import net.osslabz.mexc.client.rest.dto.ListenKey;
import net.osslabz.mexc.client.rest.dto.ListenKeys;

@Slf4j
public class UserDataClient {

    public static final String USER_DATA_STREAM_LISTEN_KEY = "/api/v3/userDataStream";

    // MEXC expires a listen key 60 minutes after the last keep-alive and recommends one every 30.
    private static final Duration KEEP_ALIVE_INTERVAL = Duration.ofMinutes(30);

    private static final String KEEP_ALIVE_THREAD_NAME = "mexc-listen-key-keep-alive";

    private final MexcRestClient restClient;

    private final ScheduledExecutorService scheduler;

    private final ScheduledFuture<?> listenKeyKeepAlive;

    public UserDataClient(String acessKey, String secretKey) {
        this(new MexcRestClient(acessKey, secretKey));
    }

    UserDataClient(MexcRestClient restClient) {
        this(restClient, KEEP_ALIVE_INTERVAL);
    }

    UserDataClient(MexcRestClient restClient, Duration keepAliveInterval) {
        this.restClient = restClient;

        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> new Thread(task, KEEP_ALIVE_THREAD_NAME));
        this.listenKeyKeepAlive = scheduler.scheduleAtFixedRate(
                () -> this.keepAliveListenKeys(keepAliveInterval),
                0,
                keepAliveInterval.toMillis(),
                TimeUnit.MILLISECONDS);
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

    // A periodic task that throws is never run again, so a failed round must not escape.
    private void keepAliveListenKeys(Duration keepAliveInterval) {
        try {
            this.getListenKeys().forEach(this::keepAliveListenKey);
        } catch (RuntimeException e) {
            log.warn("Keeping the listen keys alive failed, next try in {}: {}", keepAliveInterval, e.getMessage());
        }
    }

    private void keepAliveListenKey(String listenKey) {
        this.restClient.put(USER_DATA_STREAM_LISTEN_KEY, Map.of("listenKey", listenKey), ListenKey.class);
    }
}
