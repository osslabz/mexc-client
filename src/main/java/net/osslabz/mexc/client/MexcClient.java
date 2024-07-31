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
import net.osslabz.mexc.client.ws.SubscriptionCommand;
import net.osslabz.mexc.client.ws.SubscriptionCommandResponse;
import net.osslabz.mexc.client.ws.SubscriptionInfo;
import net.osslabz.mexc.client.ws.SubscriptionState;
import net.osslabz.mexc.client.ws.WebSocketListener;

import java.io.Closeable;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Slf4j
public class MexcClient implements Closeable {

    private final ObjectMapper objectMapper;

    private final MexcWebSocketClient webSocketClient;

    private final MexcMapper mapper = new MexcMapper();

    private final Map<String, SubscriptionInfo> activeSubscriptions = new ConcurrentHashMap<>();

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
    }


    @Override
    public void close() {

        if (!activeSubscriptions.isEmpty()) {
            log.info("Cancelling {} subscription(s) before closing...", activeSubscriptions.size());
            this.activeSubscriptions.forEach((identifier, subscriptionInfo) -> {
                try {
                    this.unsubscribe(identifier);
                } catch (Exception e) {
                    log.warn("Couldn't unsubscribe {}. Error: {}", identifier, e.getMessage());
                }
            });
        }

        this.webSocketClient.close();
    }


    private void resubscribe() {
        if (!activeSubscriptions.isEmpty()) {
            log.info("Trying to re-subscription {} subscription(s)", activeSubscriptions.size());
            this.activeSubscriptions.forEach((identifier, subscriptionInfo) -> this.subscribe(identifier));
        }
    }


    private void handleMessage(String message) {
        try {
            JsonNode jsonNode = this.objectMapper.readTree(message);

            if (this.isSubscriptionCommandResponse(jsonNode)) {
                this.processSubscriptionCommandResponse(jsonNode);
                return;
            }

            if (isOhlc(jsonNode)) {
                processOhlc(message);
                return;
            }

            log.warn("Unknown message received: {}", jsonNode);
        } catch (JsonProcessingException e) {
            throw new MexcClientException(e);
        }
    }


    private void processSubscriptionCommandResponse(JsonNode jsonNode) throws JsonProcessingException {

        SubscriptionCommandResponse subscriptionCommandResponse = this.objectMapper.treeToValue(jsonNode, SubscriptionCommandResponse.class);
        String subscriptionIdentifier = subscriptionCommandResponse.getMessage();

        if (!this.activeSubscriptions.containsKey(subscriptionIdentifier)) {
            log.warn("Unexpected message for subscriptionIdentifier={}: {}", subscriptionIdentifier, jsonNode);
            return;
        }

        SubscriptionInfo subscriptionInfo = this.activeSubscriptions.get(subscriptionIdentifier);

        if (Objects.equals(subscriptionCommandResponse.getId(), subscriptionInfo.getSubscribeRequestId())) {
            if (subscriptionCommandResponse.isSuccess()) {
                subscriptionInfo.setState(SubscriptionState.SUBSCRIBED);
                log.info("Subscription {} successfully subscribed", subscriptionIdentifier);
            } else {
                subscriptionInfo.setState(SubscriptionState.SUBSCRIBE_FAILED);
                log.warn("Subscribing to {} failed with code={}", subscriptionIdentifier, subscriptionCommandResponse.getCode());
            }
            return;
        }

        if (Objects.equals(subscriptionCommandResponse.getId(), subscriptionInfo.getUnsubscribeRequestId())) {
            if (subscriptionCommandResponse.isSuccess()) {
                activeSubscriptions.remove(subscriptionIdentifier);
                log.info("Subscription {} successfully unsubscribed", subscriptionIdentifier);
                if (this.activeSubscriptions.isEmpty()) {
                    log.info("No open subscriptions, closing connection.");
                    this.webSocketClient.close();
                }
            } else {
                subscriptionInfo.setState(SubscriptionState.UNSUBSCRIBE_FAILED);
                log.warn("Unsubscribing from {} failed with code={}", subscriptionIdentifier, subscriptionCommandResponse.getCode());
            }
        }
    }


    private void processOhlc(String message) throws JsonProcessingException {

        OhlcWrapper ohlcWrapper = this.objectMapper.readValue(message, OhlcWrapper.class);

        SubscriptionInfo subscriptionInfo = this.activeSubscriptions.get(ohlcWrapper.getIdentifier());

        Ohlc mappedOhlc = this.mapper.map(subscriptionInfo.getCurrencyPair(), subscriptionInfo.getInterval(), ohlcWrapper);

        subscriptionInfo.getConsumer().accept(mappedOhlc);
    }


    private boolean isOhlc(JsonNode jsonNode) {
        return jsonNode.has("c");
    }


    private boolean isSubscriptionCommandResponse(JsonNode jsonNode) {
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

        activeSubscriptions.put(subscriptionIdentifier, subscriptionInfo);

        this.subscribe(subscriptionIdentifier);
    }


    private void subscribe(String subscriptionIdentifier) {
        int requestId = this.getNextRequestId();
        this.activeSubscriptions.get(subscriptionIdentifier).setSubscribeRequestId(requestId);
        this.send(new SubscriptionCommand(requestId, Method.SUBSCRIPTION, List.of(subscriptionIdentifier)));
    }


    public void unsubscribe(CurrencyPair currencyPair, Interval interval) {
        String subscriptionIdentifier = mapper.calcSubscriptionIdentifier(currencyPair, interval);
        this.unsubscribe(subscriptionIdentifier);
    }

    private void unsubscribe(String subscriptionIdentifier) {
        int requestId = this.getNextRequestId();
        this.activeSubscriptions.get(subscriptionIdentifier).setUnsubscribeRequestId(requestId);
        this.send(new SubscriptionCommand(requestId, Method.UNSUBSCRIPTION, List.of(subscriptionIdentifier)));
    }


    private int getNextRequestId() {
        return this.requestIdCounter.incrementAndGet();
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
}