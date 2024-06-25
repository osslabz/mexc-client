package net.osslabz.mexc.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
class OhlcWrapper {

    @JsonProperty("c")
    private String identifier;

    @JsonProperty("s")
    private String symbol;

    @JsonProperty("t")
    private long time;

    @JsonProperty("d")
    private OhlData data;

    @Data
    @NoArgsConstructor
    class OhlData {

        @JsonProperty("k")
        private OhlcContent content;

        @Data
        @NoArgsConstructor
        class OhlcContent {

            @JsonProperty("t")
            private Long openTime;

            @JsonProperty("T")
            private Long closeTime;

            @JsonProperty("o")
            private BigDecimal openPrice;

            @JsonProperty("h")
            private BigDecimal highPrice;

            @JsonProperty("l")
            private BigDecimal lowPrice;

            @JsonProperty("c")
            private BigDecimal closePrice;

            @JsonProperty("a")
            private BigDecimal volume;

            @JsonProperty("v")
            private BigDecimal quantity;

        }
    }
}