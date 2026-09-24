package net.osslabz.mexc.client.utils;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

@Slf4j
public class SignatureInterceptor implements Interceptor {

    private static final String HEADER_ACCESS_KEY = "X-MEXC-APIKEY";
    private static final Set<String> SIGNED_METHODS = Set.of("GET", "POST", "PUT", "DELETE");
    private final String accessKey;
    private final String secretKey;

    public SignatureInterceptor(String accessKey, String secretKey) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    @NotNull
    @Override
    public Response intercept(Chain chain) throws IOException {
        Request origRequest = chain.request();
        if (!SIGNED_METHODS.contains(origRequest.method())) {
            return chain.proceed(origRequest);
        }
        return chain.proceed(createUrlSignRequest(origRequest));
    }

    private Request createUrlSignRequest(Request request) {
        String timestamp = Instant.now().toEpochMilli() + "";
        HttpUrl url = request.url();
        HttpUrl.Builder urlBuilder = url.newBuilder().setQueryParameter("timestamp", timestamp);
        String queryParams = urlBuilder.build().query();
        urlBuilder.setQueryParameter("signature", SignatureUtil.actualSignature(queryParams, secretKey));
        return request.newBuilder()
                .addHeader(HEADER_ACCESS_KEY, accessKey)
                .url(urlBuilder.build())
                .build();
    }
}
