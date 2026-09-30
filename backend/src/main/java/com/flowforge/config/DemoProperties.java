package com.flowforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("flowforge.demo")
public record DemoProperties(String email, String password, String baseUrl) {

    private static final int MIN_PASSWORD_LENGTH = 10;

    public DemoProperties {
        if (email == null || email.isBlank()) {
            throw new IllegalStateException("flowforge.demo.email must be set when the demo profile is active");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("flowforge.demo.password (FLOWFORGE_DEMO_PASSWORD) must be set to at least "
                    + MIN_PASSWORD_LENGTH + " characters when the demo profile is active");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("flowforge.demo.base-url must be set when the demo profile is active");
        }
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
