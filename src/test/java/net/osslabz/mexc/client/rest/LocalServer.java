package net.osslabz.mexc.client.rest;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;

/** Builds REST clients that talk to a local {@link MockWebServer} instead of api.mexc.com. */
public final class LocalServer {

    private LocalServer() {}

    public static UserDataClient userDataClient(MockWebServer server) {
        return new UserDataClient(restClient(server));
    }

    static MexcRestClient restClient(MockWebServer server) {
        return new MexcRestClient(
                "http://" + server.getHostName() + ":" + server.getPort(), "access-key", "secret-key");
    }

    public static MockResponse json(String body) {
        return new MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build();
    }

    public static MockResponse json(int code, String body) {
        return new MockResponse.Builder()
                .code(code)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build();
    }
}
