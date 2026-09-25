package net.osslabz.mexc.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Closeable;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import net.osslabz.mexc.client.ws.MexcWebSocketClient;
import net.osslabz.mexc.client.ws.WebSocketListener;
import net.osslabz.mexc.client.ws.dto.Method;
import net.osslabz.mexc.client.ws.dto.SubscriptionCommand;
import net.osslabz.mexc.client.ws.dto.SubscriptionCommandResponse;
import net.osslabz.mexc.client.ws.dto.SubscriptionInfo;
import net.osslabz.mexc.client.ws.dto.SubscriptionState;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public abstract class MexcClient implements Closeable {

    protected static final String BASE_URI = "wss://wbs.mexc.com/ws";

    protected final ObjectMapper objectMapper;

    protected final MexcMapper mapper = new MexcMapper();

    protected final Map<String, SubscriptionInfo> activeSubscriptions = new ConcurrentHashMap<>();

    private final AtomicInteger requestIdCounter = new AtomicInteger(0);

    final String baseUri;

    protected String uri;

    private MexcWebSocketClient webSocketClient;

    public MexcClient() {
        this(BASE_URI);
    }

    MexcClient(String baseUri) {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.baseUri = baseUri;
        this.uri = baseUri;
    }

    private void initWebSocketClient() {

        try {
            this.webSocketClient = new MexcWebSocketClient(new URI(this.uri), new WebSocketListener() {
                @Override
                public void onOpen() {
                    resubscribe();
                }

                @Override
                public void onMessage(String message) {
                    log.trace("Received message: {}", message);
                    handleMessage(message);
                }

                @Override
                public void onMessage(ByteBuffer bytes) {
                    throw new IllegalArgumentException("Not implemented");
                }

                @Override
                public void onError(Exception e) {
                    // MexcWebSocketClient logs it, and its reconnect monitor restores a dropped connection.
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    // MexcWebSocketClient logs it, and its reconnect monitor restores a dropped connection.
                }
            });
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        closeConnection();
    }

    private void closeConnection() {

        synchronized (this.objectMapper) {
            if (this.webSocketClient == null) {
                return;
            }
        }

        if (!activeSubscriptions.isEmpty()) {
            log.info("Cancelling {} subscription(s) before closing...", activeSubscriptions.size());
            this.activeSubscriptions.forEach((identifier, ohlcSubscriptionInfo) -> {
                try {
                    this.unsubscribe(identifier);
                } catch (Exception e) {
                    log.warn("Couldn't unsubscribe {}. Error: {}", identifier, e.getMessage());
                }
            });
        }

        // Confirming the last unsubscription closes the client from the read thread, possibly while this runs.
        MexcWebSocketClient closing;
        synchronized (this.objectMapper) {
            closing = this.webSocketClient;
            this.webSocketClient = null;
        }
        if (closing != null) {
            closing.close();
        }
    }

    private void resubscribe() {
        if (!activeSubscriptions.isEmpty()) {
            log.info("Trying to (re-)subscribe {} subscription(s)", activeSubscriptions.size());
            this.activeSubscriptions.forEach(
                    (identifier, ohlcSubscriptionInfo) -> this.subscribe(ohlcSubscriptionInfo));
        }
    }

    protected String getIdentifier(JsonNode jsonNode) {

        if (jsonNode.has("c")
                && jsonNode.get("c") != null
                && StringUtils.isNotBlank(jsonNode.get("c").asText())) {
            return jsonNode.get("c").asText();
        }
        return null;
    }

    private void handleMessage(String message) {
        try {
            JsonNode jsonNode = this.objectMapper.readTree(message);

            if (this.isSubscriptionCommandResponse(jsonNode)) {
                this.processSubscriptionCommandResponse(jsonNode);
                return;
            }

            String identifier = this.getIdentifier(jsonNode);
            if (identifier == null) {
                log.warn("Received a message without an identifier, won't be processed: {}", message);
                return;
            }

            SubscriptionInfo subscriptionInfo = this.activeSubscriptions.get(identifier);
            if (subscriptionInfo == null) {
                log.warn("Received a message without an unmanaged identifier, won't be processed: {}", message);
                return;
            }

            Object mapped = this.doHandleMessage(subscriptionInfo, jsonNode);
            if (mapped == null) {
                log.warn("Unknown message received that won't be processed: {}", jsonNode);
            }

            subscriptionInfo.getConsumer().accept(mapped);

        } catch (JsonProcessingException e) {
            throw new MexcClientException(e);
        }
    }

    protected abstract Object doHandleMessage(SubscriptionInfo subscriptionInfo, JsonNode jsonNode);

    private void processSubscriptionCommandResponse(JsonNode jsonNode) throws JsonProcessingException {

        SubscriptionCommandResponse response =
                this.objectMapper.treeToValue(jsonNode, SubscriptionCommandResponse.class);
        int requestId = response.getId();

        SubscriptionInfo subscriptionInfo = this.activeSubscriptions.values().stream()
                .filter(subscription -> Objects.equals(requestId, subscription.getSubscribeRequestId())
                        || Objects.equals(requestId, subscription.getUnsubscribeRequestId()))
                .findAny()
                .orElse(null);
        if (subscriptionInfo == null) {
            // A reconnect resubscribes under a new id, so answers to the old one end up here.
            log.debug("Ignoring an answer to request id={} that no subscription awaits: {}", requestId, jsonNode);
            return;
        }

        String subscriptionIdentifier = subscriptionInfo.getSubscriptionIdentifier();
        // MEXC refuses a channel with code 0 too; only an answer naming the channel confirms the request.
        boolean confirmed = response.isSuccess() && subscriptionIdentifier.equals(response.getMessage());

        if (Objects.equals(requestId, subscriptionInfo.getSubscribeRequestId())) {
            if (confirmed) {
                subscriptionInfo.setState(SubscriptionState.SUBSCRIBED);
                log.info("Subscription {} successfully subscribed", subscriptionIdentifier);
            } else {
                subscriptionInfo.setState(SubscriptionState.SUBSCRIBE_FAILED);
                log.warn(
                        "Subscribing to {} failed with code={}: {}",
                        subscriptionIdentifier,
                        response.getCode(),
                        response.getMessage());
            }
            return;
        }

        if (confirmed) {
            activeSubscriptions.remove(subscriptionIdentifier);
            log.info("Subscription {} successfully unsubscribed", subscriptionIdentifier);
            if (this.activeSubscriptions.isEmpty()) {
                log.info("No open subscriptions, closing connection.");
                this.closeConnection();
            }
        } else {
            subscriptionInfo.setState(SubscriptionState.UNSUBSCRIBE_FAILED);
            log.warn(
                    "Unsubscribing from {} failed with code={}: {}",
                    subscriptionIdentifier,
                    response.getCode(),
                    response.getMessage());
        }
    }

    private boolean isSubscriptionCommandResponse(JsonNode jsonNode) {
        return jsonNode.has("code") && jsonNode.has("msg");
    }

    protected void subscribe(SubscriptionInfo subscriptionInfo) {
        int requestId = this.getNextRequestId();
        subscriptionInfo.setSubscribeRequestId(requestId);
        activeSubscriptions.put(subscriptionInfo.getSubscriptionIdentifier(), subscriptionInfo);

        this.send(new SubscriptionCommand(
                requestId, Method.SUBSCRIPTION, List.of(subscriptionInfo.getSubscriptionIdentifier())));
    }

    protected void unsubscribe(String subscriptionIdentifier) {
        int requestId = this.getNextRequestId();
        this.activeSubscriptions.get(subscriptionIdentifier).setUnsubscribeRequestId(requestId);
        this.send(new SubscriptionCommand(requestId, Method.UNSUBSCRIPTION, List.of(subscriptionIdentifier)));
    }

    private int getNextRequestId() {
        return this.requestIdCounter.incrementAndGet();
    }

    private void send(Object o) {
        String jsonString = asJsonString(o);

        this.getWebSocketClient().send(jsonString);
    }

    private MexcWebSocketClient getWebSocketClient() {
        synchronized (this.objectMapper) {
            if (this.webSocketClient == null) {
                initWebSocketClient();
            }
            return this.webSocketClient;
        }
    }

    private String asJsonString(Object o) {
        try {
            return this.objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
