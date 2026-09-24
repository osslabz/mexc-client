package net.osslabz.mexc.client.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
