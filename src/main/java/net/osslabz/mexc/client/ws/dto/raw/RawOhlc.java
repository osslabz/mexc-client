package net.osslabz.mexc.client.ws.dto.raw;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
public class RawOhlc extends RawBaseMessage {

    @JsonProperty("d")
    private OhlData data;

    @Data
    @NoArgsConstructor
    public class OhlData {

        @JsonProperty("k")
        private OhlcContent content;

        @Data
        @NoArgsConstructor
        public class OhlcContent {

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