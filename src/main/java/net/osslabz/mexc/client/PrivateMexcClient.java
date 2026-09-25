package net.osslabz.mexc.client;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.osslabz.crypto.Order;
import net.osslabz.mexc.client.rest.UserDataClient;
import net.osslabz.mexc.client.ws.dto.SubscriptionInfo;
import net.osslabz.mexc.client.ws.dto.SubscriptionState;
import net.osslabz.mexc.proto.PushDataV3ApiWrapper;

@Slf4j
public class PrivateMexcClient extends MexcClient {

    private static final String ORDER_SUBSCRIPTION_IDENTIFIER = "spot@private.orders.v3.api.pb";

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
    protected Object doHandleMessage(SubscriptionInfo subscriptionInfo, PushDataV3ApiWrapper push) {
        if (isOrder(subscriptionInfo) && push.hasPrivateOrders()) {
            Order order = this.mapper.map(push);
            log.trace("Mapped order: {}", order);
            return order;
        }
        return null;
    }

    private boolean isOrder(SubscriptionInfo subscriptionInfo) {
        return ORDER_SUBSCRIPTION_IDENTIFIER.equals(subscriptionInfo.getSubscriptionIdentifier());
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
