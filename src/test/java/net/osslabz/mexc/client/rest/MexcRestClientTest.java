package net.osslabz.mexc.client.rest;

import static net.osslabz.mexc.client.rest.LocalServer.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import net.osslabz.mexc.client.rest.dto.ListenKey;
import net.osslabz.mexc.client.rest.dto.ListenKeys;
import net.osslabz.mexc.client.utils.SignatureUtil;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MexcRestClientTest {

    private MockWebServer server;

    private MexcRestClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
        client = LocalServer.restClient(server);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void getReadsTheResponseBody() throws Exception {
        server.enqueue(json("{\"listenKey\":[\"key-1\",\"key-2\"]}"));

        ListenKeys listenKeys = client.get("/api/v3/userDataStream", Map.of(), ListenKeys.class);

        assertEquals(List.of("key-1", "key-2"), listenKeys.getListenKey());
        RecordedRequest request = takeRequest();
        assertEquals("GET", request.getMethod());
        assertEquals("/api/v3/userDataStream", request.getUrl().encodedPath());
        assertEquals("access-key", request.getHeaders().get("X-MEXC-APIKEY"));
        assertNotNull(request.getUrl().queryParameter("signature"));
    }

    @Test
    void postSendsAnEmptySignedBody() throws Exception {
        server.enqueue(json("{\"listenKey\":\"key-3\"}"));

        ListenKey listenKey = client.post("/api/v3/userDataStream", Map.of(), ListenKey.class);

        assertEquals("key-3", listenKey.getListenKey());
        RecordedRequest request = takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals(0, request.getBodySize());
        assertNotNull(request.getUrl().queryParameter("signature"));
    }

    @Test
    void getSendsTheParametersInTheQuery() throws Exception {
        server.enqueue(json("{\"listenKey\":[]}"));

        client.get("/api/v3/userDataStream", Map.of("recvWindow", "5000"), ListenKeys.class);

        assertEquals("5000", takeRequest().getUrl().queryParameter("recvWindow"));
    }

    @Test
    void putSendsTheParametersInTheSignedQuery() throws Exception {
        server.enqueue(json("{\"listenKey\":\"key-1\"}"));

        client.put("/api/v3/userDataStream", Map.of("listenKey", "key-1"), ListenKey.class);

        RecordedRequest request = takeRequest();
        assertEquals("PUT", request.getMethod());
        assertEquals(0, request.getBodySize());
        HttpUrl url = request.getUrl();
        assertEquals("key-1", url.queryParameter("listenKey"));
        assertEquals(
                SignatureUtil.actualSignature(
                        "listenKey=key-1&timestamp=" + url.queryParameter("timestamp"), "secret-key"),
                url.queryParameter("signature"));
    }

    @Test
    void deleteSendsTheParametersAsBody() throws Exception {
        server.enqueue(json("{\"listenKey\":\"key-1\"}"));

        client.delete("/api/v3/userDataStream", Map.of("listenKey", "key-1"), ListenKey.class);

        RecordedRequest request = takeRequest();
        assertEquals("DELETE", request.getMethod());
        assertEquals("listenKey=key-1", request.getBody().utf8());
    }

    @Test
    void anErrorStatusThrowsTheExchangeMessage() {
        server.enqueue(json(400, "{\"code\":\"700002\",\"msg\":\"Signature for this request is not valid.\"}"));

        RuntimeException e = assertThrows(
                RuntimeException.class, () -> client.get("/api/v3/userDataStream", Map.of(), ListenKeys.class));

        assertEquals("Signature for this request is not valid.", e.getMessage());
    }

    @Test
    void anUnreadableBodyThrows() {
        server.enqueue(json("not json"));

        assertThrows(RuntimeException.class, () -> client.get("/api/v3/userDataStream", Map.of(), ListenKeys.class));
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        return server.takeRequest(5, TimeUnit.SECONDS);
    }
}
