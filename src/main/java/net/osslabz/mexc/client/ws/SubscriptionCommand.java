package net.osslabz.mexc.client.ws;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionCommand {

    private Integer id;

    private Method method;

    private List<String> params;

}