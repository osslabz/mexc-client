package net.osslabz.mexc.client.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import net.osslabz.mexc.client.rest.dto.ErrorResponse;
import net.osslabz.mexc.client.utils.SignatureInterceptor;
import net.osslabz.mexc.client.utils.SignatureUtil;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class MexcRestClient {
    private static final String REQUEST_HOST = "https://api.mexc.com";

    private static final ObjectMapper OBJECT_MAPPER;

    private final String accessKey;
    private final String secretKey;
    private final OkHttpClient okHttpClient;


    static {
        OBJECT_MAPPER = new ObjectMapper();
        OBJECT_MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        OBJECT_MAPPER.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        OBJECT_MAPPER.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        OBJECT_MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }


    public MexcRestClient(String accessKey, String secretKey) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;

        this.okHttpClient = createOkHttpClient();
    }


    private OkHttpClient createOkHttpClient() {
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(
                message -> LoggerFactory.getLogger(this.getClass()).info(message)
        );
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        return new OkHttpClient.Builder()
                .connectTimeout(45, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(45, TimeUnit.SECONDS)
                .addInterceptor(new SignatureInterceptor(accessKey, secretKey))
                .addInterceptor(loggingInterceptor)
                .build();
    }


    <T> T get(String uri, Map<String, String> params, Class<T> clazz) {
        try {
            Response response = okHttpClient
                    .newCall(new Request.Builder().url(createUrl(uri, params)).get().build())
                    .execute();
            return handleResponse(response, clazz);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @NotNull
    private static String createUrl(String uri, Map<String, String> params) {
        String url = params != null && !params.isEmpty() ? REQUEST_HOST + uri + "?" + SignatureUtil.toQueryString(params) : REQUEST_HOST + uri;
        return url;
    }


    <T> T post(String uri, Map<String, String> params, Class<T> clazz) {
        try {
            String url = createUrl(uri, params);
            Response response = okHttpClient
                    .newCall(new Request.Builder()
                            .url(url)

                            .post(RequestBody.create(new byte[0], null)).header("Content-Length", "0").build()).execute();
            return handleResponse(response, clazz);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    <T> T postEmptyBody(String uri, Map<String, String> params, Class<T> clazz) {
        try {
            String timestamp = Instant.now().toEpochMilli() + "";
            String paramsStr = SignatureUtil.toQueryStringWithEncoding(params);
            paramsStr += "&timestamp=" + timestamp;
            String signature = SignatureUtil.actualSignature(paramsStr, secretKey);
            paramsStr += "&signature=" + signature;


            RequestBody empty = RequestBody.create(null, new byte[0]);
            Request.Builder body = new Request.Builder().url(REQUEST_HOST.concat(uri).concat("?").concat(paramsStr)).method("POST", empty).header("Content-Length", "0");
            Response response = okHttpClient
                    .newCall(body.build()).execute();
            return handleResponse(response, clazz);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    <T> T put(String uri, Map<String, String> params, Class<T> clazz) {
        try {
            Response response = okHttpClient
                    .newCall(new Request.Builder()
                            .url(REQUEST_HOST.concat(uri))
                            .put(RequestBody.create(SignatureUtil.toQueryString(params), MediaType.get("text/plain"))).build()).execute();
            return handleResponse(response, clazz);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    <T> T delete(String uri, Map<String, String> params, Class<T> clazz) {
        try {
            return handleResponse(okHttpClient
                    .newCall(new Request.Builder()
                            .url(REQUEST_HOST.concat(uri))
                            .delete(RequestBody.create(SignatureUtil.toQueryString(params), MediaType.get("text/plain"))).build()).execute(), clazz);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    private <T> T handleResponse(Response response, Class<T> clazz) {
        try {
            if (response.code() < 400) {
                return OBJECT_MAPPER.readValue(response.body().string(), clazz);
            } else {
                ErrorResponse errorResponse = OBJECT_MAPPER.readValue(response.body().string(), ErrorResponse.class);
                throw new RuntimeException(errorResponse.getMsg());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}