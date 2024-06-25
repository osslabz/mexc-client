package net.osslabz.mexc.client.ws;

import java.nio.ByteBuffer;

public interface WebSocketListener {

    void onOpen();
    void onMessage(String message);

    void onMessage(ByteBuffer bytes);

    void onError(Exception e);

    void onClose(int code, String reason, boolean remote);
}