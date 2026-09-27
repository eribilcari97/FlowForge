package com.flowforge.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("flowforge.security.jwt")
public record JwtProperties(String secret, Duration accessTokenTtl) {

    private static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("flowforge.security.jwt.secret (environment variable FLOWFORGE_JWT_SECRET) "
                    + "must be set to at least " + MIN_SECRET_BYTES + " bytes");
        }
        if (accessTokenTtl == null || accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
            throw new IllegalStateException("flowforge.security.jwt.access-token-ttl must be positive");
        }
    }

    public SecretKey secretKey() {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Override
    public String toString() {
        return "JwtProperties[secret=***, accessTokenTtl=" + accessTokenTtl + "]";
    }
}
