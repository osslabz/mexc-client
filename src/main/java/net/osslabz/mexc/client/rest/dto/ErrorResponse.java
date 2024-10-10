package net.osslabz.mexc.client.rest.dto;

import lombok.Data;

@Data
public class ErrorResponse {

    private String code;

    private String msg;
}