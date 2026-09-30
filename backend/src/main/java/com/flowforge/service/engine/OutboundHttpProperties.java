package com.flowforge.service.engine;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("flowforge.http")
public record OutboundHttpProperties(boolean allowPrivateAddresses, List<String> allowedInternalUrlPrefixes) {

    public OutboundHttpProperties {
        allowedInternalUrlPrefixes = allowedInternalUrlPrefixes == null ? List.of() : List.copyOf(allowedInternalUrlPrefixes);
    }
}
