package net.osslabz.mexc.client.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SignatureInterceptorTest {

    private MockWebServer server;

    private OkHttpClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new OkHttpClient.Builder()
                .addInterceptor(new SignatureInterceptor("access-key", "secret-key"))
                .build();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void signsTheQueryOfAGetRequest() throws Exception {
        RecordedRequest request = send(new Request.Builder()
                .url(server.url("/api/v3/order?symbol=BTCUSDT"))
                .get());

        assertSignedQuery(request, "symbol=BTCUSDT&");
    }

    @Test
    void signsTheQueryOfADeleteRequest() throws Exception {
        RecordedRequest request = send(new Request.Builder()
                .url(server.url("/api/v3/order?orderId=42"))
                .delete());

        assertSignedQuery(request, "orderId=42&");
    }

    @Test
    void leavesAHeadRequestUnsigned() throws Exception {
        RecordedRequest request =
                send(new Request.Builder().url(server.url("/api/v3/ping")).head());

        assertNull(request.getHeaders().get("X-MEXC-APIKEY"));
        assertNull(request.getUrl().query());
    }

    private RecordedRequest send(Request.Builder request) throws IOException, InterruptedException {
        server.enqueue(new MockResponse.Builder().build());
        try (Response response = client.newCall(request.build()).execute()) {
            assertEquals(200, response.code());
        }
        return server.takeRequest(5, TimeUnit.SECONDS);
    }

    private static void assertSignedQuery(RecordedRequest request, String queryBeforeTimestamp) {
        HttpUrl url = request.getUrl();
        String timestamp = url.queryParameter("timestamp");
        assertTrue(Math.abs(System.currentTimeMillis() - Long.parseLong(timestamp)) < 60_000);
        assertEquals("access-key", request.getHeaders().get("X-MEXC-APIKEY"));
        assertEquals(
                SignatureUtil.actualSignature(queryBeforeTimestamp + "timestamp=" + timestamp, "secret-key"),
                url.queryParameter("signature"));
    }
}
