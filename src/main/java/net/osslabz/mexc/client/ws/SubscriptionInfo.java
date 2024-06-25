package net.osslabz.mexc.client.ws;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import net.osslabz.crypto.CurrencyPair;
import net.osslabz.crypto.Interval;

import java.util.function.Consumer;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionInfo {

    private CurrencyPair currencyPair;

    private Interval interval;

    private String subscriptionIdentifier;

    @Setter
    private Integer subscribeRequestId;

    @Setter
    private Integer unsubscribeRequestId;

    @Setter
    private SubscriptionState state;


    private Consumer consumer;
}