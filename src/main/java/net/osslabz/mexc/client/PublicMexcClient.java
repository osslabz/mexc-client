package net.osslabz.mexc.client;

import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.osslabz.crypto.CurrencyPair;
import net.osslabz.crypto.Interval;
import net.osslabz.crypto.Ohlc;
import net.osslabz.mexc.client.ws.dto.OhlcSubscriptionInfo;
import net.osslabz.mexc.client.ws.dto.SubscriptionInfo;
import net.osslabz.mexc.client.ws.dto.SubscriptionState;
import net.osslabz.mexc.proto.PushDataV3ApiWrapper;

@Slf4j
public class PublicMexcClient extends MexcClient {

    public PublicMexcClient() {
        this(BASE_URI);
    }

    PublicMexcClient(String baseUri) {
        super(baseUri);
    }

    public void subscribeToOhlc(CurrencyPair currencyPair, Interval interval, Consumer<Ohlc> callback) {

        String subscriptionIdentifier = mapper.calcSubscriptionIdentifier(currencyPair, interval);

        OhlcSubscriptionInfo subscriptionInfo = OhlcSubscriptionInfo.builder()
                .currencyPair(currencyPair)
                .interval(interval)
                .subscriptionIdentifier(subscriptionIdentifier)
                .state(SubscriptionState.INIT)
                .consumer(callback)
                .build();

        this.subscribe(subscriptionInfo);
    }

    public void unsubscribeFromOhlc(CurrencyPair currencyPair, Interval interval) {
        String subscriptionIdentifier = mapper.calcSubscriptionIdentifier(currencyPair, interval);
        this.unsubscribe(subscriptionIdentifier);
    }

    @Override
    public Object doHandleMessage(SubscriptionInfo subscriptionInfo, PushDataV3ApiWrapper push) {
        if (subscriptionInfo instanceof OhlcSubscriptionInfo ohlcSubscriptionInfo && push.hasPublicSpotKline()) {
            Ohlc ohlc =
                    this.mapper.map(ohlcSubscriptionInfo.getCurrencyPair(), ohlcSubscriptionInfo.getInterval(), push);
            log.trace("Mapped OHLC: {}", ohlc);
            return ohlc;
        }
        return null;
    }
}
