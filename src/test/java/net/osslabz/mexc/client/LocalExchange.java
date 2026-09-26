package net.osslabz.mexc.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.java_websocket.WebSocket;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/**
 * A local stand-in for MEXC's websocket endpoint that answers subscriptions with a fixed code or blocks them, and
 * answers a repeated subscription on the same connection with an empty message, as MEXC does. Like MEXC, it can close
 * connections the client has sent nothing on for a while.
 */
final class LocalExchange extends WebSocketServer implements AutoCloseable {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CountDownLatch started = new CountDownLatch(1);

    private final BlockingQueue<JsonNode> commands = new LinkedBlockingQueue<>();

    private final BlockingQueue<String> openedResources = new LinkedBlockingQueue<>();

    private final AtomicInteger clientCloses = new AtomicInteger();

    private final AtomicInteger openConnections = new AtomicInteger();

    private final AtomicInteger pings = new AtomicInteger();

    private final Map<WebSocket, Long> lastClientMessageNanos = new ConcurrentHashMap<>();

    private final ScheduledExecutorService idleCheck = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "local-exchange-idle-check");
        thread.setDaemon(true);
        return thread;
    });

    private final int subscriptionAnswerCode;

    private final boolean blocksSubscriptions;

    private LocalExchange(int subscriptionAnswerCode, boolean blocksSubscriptions) {
        super(new InetSocketAddress("localhost", 0));
        setReuseAddr(true);
        this.subscriptionAnswerCode = subscriptionAnswerCode;
        this.blocksSubscriptions = blocksSubscriptions;
    }

    static LocalExchange start(int subscriptionAnswerCode) throws InterruptedException {
        return start(new LocalExchange(subscriptionAnswerCode, false));
    }

    /** Refuses every subscription the way MEXC refuses a channel it no longer serves: with code 0. */
    static LocalExchange startBlocking() throws InterruptedException {
        return start(new LocalExchange(0, true));
    }

    /** Closes a connection once the client has sent nothing on it for {@code idleTimeout}. */
    static LocalExchange startClosingIdleConnections(Duration idleTimeout) throws InterruptedException {
        LocalExchange exchange = start(new LocalExchange(0, false));
        long timeoutNanos = idleTimeout.toNanos();
        ScheduledFuture<?> unusedIdleCheck = exchange.idleCheck.scheduleWithFixedDelay(
                () -> exchange.lastClientMessageNanos.forEach((connection, lastMessage) -> {
                    if (System.nanoTime() - lastMessage > timeoutNanos) {
                        connection.close();
                    }
                }),
                10,
                10,
                TimeUnit.MILLISECONDS);
        return exchange;
    }

    private static LocalExchange start(LocalExchange exchange) throws InterruptedException {
        exchange.start();
        if (!exchange.started.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("local exchange did not start");
        }
        return exchange;
    }

    String uri() {
        return "ws://localhost:" + getPort();
    }

    JsonNode takeCommand() throws InterruptedException {
        // The reconnect monitor checks every three seconds.
        return commands.poll(10, TimeUnit.SECONDS);
    }

    JsonNode takeCommand(Duration timeout) throws InterruptedException {
        return commands.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    String takeOpenedResource() throws InterruptedException {
        return openedResources.poll(5, TimeUnit.SECONDS);
    }

    int clientCloses() {
        return clientCloses.get();
    }

    int pings() {
        return pings.get();
    }

    void awaitClientCloses(int count) throws InterruptedException {
        await(() -> clientCloses.get() >= count);
    }

    /** Waits until the exchange serves {@code count} connections; it answers a handshake before it serves one. */
    void awaitOpenConnections(int count) throws InterruptedException {
        await(() -> openConnections.get() == count);
    }

    void push(String message) {
        broadcast(message);
    }

    void push(byte[] frame) {
        broadcast(frame);
    }

    void dropConnections() {
        getConnections().forEach(WebSocket::close);
    }

    static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5 seconds");
            }
            Thread.sleep(10);
        }
    }

    /** Blocks the calling thread, typically one of the client's or the exchange's, until the test releases it. */
    static void awaitRelease(CountDownLatch released) {
        try {
            if (!released.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never released the blocked thread");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void onStart() {
        started.countDown();
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        connection.setAttachment(ConcurrentHashMap.<String>newKeySet());
        lastClientMessageNanos.put(connection, System.nanoTime());
        openedResources.add(handshake.getResourceDescriptor());
        openConnections.incrementAndGet();
    }

    @Override
    public void onMessage(WebSocket connection, String message) {
        lastClientMessageNanos.put(connection, System.nanoTime());
        JsonNode command;
        try {
            command = OBJECT_MAPPER.readTree(message);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        if ("PING".equals(command.get("method").asText())) {
            pings.incrementAndGet();
            answer(connection, "{\"id\":0,\"code\":0,\"msg\":\"PONG\"}");
            return;
        }
        commands.add(command);
        boolean subscription = "SUBSCRIPTION".equals(command.get("method").asText());
        String channel = command.get("params").get(0).asText();
        String answer = OBJECT_MAPPER
                .createObjectNode()
                .put("id", command.get("id").asInt())
                .put("code", subscription ? subscriptionAnswerCode : 0)
                .put(
                        "msg",
                        subscription
                                ? subscriptionAnswer(connection, channel)
                                : unsubscriptionAnswer(connection, channel))
                .toString();
        answer(connection, answer);
    }

    private static void answer(WebSocket connection, String answer) {
        try {
            connection.send(answer);
        } catch (WebsocketNotConnectedException e) {
            // close() sends its unsubscriptions and disconnects without waiting for the answers.
        }
    }

    private String subscriptionAnswer(WebSocket connection, String channel) {
        if (blocksSubscriptions) {
            return blockedAnswer(channel);
        }
        Set<String> subscribed = connection.getAttachment();
        return subscribed.add(channel) ? channel : "";
    }

    private static String unsubscriptionAnswer(WebSocket connection, String channel) {
        Set<String> subscribed = connection.getAttachment();
        subscribed.remove(channel);
        return channel;
    }

    /** The answer wbs-api.mexc.com gave to a JSON kline channel on 2026-09-25. */
    static String blockedAnswer(String channel) {
        return "Not Subscribed successfully! [%s].  Reason： Blocked! ".formatted(channel);
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        lastClientMessageNanos.remove(connection);
        openConnections.decrementAndGet();
        clientCloses.incrementAndGet();
    }

    @Override
    public void onError(WebSocket connection, Exception e) {
        throw new IllegalStateException(e);
    }

    @Override
    public void close() throws InterruptedException {
        idleCheck.shutdownNow();
        stop(1000);
    }
}
