package net.osslabz.mexc.client.ws.dto.raw;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
public class RawOrder extends RawBaseMessage {

    @JsonProperty("d")
    private OrderData data;

    @Data
    @NoArgsConstructor
    public static class OrderData {


        @JsonProperty("A")
        private Long remainAmount;

        @JsonProperty("o")
        private Long createTime;

        @JsonProperty("S")
        private Integer type;

        @JsonProperty("V")
        private BigDecimal remainQuantity;

        @JsonProperty("a")
        private BigDecimal amount;

        @JsonProperty("i")
        private String orderId;

        @JsonProperty("c")
        private String clientOrderId;

        @JsonProperty("m")
        private Integer isMaker;

        @JsonProperty("o")
        private Integer tradeType;

        @JsonProperty("p")
        private BigDecimal price;

        @JsonProperty("s")
        private Integer status;

        @JsonProperty("v")
        private BigDecimal quantity;

        @JsonProperty("ap")
        private BigDecimal avgPrice;

        @JsonProperty("cv")
        private BigDecimal cumulativeQuantity;

        @JsonProperty("ca")
        private BigDecimal cumulativeAmount;

        @JsonProperty("T")
        private Integer limitComparison;
    }
}