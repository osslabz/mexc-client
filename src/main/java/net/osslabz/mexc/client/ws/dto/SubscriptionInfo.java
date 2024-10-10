package net.osslabz.mexc.client.ws.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.function.Consumer;

@Data
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionInfo {

    private String subscriptionIdentifier;

    private Integer subscribeRequestId;

    private Integer unsubscribeRequestId;

    private SubscriptionState state;

    private Consumer consumer;
}