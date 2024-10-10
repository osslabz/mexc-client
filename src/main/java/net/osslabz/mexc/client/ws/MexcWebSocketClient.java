package net.osslabz.mexc.client.ws;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MexcWebSocketClient extends WebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(MexcWebSocketClient.class);
    private final WebSocketListener listener;

    private final Object lock = new Object();
    private volatile boolean reconnectMonitorStarted = false;

    private volatile boolean connected = false;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        thread.setName("reconnect-monitor");
        return thread;
    });


    public MexcWebSocketClient(URI serverURI, WebSocketListener webSocketListener) {
        super(serverURI);
        this.setConnectionLostTimeout(5);
        this.listener = webSocketListener;
    }

    @Override
    public void onOpen(ServerHandshake serverHandshake) {
        log.debug("New connection opened");
        this.listener.onOpen();
        this.startMonitoringThread();
    }


    private void startMonitoringThread() {

        if (!this.reconnectMonitorStarted) {
            log.debug("Starting re-reconnect monitor thread...");
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    if (!this.isOpen()) {
                        log.debug("Trying to reconnect...");
                        reconnectBlocking();
                    }
                } catch (Exception e) {
                    log.debug("Couldn't reconnect connection (message={}), will try again!", e.getMessage());
                }
            }, 1, 3, TimeUnit.SECONDS);
            this.reconnectMonitorStarted = true;
        }

    }


    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.info("connection closed with code={}, reason={}. Was remotely closed={}", code, reason, remote);
        this.listener.onClose(code, reason, remote);
    }

    @Override
    public void onMessage(String message) {
        log.trace("received message={}", message);
        this.listener.onMessage(message);
    }


    @Override
    public void onMessage(ByteBuffer bytes) {
        log.trace("received binary message={}", bytes);
        this.listener.onMessage(bytes);
    }


    @Override
    public void onError(Exception e) {
        log.warn("connection error with message={}", e.getMessage());
        this.listener.onError(e);
    }

    @Override
    public void send(String message) {
        if (!this.isOpen()) {
            synchronized (lock) {
                start();
            }
        }
        log.trace("Sending message={}", message);
        super.send(message);
    }

    private void start() {
        try {
            log.info("Opening connection...");
            this.connected = this.connectBlocking();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        this.scheduler.shutdown();
        super.close();
        this.reconnectMonitorStarted = false;
    }

    boolean isConnected() {
        return this.connected;
    }

    public boolean isConnectionAlive() {
        return this.isConnected() && this.isOpen();
    }
}