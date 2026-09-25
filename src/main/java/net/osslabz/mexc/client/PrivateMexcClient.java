package net.osslabz.mexc.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.osslabz.crypto.Order;
import net.osslabz.mexc.client.rest.UserDataClient;
import net.osslabz.mexc.client.ws.dto.SubscriptionInfo;
import net.osslabz.mexc.client.ws.dto.SubscriptionState;
import net.osslabz.mexc.client.ws.dto.raw.RawOrder;

@Slf4j
public class PrivateMexcClient extends MexcClient {

    private static final String ORDER_SUBSCRIPTION_IDENTIFIER = "spot@private.orders.v3.api";

    private final UserDataClient userDataClient;

    private final AtomicBoolean closed = new AtomicBoolean();

    public PrivateMexcClient(String accessKey, String secretKey) {
        this(BASE_URI, new UserDataClient(accessKey, secretKey));
    }

    PrivateMexcClient(String baseUri, UserDataClient userDataClient) {
        super(baseUri);
        this.userDataClient = userDataClient;
    }

    public void subscribeToOrders(Consumer<Order> callback) {
        if (closed.get()) {
            throw new IllegalStateException("The client is closed");
        }

        String listenKey = this.getActiveListenKey();
        this.uri = this.baseUri + "?listenKey=" + listenKey;

        SubscriptionInfo subscriptionInfo = SubscriptionInfo.builder()
                .subscriptionIdentifier(ORDER_SUBSCRIPTION_IDENTIFIER)
                .state(SubscriptionState.INIT)
                .consumer(callback)
                .build();

        this.subscribe(subscriptionInfo);
    }

    public void unsubscribeFromOrders() {
        this.unsubscribe(ORDER_SUBSCRIPTION_IDENTIFIER);
    }

    /** Closes the connection and stops the listen key keep-alive; the client can't subscribe again afterwards. */
    @Override
    public void close() {
        closed.set(true);
        try {
            super.close();
        } finally {
            userDataClient.close();
        }
    }

    @Override
    protected Object doHandleMessage(SubscriptionInfo subscriptionInfo, JsonNode jsonNode) {

        if (isOrder(subscriptionInfo)) {
            return processOrderMessage(subscriptionInfo, jsonNode);
        }
        return null;
    }

    private boolean isOrder(SubscriptionInfo subscriptionInfo) {
        return ORDER_SUBSCRIPTION_IDENTIFIER.equals(subscriptionInfo.getSubscriptionIdentifier());
    }

    private Order processOrderMessage(SubscriptionInfo subscriptionInfo, JsonNode jsonNode) {

        try {
            RawOrder rawOrder = this.objectMapper.treeToValue(jsonNode, RawOrder.class);
            log.trace("Order from exchange: {}", rawOrder);
            Order order = this.mapper.map(subscriptionInfo, rawOrder);
            log.trace("Mapped order: {}", order);
            return order;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private String getActiveListenKey() {
        String listenKey = null;
        List<String> listenKeys = userDataClient.getListenKeys();
        if (listenKeys.isEmpty()) {
            listenKey = userDataClient.createListenKey();
        } else {
            listenKey = listenKeys.get(0);
        }
        return listenKey;
    }
}
