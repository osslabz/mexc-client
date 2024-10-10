package net.osslabz.mexc.client.ws.dto;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import net.osslabz.crypto.CurrencyPair;
import net.osslabz.crypto.Interval;

@Getter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class OhlcSubscriptionInfo extends SubscriptionInfo {

    private CurrencyPair currencyPair;

    private Interval interval;
}