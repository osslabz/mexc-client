package net.osslabz.mexc.client.ws;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MexcWebSocketClient extends WebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(MexcWebSocketClient.class);

    /** MEXC closes a connection that carries no data for 60 seconds; its PING command counts as data. */
    public static final Duration PING_INTERVAL = Duration.ofSeconds(20);

    private static final String PING = "{\"method\":\"PING\"}";

    private final WebSocketListener listener;

    private final Object lock = new Object();

    // Guarded by lock.
    private boolean connectAttempted;

    private final AtomicBoolean spent = new AtomicBoolean();

    private final Object schedulerLock = new Object();
    private final AtomicReference<ScheduledFuture<?>> reconnectMonitor = new AtomicReference<>();

    private final AtomicReference<ScheduledFuture<?>> pinger = new AtomicReference<>();

    private final AtomicBoolean connected = new AtomicBoolean();

    private final AtomicBoolean closed = new AtomicBoolean();

    private final AtomicReference<Thread> monitorThread = new AtomicReference<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        thread.setName("reconnect-monitor");
        monitorThread.set(thread);
        return thread;
    });

    private final Duration pingInterval;

    public MexcWebSocketClient(URI serverURI, WebSocketListener webSocketListener) {
        this(serverURI, PING_INTERVAL, webSocketListener);
    }

    public MexcWebSocketClient(URI serverURI, Duration pingInterval, WebSocketListener webSocketListener) {
        super(serverURI);
        this.setConnectionLostTimeout(5);
        this.listener = webSocketListener;
        this.pingInterval = pingInterval;
    }

    @Override
    public void onOpen(ServerHandshake serverHandshake) {
        if (this.closed.get()) {
            // A reconnect that close() came too early to stop.
            super.close();
            return;
        }
        log.debug("New connection opened");
        this.listener.onOpen();
        this.startMonitoringThread();
    }

    private void startMonitoringThread() {

        // close() shuts the scheduler down under this lock, so nothing gets scheduled on a shut-down scheduler.
        synchronized (this.schedulerLock) {
            if (this.closed.get() || this.reconnectMonitor.get() != null) {
                return;
            }
            log.debug("Starting re-reconnect monitor thread...");
            this.reconnectMonitor.set(scheduler.scheduleWithFixedDelay(
                    () -> {
                        if (this.closed.get() || this.isOpen()) {
                            return;
                        }
                        try {
                            log.debug("Trying to reconnect...");
                            reconnectBlocking();
                        } catch (Exception e) {
                            log.debug("Couldn't reconnect connection (message={}), will try again!", e.getMessage());
                        }
                        // close() may have run after onOpen checked for it.
                        if (this.closed.get()) {
                            super.close();
                        }
                    },
                    1,
                    3,
                    TimeUnit.SECONDS));
            this.pinger.set(scheduler.scheduleAtFixedRate(
                    this::ping, pingInterval.toMillis(), pingInterval.toMillis(), TimeUnit.MILLISECONDS));
        }
    }

    // A websocket ping frame doesn't count as data for MEXC; only its PING command does.
    private void ping() {
        if (this.isOpen()) {
            try {
                log.trace("Sending message={}", PING);
                super.send(PING);
            } catch (WebsocketNotConnectedException e) {
                log.debug("Couldn't ping, the connection closed meanwhile");
            }
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
        open();
        log.trace("Sending message={}", message);
        super.send(message);
    }

    /**
     * Opens the connection unless it is open; the listener's onOpen has run when this returns.
     *
     * <p>Java-WebSocket connects a client once. Only the first call connects; after that the reconnect monitor
     * restores a dropped connection.
     *
     * @throws WebsocketNotConnectedException if the connection can't be opened or the client is closed
     */
    public void open() {
        // onOpen sends from the read thread while connectBlocking() holds the lock; it gets past here only because
        // the connection is open by then.
        if (!this.isOpen()) {
            synchronized (lock) {
                if (!this.closed.get() && !this.connectAttempted) {
                    this.connectAttempted = true;
                    start();
                }
            }
        }
        if (!this.isOpen()) {
            throw new WebsocketNotConnectedException();
        }
    }

    private void start() {
        try {
            log.info("Opening connection...");
            boolean opened = this.connectBlocking();
            this.connected.set(opened);
            this.spent.set(!opened);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        // reconnectBlocking() closes the dropped connection through close(); that must not stop the monitor.
        if (Thread.currentThread().equals(this.monitorThread.get())) {
            super.close();
            return;
        }
        synchronized (this.schedulerLock) {
            this.closed.set(true);
            this.scheduler.shutdown();
        }
        super.close();
    }

    boolean isConnected() {
        return this.connected.get();
    }

    /** Whether the first connect failed; Java-WebSocket can't connect such a client again. */
    public boolean isSpent() {
        return this.spent.get();
    }

    public boolean isConnectionAlive() {
        return this.isConnected() && this.isOpen();
    }
}
