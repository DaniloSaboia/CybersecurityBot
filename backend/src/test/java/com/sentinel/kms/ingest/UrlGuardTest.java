package com.sentinel.kms.ingest;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlGuardTest {

    // Resolver stub: every host "resolves" to the addresses we choose.
    private static UrlGuard guardReturning(InetAddress... addresses) {
        return new UrlGuard(host -> List.of(addresses));
    }

    private static InetAddress addr(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void allowsPublicHttpsUrl() {
        UrlGuard guard = guardReturning(addr("93.184.216.34"));
        assertThat(guard.validate("https://example.com/advisory.html").getHost()).isEqualTo("example.com");
    }

    @Test
    void rejectsMissingOrNullish() {
        UrlGuard guard = guardReturning(addr("93.184.216.34"));
        assertThatThrownBy(() -> guard.validate(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.validate("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.validate("not a url")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonHttpSchemes() {
        UrlGuard guard = guardReturning(addr("93.184.216.34"));
        assertThatThrownBy(() -> guard.validate("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only http(s)");
        assertThatThrownBy(() -> guard.validate("ftp://example.com/doc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCredentialsInUrl() {
        UrlGuard guard = guardReturning(addr("93.184.216.34"));
        assertThatThrownBy(() -> guard.validate("https://user:pass@example.com/doc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentials");
    }

    @Test
    void rejectsLoopbackAndPrivateRanges() {
        for (String blocked : new String[]{
                "127.0.0.1",           // loopback v4
                "10.1.2.3",            // site-local
                "172.16.5.4",          // site-local
                "192.168.1.1",         // site-local
                "169.254.1.1",         // link-local (cloud metadata)
                "0.0.0.0",             // wildcard
                "100.64.0.7",          // CGNAT
                "::1",                 // loopback v6
                "fe80::1",             // link-local v6
                "fd00::1234"}) {       // unique-local v6
            UrlGuard guard = guardReturning(addr(blocked));
            assertThatThrownBy(() -> guard.validate("http://" + dnsSafe(blocked) + ".test/doc"))
                    .as("should block %s", blocked)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-public");
        }
    }

    /** Host text doesn't matter (resolver is stubbed) — it just has to parse as a hostname. */
    private static String dnsSafe(String literal) {
        return "host" + Math.abs(literal.hashCode());
    }

    @Test
    void blocksIfAnyResolvedAddressIsPrivate() {
        UrlGuard guard = guardReturning(addr("93.184.216.34"), addr("192.168.0.5"));
        assertThatThrownBy(() -> guard.validate("https://mixed-resolution.test/doc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unresolvableHostIsRejected() {
        UrlGuard guard = new UrlGuard(host -> {
            throw new IllegalArgumentException("Cannot resolve host: " + host);
        });
        assertThatThrownBy(() -> guard.validate("https://nx.example.com/doc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resolve");
    }
}
