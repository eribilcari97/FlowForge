package com.flowforge.service.engine;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(OutboundHttpProperties.class)
public class AddressGuard {

    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final boolean allowPrivateAddresses;
    private final List<URI> allowedPrefixes;
    private final Resolver resolver;

    @Autowired
    public AddressGuard(OutboundHttpProperties properties) {
        this(properties, InetAddress::getAllByName);
    }

    AddressGuard(OutboundHttpProperties properties, Resolver resolver) {
        this.allowPrivateAddresses = properties.allowPrivateAddresses();
        this.allowedPrefixes = properties.allowedInternalUrlPrefixes().stream()
                .map(prefix -> URI.create(prefix.replaceAll("(?<!:)/{2,}", "/")).normalize())
                .toList();
        this.resolver = resolver;
    }

    public Optional<String> check(URI uri) throws UnknownHostException {
        if (allowPrivateAddresses || isAllowedInternalUrl(uri)) {
            return Optional.empty();
        }
        for (InetAddress address : resolver.resolve(uri.getHost())) {
            if (isInternal(address)) {
                return Optional.of("Requests to private or internal addresses are not allowed: " + uri.getHost()
                        + " resolves to " + address.getHostAddress());
            }
        }
        return Optional.empty();
    }

    static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 0 || first >= 240 || (first == 100 && second >= 64 && second <= 127);
        }
        return (bytes[0] & 0xfe) == 0xfc;
    }

    private boolean isAllowedInternalUrl(URI uri) {
        if (allowedPrefixes.isEmpty() || uri.getRawUserInfo() != null) {
            return false;
        }
        URI target = uri.normalize();
        return allowedPrefixes.stream().anyMatch(prefix -> sameOrigin(prefix, target)
                && target.getPath() != null && target.getPath().startsWith(prefix.getPath()));
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost().toLowerCase(Locale.ROOT).equals(b.getHost().toLowerCase(Locale.ROOT))
                && port(a) == port(b);
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
