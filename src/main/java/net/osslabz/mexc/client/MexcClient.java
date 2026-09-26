package net.osslabz.mexc.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.Closeable;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import net.osslabz.mexc.client.ws.MexcWebSocketClient;
import net.osslabz.mexc.client.ws.WebSocketListener;
import net.osslabz.mexc.client.ws.dto.Method;
import net.osslabz.mexc.client.ws.dto.SubscriptionCommand;
import net.osslabz.mexc.client.ws.dto.SubscriptionCommandResponse;
import net.osslabz.mexc.client.ws.dto.SubscriptionInfo;
import net.osslabz.mexc.client.ws.dto.SubscriptionState;
import net.osslabz.mexc.proto.PushDataV3ApiWrapper;
import org.java_websocket.exceptions.WebsocketNotConnectedException;

@Slf4j
public abstract class MexcClient implements Closeable {

    protected static final String BASE_URI = "wss://wbs-api.mexc.com/ws";

    protected final ObjectMapper objectMapper;

    protected final MexcMapper mapper = new MexcMapper();

    protected final Map<String, SubscriptionInfo> activeSubscriptions = new ConcurrentHashMap<>();

    private final AtomicInteger requestIdCounter = new AtomicInteger(0);

    final String baseUri;

    protected String uri;

    private final Duration pingInterval;

    private MexcWebSocketClient webSocketClient;

    public MexcClient() {
        this(BASE_URI);
    }

    MexcClient(String baseUri) {
        this(baseUri, MexcWebSocketClient.PING_INTERVAL);
    }

    MexcClient(String baseUri, Duration pingInterval) {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.baseUri = baseUri;
        this.uri = baseUri;
        this.pingInterval = pingInterval;
    }

    private void initWebSocketClient() {

        // The listener resubscribes through the client that opened, so a closed client never builds a new one.
        AtomicReference<MexcWebSocketClient> opening = new AtomicReference<>();
        this.webSocketClient =
                new MexcWebSocketClient(URI.create(this.uri), this.pingInterval, new WebSocketListener() {
                    @Override
                    public void onOpen() {
                        resubscribe(opening.get());
                    }

                    @Override
                    public void onMessage(String message) {
                        log.trace("Received message: {}", message);
                        handleMessage(message);
                    }

                    @Override
                    public void onMessage(ByteBuffer bytes) {
                        handlePush(bytes);
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
        opening.set(this.webSocketClient);
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

    private void resubscribe(MexcWebSocketClient client) {
        if (!activeSubscriptions.isEmpty()) {
            log.info("Trying to (re-)subscribe {} subscription(s)", activeSubscriptions.size());
            try {
                this.activeSubscriptions.forEach(
                        (identifier, ohlcSubscriptionInfo) -> this.sendSubscription(client, ohlcSubscriptionInfo));
            } catch (WebsocketNotConnectedException e) {
                log.debug("Stopped resubscribing, the connection closed meanwhile");
            }
        }
    }

    // Commands and their answers are JSON text frames; the data itself arrives as binary protobuf pushes.
    private void handleMessage(String message) {
        try {
            JsonNode jsonNode = this.objectMapper.readTree(message);

            if (this.isPong(jsonNode)) {
                log.trace("Received PONG");
                return;
            }
            if (this.isSubscriptionCommandResponse(jsonNode)) {
                this.processSubscriptionCommandResponse(jsonNode);
                return;
            }
            log.warn("Received a text message that answers no command, won't be processed: {}", message);

        } catch (JsonProcessingException e) {
            throw new MexcClientException(e);
        }
    }

    private void handlePush(ByteBuffer bytes) {
        PushDataV3ApiWrapper push;
        try {
            push = PushDataV3ApiWrapper.parseFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            log.warn("Received a push that is no protobuf message, won't be processed: {}", e.getMessage());
            return;
        }
        log.trace("Received push: {}", push);

        SubscriptionInfo subscriptionInfo = this.activeSubscriptions.get(push.getChannel());
        if (subscriptionInfo == null) {
            log.warn("Received a push for an unmanaged channel, won't be processed: {}", push.getChannel());
            return;
        }

        Object mapped = this.doHandleMessage(subscriptionInfo, push);
        if (mapped == null) {
            log.warn(
                    "Received a push with an unexpected {} body on {}, won't be processed",
                    push.getBodyCase(),
                    push.getChannel());
            return;
        }

        subscriptionInfo.getConsumer().accept(mapped);
    }

    /** Maps a push of a managed channel, or returns null if the push carries nothing this client maps. */
    protected abstract Object doHandleMessage(SubscriptionInfo subscriptionInfo, PushDataV3ApiWrapper push);

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

    private boolean isPong(JsonNode jsonNode) {
        return "PONG".equals(jsonNode.path("msg").asText());
    }

    private boolean isSubscriptionCommandResponse(JsonNode jsonNode) {
        return jsonNode.has("code") && jsonNode.has("msg");
    }

    protected void subscribe(SubscriptionInfo subscriptionInfo) {
        activeSubscriptions.put(subscriptionInfo.getSubscriptionIdentifier(), subscriptionInfo);

        MexcWebSocketClient client = this.getWebSocketClient();
        if (client.isOpen()) {
            this.sendSubscription(client, subscriptionInfo);
        } else {
            // Opening subscribes every active subscription, this one included; MEXC answers a second request with "".
            client.open();
        }
    }

    private void sendSubscription(MexcWebSocketClient client, SubscriptionInfo subscriptionInfo) {
        int requestId = this.getNextRequestId();
        subscriptionInfo.setSubscribeRequestId(requestId);

        client.send(asJsonString(new SubscriptionCommand(
                requestId, Method.SUBSCRIPTION, List.of(subscriptionInfo.getSubscriptionIdentifier()))));
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
