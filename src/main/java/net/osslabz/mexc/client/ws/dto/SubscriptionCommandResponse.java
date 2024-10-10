package net.osslabz.mexc.client.ws.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionCommandResponse {

    private int id;

    private int code = -1;

    @JsonProperty("msg")
    private String message;

    public boolean isSuccess() {
        return code == 0;
    }

}