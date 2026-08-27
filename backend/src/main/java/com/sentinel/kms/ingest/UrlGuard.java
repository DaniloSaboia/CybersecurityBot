package com.sentinel.kms.ingest;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.function.Function;

/**
 * SSRF guard for user-supplied URLs: only public http(s) endpoints may be
 * fetched. Rejects non-HTTP schemes, URLs carrying credentials, and any host
 * that resolves (in whole or in part) to a loopback, link-local, site-local,
 * unique-local, multicast, or wildcard address — so the backend can never be
 * turned into a proxy for internal services or cloud metadata endpoints.
 *
 * <p>DNS resolution is injectable so tests can exercise the rules without
 * hitting a resolver. Note the guard validates at call time; a hostile DNS
 * server could still re-resolve between validation and fetch (TOCTOU), which
 * {@link UrlFetcher} mitigates by pinning the resolved addresses it actually
 * connects to.
 */
@Component
public class UrlGuard {

    private final Function<String, List<InetAddress>> resolver;

    public UrlGuard() {
        this(host -> {
            try {
                return List.of(InetAddress.getAllByName(host));
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Cannot resolve host: " + host, e);
            }
        });
    }

    UrlGuard(Function<String, List<InetAddress>> resolver) {
        this.resolver = resolver;
    }

    /**
     * Validates {@code raw} and returns the parsed URI, or throws
     * {@link IllegalArgumentException} with a user-presentable reason.
     */
    public URI validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("URL is required");
        }
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Malformed URL: " + e.getMessage());
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Only http(s) URLs are allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL has no host");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("URLs with embedded credentials are not allowed");
        }
        for (InetAddress addr : resolve(host)) {
            if (isBlocked(addr)) {
                throw new IllegalArgumentException(
                        "Refusing to fetch non-public host (" + addr.getHostAddress() + ")");
            }
        }
        return uri;
    }

    private List<InetAddress> resolve(String host) {
        List<InetAddress> addresses = resolver.apply(host);
        if (addresses == null || addresses.isEmpty()) {
            throw new IllegalArgumentException("Host does not resolve: " + host);
        }
        return addresses;
    }

    /** The resolved addresses behind a validated host — {@link UrlFetcher} pins these. */
    List<InetAddress> resolvedAddresses(URI uri) {
        return resolve(uri.getHost());
    }

    static boolean isBlocked(InetAddress a) {
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isAnyLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff;
            // 0.0.0.0/8 (already via anyLocal for 0.0.0.0 itself) and 100.64.0.0/10 CGNAT
            return first == 0 || (first == 100 && (b[1] & 0xc0) == 0x40);
        }
        // IPv6 unique-local fc00::/7
        return (b[0] & 0xfe) == 0xfc;
    }
}
