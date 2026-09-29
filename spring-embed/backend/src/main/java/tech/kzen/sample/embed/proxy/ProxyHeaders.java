package tech.kzen.sample.embed.proxy;

import java.util.Locale;
import java.util.Set;


/**
 * kzen-shell's header rules for a reverse proxy, in servlet terms: hop-by-hop headers (RFC 7230 §6.1) plus
 * {@code Host}, {@code Content-Length} and {@code Expect} are dropped in both directions — the transport
 * re-derives them — and anything {@code Proxy-*}. {@code Content-Encoding} is relayed untouched, so a gzip body
 * stays gzip end to end, and {@code Location} is relayed as the upstream wrote it (kzen answers relative).
 */
final class ProxyHeaders {
    private static final Set<String> dropped = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade", "host", "content-length", "expect");

    /** Headers the JDK client sets itself and refuses from the caller. */
    private static final Set<String> restrictedByClient = Set.of("connection", "content-length", "expect", "host", "upgrade");


    private ProxyHeaders() {}


    static boolean relayRequest(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return !dropped.contains(lower) && !restrictedByClient.contains(lower) && !lower.startsWith("proxy-");
    }


    static boolean relayResponse(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return !dropped.contains(lower) && !lower.startsWith("proxy-") && !lower.startsWith(":");
    }
}
