package net.osslabz.mexc.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import net.osslabz.crypto.CurrencyPair;
import net.osslabz.crypto.Interval;
import net.osslabz.crypto.Ohlc;
import net.osslabz.mexc.client.ws.dto.OhlcSubscriptionInfo;
import net.osslabz.mexc.client.ws.dto.SubscriptionInfo;
import net.osslabz.mexc.client.ws.dto.SubscriptionState;
import net.osslabz.mexc.client.ws.dto.raw.RawOhlc;

import java.util.function.Consumer;

@Slf4j
public class PublicMexcClient extends MexcClient {

    public PublicMexcClient() {
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


    public Object doHandleMessage(SubscriptionInfo subscriptionInfo, JsonNode jsonNode) {
        if (isOhlc(subscriptionInfo, jsonNode)) {
            return processOhlcMessage((OhlcSubscriptionInfo) subscriptionInfo, jsonNode);
        }
        return null;
    }


    private boolean isOhlc(SubscriptionInfo subscriptionInfo, JsonNode jsonNode) {
        return subscriptionInfo.getSubscriptionIdentifier().startsWith("spot@public.kline.v3.api");
    }


    private Ohlc processOhlcMessage(OhlcSubscriptionInfo subscriptionInfo, JsonNode jsonNode) {

        try {
            RawOhlc rawOhlc = this.objectMapper.treeToValue(jsonNode, RawOhlc.class);
            log.trace("OHLC from exchange: {}", rawOhlc);
            Ohlc mappedOhlc = this.mapper.map(subscriptionInfo.getCurrencyPair(), subscriptionInfo.getInterval(), rawOhlc);
            log.trace("Mapped OHLC: {}", mappedOhlc);
            return mappedOhlc;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}