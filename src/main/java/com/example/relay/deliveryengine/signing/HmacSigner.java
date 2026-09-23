package com.example.relay.deliveryengine.signing;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

@Component
public class HmacSigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    public String sign(String relayId, long timestampEpochSeconds, byte[] body, String secret) {
        byte[] signedPrefix = (relayId + "." + timestampEpochSeconds + ".").getBytes(StandardCharsets.UTF_8);

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            mac.update(signedPrefix);
            byte[] hash = mac.doFinal(body);
            return "v1," + Base64.getEncoder().encodeToString(hash);

        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("Failed to compute HMAC signature", ex);
        }
    }
}
