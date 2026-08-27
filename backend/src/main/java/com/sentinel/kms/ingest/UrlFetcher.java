package com.sentinel.kms.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Fetches a guarded URL for the ingestion pipeline over {@link java.net.http.HttpClient}.
 *
 * <p>SSRF posture: every hop (including redirects) goes back through
 * {@link UrlGuard#validate}, redirects are followed manually (max 4) so a
 * 30x to an internal address can't slip past, the resolved addresses are
 * re-checked against the guard before each connect, and the response body is
 * capped at {@code app.ingest.url-max-bytes}.
 */
@Component
public class UrlFetcher {

    private static final Logger log = LoggerFactory.getLogger(UrlFetcher.class);

    private static final int MAX_REDIRECTS = 4;

    private final UrlGuard guard;
    private final HttpClient client;
    private final long maxBytes;

    public UrlFetcher(UrlGuard guard, @Value("${app.ingest.url-max-bytes:2097152}") long maxBytes) {
        this.guard = guard;
        this.maxBytes = maxBytes;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER) // handled manually, re-validated
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Raw bytes of the fetched document; throws on any validation/IO failure. */
    public byte[] fetch(String rawUrl) {
        URI uri = guard.validate(rawUrl);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            // Re-check the addresses this connection will actually use (TOCTOU pin).
            List<InetAddress> addresses = guard.resolvedAddresses(uri);
            if (addresses.stream().anyMatch(UrlGuard::isBlocked)) {
                throw new IllegalArgumentException("Refusing to fetch non-public host: " + uri.getHost());
            }

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "SentinelKMS-Ingest/1.0")
                    .header("Accept", "text/html,text/plain,application/json,application/pdf,*/*;q=0.8")
                    .GET()
                    .build();

            HttpResponse<byte[]> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                throw new IllegalArgumentException("Fetch failed for " + uri + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalArgumentException("Fetch interrupted for " + uri);
            }

            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null) {
                    throw new IllegalArgumentException("Redirect without Location from " + uri);
                }
                uri = guard.validate(uri.resolve(location).toString());
                continue;
            }
            if (status != 200) {
                throw new IllegalArgumentException("Fetch failed for " + uri + " — HTTP " + status);
            }
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                throw new IllegalArgumentException("Empty body from " + uri);
            }
            if (body.length > maxBytes) {
                log.warn("Truncating fetched document from {} ({} bytes > cap {})",
                        uri, body.length, maxBytes);
                byte[] capped = new byte[(int) maxBytes];
                System.arraycopy(body, 0, capped, 0, (int) maxBytes);
                return capped;
            }
            return body;
        }
        throw new IllegalArgumentException("Too many redirects for " + rawUrl);
    }
}
