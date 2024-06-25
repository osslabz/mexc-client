package net.osslabz.mexc.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.osslabz.crypto.CurrencyPair;
import net.osslabz.crypto.Interval;
import net.osslabz.crypto.Ohlc;
import net.osslabz.mexc.client.ws.Method;
import net.osslabz.mexc.client.ws.MexcWebSocketClient;
import net.osslabz.mexc.client.ws.SubscriptionInfo;
import net.osslabz.mexc.client.ws.SubscriptionRequest;
import net.osslabz.mexc.client.ws.SubscriptionResponse;
import net.osslabz.mexc.client.ws.SubscriptionState;
import net.osslabz.mexc.client.ws.WebSocketListener;

import java.io.Closeable;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Slf4j
public class MexcClient implements Closeable {

    private final MexcWebSocketClient webSocketClient;

    private final MexcMapper mapper;

    private final ObjectMapper objectMapper;

    private final Map<String, SubscriptionInfo> subscriptions = new ConcurrentHashMap<>();

    private final AtomicInteger openCounter = new AtomicInteger(0);

    private final AtomicInteger requestIdCounter = new AtomicInteger(0);


    public MexcClient() {
        try {
            this.webSocketClient = new MexcWebSocketClient(new URI("wss://wbs.mexc.com/ws"), new WebSocketListener() {
                @Override
                public void onOpen() {
                    int opened = openCounter.getAndIncrement();
                    if (opened > 0) {
                        resubscribe();
                    }
                }

                @Override
                public void onMessage(String message) {
                    handleMessage(message);
                }

                @Override
                public void onMessage(ByteBuffer bytes) {
                    throw new IllegalArgumentException("Not implemented");
                }

                @Override
                public void onError(Exception e) {

                }

                @Override
                public void onClose(int code, String reason, boolean remote) {

                }
            });
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        this.objectMapper = new ObjectMapper();
        this.objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        this.mapper = new MexcMapper();
    }

    private void resubscribe() {
        if (!subscriptions.isEmpty()) {
            log.info("trying to re-subscription {} subscription(s)", subscriptions.size());
            this.subscriptions.forEach((identifier, subscriptionInfo) -> this.subscribe(identifier));
        }
    }

    private void handleMessage(String message) {
        try {
            JsonNode jsonNode = this.objectMapper.readTree(message);

            if (isResponse(jsonNode)) {
                SubscriptionResponse subscriptionResponse = this.objectMapper.treeToValue(jsonNode, SubscriptionResponse.class);
                String subscriptionIdentifier = subscriptionResponse.getMessage();

                if (!this.subscriptions.containsKey(subscriptionIdentifier)) {
                    log.warn("Unexpected message for subscriptionIdentifier={}: {}", subscriptionIdentifier, message);
                    return;
                }

                SubscriptionInfo subscriptionInfo = this.subscriptions.get(subscriptionIdentifier);

                if (Objects.equals(subscriptionResponse.getId(), subscriptionInfo.getSubscribeRequestId())) {
                    if (subscriptionResponse.isSuccess()) {
                        subscriptionInfo.setState(SubscriptionState.SUBSCRIBED);
                        log.info("subscription {} successfully subscribed", subscriptionIdentifier);
                    } else {
                        subscriptionInfo.setState(SubscriptionState.SUBSCRIBE_FAILED);
                        log.info("subscription {} failed with code={}", subscriptionIdentifier, subscriptionResponse.getCode());
                    }
                    return;
                }

                if (Objects.equals(subscriptionResponse.getId(), subscriptionInfo.getUnsubscribeRequestId())) {
                    if (subscriptionResponse.isSuccess()) {
                        subscriptions.remove(subscriptionIdentifier);
                        log.info("subscription {} successfully unsubscribed", subscriptionIdentifier);
                    } else {
                        subscriptionInfo.setState(SubscriptionState.UNSUBSCRIBE_FAILED);
                        log.info("unsubscribing to {} failed with code={}", subscriptionIdentifier, subscriptionResponse.getCode());
                    }
                    return;
                }
            }

            if (isOhlc(jsonNode)) {
                OhlcWrapper ohlcWrapper = this.objectMapper.readValue(message, OhlcWrapper.class);

                SubscriptionInfo subscriptionInfo = this.subscriptions.get(ohlcWrapper.getIdentifier());

                Ohlc mappedOhlc = this.mapper.map(subscriptionInfo.getCurrencyPair(), subscriptionInfo.getInterval(), ohlcWrapper);
                subscriptionInfo.getConsumer().accept(mappedOhlc);
                return;
            }

            log.warn("Unknown message received: {}", jsonNode);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isOhlc(JsonNode jsonNode) {
        return jsonNode.has("c");
    }

    private boolean isResponse(JsonNode jsonNode) {
        return jsonNode.has("code") && jsonNode.has("msg");
    }


    public void subscribe(CurrencyPair currencyPair, Interval interval, Consumer<Ohlc> callback) {

        String subscriptionIdentifier = mapper.calcSubscriptionIdentifier(currencyPair, interval);

        SubscriptionInfo subscriptionInfo = SubscriptionInfo.builder()
                .currencyPair(currencyPair)
                .interval(interval)
                .subscriptionIdentifier(subscriptionIdentifier)
                .state(SubscriptionState.INIT)
                .consumer(callback)
                .build();

        subscriptions.put(subscriptionIdentifier, subscriptionInfo);

        this.subscribe(subscriptionIdentifier);
    }

    private void subscribe(String subscriptionIdentifier) {
        int requestId = getNextId();
        this.subscriptions.get(subscriptionIdentifier).setSubscribeRequestId(requestId);
        this.send(new SubscriptionRequest(requestId, Method.SUBSCRIPTION, List.of(subscriptionIdentifier)));
    }


    public void unsubscribe(CurrencyPair currencyPair, Interval interval) {

        String subscriptionIdentifier = mapper.calcSubscriptionIdentifier(currencyPair, interval);

        this.unsubscribe(subscriptionIdentifier);
    }

    private void unsubscribe(String subscriptionIdentifier) {
        int requestId = getNextId();
        this.subscriptions.get(subscriptionIdentifier).setUnsubscribeRequestId(requestId);
        this.send(new SubscriptionRequest(requestId, Method.UNSUBSCRIPTION, List.of(subscriptionIdentifier)));
    }

    private void send(Object o) {
        String jsonString = asJsonString(o);

        this.webSocketClient.send(jsonString);
    }

    private String asJsonString(Object o) {
        try {
            return this.objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private int getNextId() {
        return this.requestIdCounter.incrementAndGet();
    }


    public void close() {

        if (!subscriptions.isEmpty()) {
            log.info("cancelling {} subscription(s) before closing...", subscriptions.size());
            this.subscriptions.forEach((identifier, subscriptionInfo) -> this.unsubscribe(identifier));
        }

        this.webSocketClient.close();
    }
}