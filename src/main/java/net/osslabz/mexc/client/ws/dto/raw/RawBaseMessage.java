package net.osslabz.mexc.client.ws.dto.raw;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class RawBaseMessage {

    @JsonProperty("c")
    private String identifier;

    @JsonProperty("s")
    private String symbol;

    @JsonProperty("t")
    private long time;

}