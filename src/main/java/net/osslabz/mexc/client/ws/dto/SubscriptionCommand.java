package net.osslabz.mexc.client.ws.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionCommand {

    private Integer id;

    private Method method;

    private List<String> params;
}
