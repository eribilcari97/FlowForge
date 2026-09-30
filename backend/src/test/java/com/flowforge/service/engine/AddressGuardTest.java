package com.flowforge.service.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AddressGuardTest {

    @ParameterizedTest
    @ValueSource(strings = { "127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.1.20", "169.254.169.254", "0.0.0.0",
            "100.64.0.1", "224.0.0.1", "::1", "fe80::1", "fd00:ec2::254", "::ffff:10.0.0.1" })
    void internalAddressesAreRecognised(String address) throws UnknownHostException {
        assertThat(AddressGuard.isInternal(InetAddress.getByName(address))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "93.184.215.14", "8.8.8.8", "172.32.0.1", "100.128.0.1", "2606:4700::1111" })
    void publicAddressesAreAllowed(String address) throws UnknownHostException {
        assertThat(AddressGuard.isInternal(InetAddress.getByName(address))).isFalse();
    }

    @Test
    void aHostNameResolvingToAnInternalAddressIsBlocked() throws UnknownHostException {
        AddressGuard guard = guard(false, List.of(), "93.184.215.14", "10.0.0.5");

        assertThat(guard.check(URI.create("https://rebinding.example.com/data")))
                .hasValue("Requests to private or internal addresses are not allowed: "
                        + "rebinding.example.com resolves to 10.0.0.5");
    }

    @Test
    void aHostNameResolvingOnlyToPublicAddressesIsAllowed() throws UnknownHostException {
        assertThat(guard(false, List.of(), "93.184.215.14").check(URI.create("https://api.example.com/data")))
                .isEmpty();
    }

    @Test
    void privateAddressesCanBeAllowedForLocalDevelopment() throws UnknownHostException {
        assertThat(guard(true, List.of(), "127.0.0.1").check(URI.create("http://localhost:8080/anything")))
                .isEmpty();
    }

    @Test
    void onlyTheExactAllowedInternalUrlsAreExempt() throws UnknownHostException {
        AddressGuard guard = guard(false, List.of("http://localhost:8080/demo/"), "127.0.0.1");

        assertThat(guard.check(URI.create("http://localhost:8080/demo/orders"))).isEmpty();
        assertThat(guard.check(URI.create("http://LOCALHOST:8080/demo/flaky?x=1"))).isEmpty();
        assertThat(guard.check(URI.create("http://localhost:8080/demo/../api/projects"))).isPresent();
        assertThat(guard.check(URI.create("http://localhost:8081/demo/orders"))).isPresent();
        assertThat(guard.check(URI.create("http://user@localhost:8080/demo/orders"))).isPresent();
        assertThat(guard.check(URI.create("http://localhost:8080/actuator/env"))).isPresent();
    }

    private static AddressGuard guard(boolean allowPrivate, List<String> prefixes, String... resolvedAddresses) {
        return new AddressGuard(new OutboundHttpProperties(allowPrivate, prefixes), host -> {
            InetAddress[] addresses = new InetAddress[resolvedAddresses.length];
            for (int i = 0; i < resolvedAddresses.length; i++) {
                addresses[i] = InetAddress.getByName(resolvedAddresses[i]);
            }
            return addresses;
        });
    }
}
