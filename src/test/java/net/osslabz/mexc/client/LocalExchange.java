package net.osslabz.mexc.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.java_websocket.WebSocket;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/** A local stand-in for MEXC's websocket endpoint that answers subscriptions with a fixed code. */
final class LocalExchange extends WebSocketServer implements AutoCloseable {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CountDownLatch started = new CountDownLatch(1);

    private final BlockingQueue<JsonNode> commands = new LinkedBlockingQueue<>();

    private final BlockingQueue<String> openedResources = new LinkedBlockingQueue<>();

    private final AtomicInteger clientCloses = new AtomicInteger();

    private volatile int subscriptionAnswerCode;

    private LocalExchange() {
        super(new InetSocketAddress("localhost", 0));
        setReuseAddr(true);
    }

    static LocalExchange start(int subscriptionAnswerCode) throws InterruptedException {
        LocalExchange exchange = new LocalExchange();
        exchange.subscriptionAnswerCode = subscriptionAnswerCode;
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

    String takeOpenedResource() throws InterruptedException {
        return openedResources.poll(5, TimeUnit.SECONDS);
    }

    void awaitClientCloses(int count) throws InterruptedException {
        await(() -> clientCloses.get() >= count);
    }

    void push(String message) {
        broadcast(message);
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

    @Override
    public void onStart() {
        started.countDown();
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        openedResources.add(handshake.getResourceDescriptor());
    }

    @Override
    public void onMessage(WebSocket connection, String message) {
        JsonNode command;
        try {
            command = OBJECT_MAPPER.readTree(message);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        commands.add(command);
        String answer = OBJECT_MAPPER
                .createObjectNode()
                .put("id", command.get("id").asInt())
                .put("code", "SUBSCRIPTION".equals(command.get("method").asText()) ? subscriptionAnswerCode : 0)
                .put("msg", command.get("params").get(0).asText())
                .toString();
        try {
            connection.send(answer);
        } catch (WebsocketNotConnectedException e) {
            // close() sends its unsubscriptions and disconnects without waiting for the answers.
        }
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        clientCloses.incrementAndGet();
    }

    @Override
    public void onError(WebSocket connection, Exception e) {
        throw new IllegalStateException(e);
    }

    @Override
    public void close() throws InterruptedException {
        stop(1000);
    }
}
