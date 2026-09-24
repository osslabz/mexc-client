package net.osslabz.mexc.client.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SignatureUtilTest {

    @Test
    void signsWithHmacSha256AsLowercaseHex() {
        // RFC 4231, test case 2
        String signature = SignatureUtil.actualSignature("what do ya want for nothing?", "Jefe");

        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", signature);
    }

    @Test
    void urlEncodeEncodesSpacesAsPercent20() {
        assertEquals("a%20b%26c%3Dd", SignatureUtil.urlEncode("a b&c=d"));
    }

    @Test
    void toQueryStringJoinsTheParametersInOrder() {
        assertEquals(
                "symbol=BTCUSDT&side=BUY", SignatureUtil.toQueryString(params("symbol", "BTCUSDT", "side", "BUY")));
    }

    @Test
    void toQueryStringOfNoParametersIsEmpty() {
        assertEquals("", SignatureUtil.toQueryString(Map.of()));
    }

    @Test
    void toQueryStringWithEncodingEncodesTheValues() {
        assertEquals(
                "symbol=BTCUSDT&note=a%20b",
                SignatureUtil.toQueryStringWithEncoding(params("symbol", "BTCUSDT", "note", "a b")));
    }

    private static Map<String, String> params(String key1, String value1, String key2, String value2) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put(key1, value1);
        params.put(key2, value2);
        return params;
    }
}
