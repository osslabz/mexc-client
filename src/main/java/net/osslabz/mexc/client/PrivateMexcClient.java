package net.osslabz.mexc.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import net.osslabz.crypto.Ohlc;
import net.osslabz.crypto.Order;
import net.osslabz.mexc.client.rest.UserDataClient;
import net.osslabz.mexc.client.ws.dto.SubscriptionInfo;
import net.osslabz.mexc.client.ws.dto.SubscriptionState;
import net.osslabz.mexc.client.ws.dto.raw.RawOrder;

import java.util.List;
import java.util.function.Consumer;

@Slf4j
public class PrivateMexcClient extends MexcClient {

    private static final String ORDER_SUBSCRIPTION_IDENTIFIER = "spot@private.orders.v3.api";

    private final UserDataClient userDataClient;


    public PrivateMexcClient(String accessKey, String secretKey) {
        this.userDataClient = new UserDataClient(accessKey, secretKey);
    }


    public void subscribeToOrders(Consumer<Ohlc> callback) {

        String listenKey = this.getActiveListenKey();
        this.uri = BASE_URI + "?listenKey=" + listenKey;

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


    protected Object doHandleMessage(SubscriptionInfo subscriptionInfo, JsonNode jsonNode) {

        if (isOrder(subscriptionInfo, jsonNode)) {
            return processOrderMessage(subscriptionInfo, jsonNode);
        }
        return null;
    }


    private boolean isOrder(SubscriptionInfo subscriptionInfo, JsonNode jsonNode) {
        return subscriptionInfo.getSubscriptionIdentifier().equals(ORDER_SUBSCRIPTION_IDENTIFIER);
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