package tech.kzen.sample.embed.proxy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.kzen.sample.embed.workspace.KzenWorkspace;
import tech.kzen.sample.embed.workspace.KzenWorkspaces;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


/**
 * The streaming reverse proxy: {@code /kzen/{workspace}/**} → that workspace's loopback server, same method,
 * path and query, request body forwarded as a stream, status and headers relayed under {@link ProxyHeaders},
 * the response body copied in 8 KiB chunks with a flush of both the servlet output and the response buffer
 * per chunk — on the request thread, through a void handler writing {@link HttpServletResponse} directly.
 * That synchronous shape is what makes kzen's SSE ({@code /logic/events}) arrive incrementally through
 * Tomcat; a {@code StreamingResponseBody} buffers until completion (HS02 G7). A client that goes away ends the
 * copy loop at its next write, which closes the upstream stream; live copies are counted for the host's stats.
 */
@RestController
public class KzenProxyController {
    private static final Logger logger = LoggerFactory.getLogger(KzenProxyController.class);
    private static final String prefix = "/kzen/";
    private static final int chunkBytes = 8 * 1024;

    private final KzenWorkspaces workspaces;
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final AtomicInteger activeStreams = new AtomicInteger();
    private final AtomicLong relayedRequests = new AtomicLong();
    private final AtomicLong clientDisconnects = new AtomicLong();


    public KzenProxyController(KzenWorkspaces workspaces) {
        this.workspaces = workspaces;
    }


    public int activeStreams() {
        return activeStreams.get();
    }

    public long relayedRequests() {
        return relayedRequests.get();
    }

    public long clientDisconnects() {
        return clientDisconnects.get();
    }


    @RequestMapping(prefix + "{workspace}/**")
    public void proxy(
            @PathVariable String workspace,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        Optional<KzenWorkspace> target = workspaces.find(workspace).filter(KzenWorkspace::isRunning);
        if (target.isEmpty()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "No running workspace '" + workspace + "'");
            return;
        }
        relayedRequests.incrementAndGet();

        String subPath = request.getRequestURI().substring((prefix + workspace).length());
        String query = request.getQueryString();
        URI upstream = URI.create("http://127.0.0.1:" + target.get().port()
                + (subPath.isEmpty() ? "/" : subPath) + (query == null ? "" : "?" + query));

        HttpRequest.Builder builder = HttpRequest.newBuilder(upstream);
        request.getHeaderNames().asIterator().forEachRemaining(name -> {
            if (ProxyHeaders.relayRequest(name)) {
                request.getHeaders(name).asIterator().forEachRemaining(value -> builder.header(name, value));
            }
        });
        HttpRequest.BodyPublisher body = hasBody(request)
                ? HttpRequest.BodyPublishers.ofInputStream(() -> {
                    try {
                        return request.getInputStream();
                    }
                    catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                : HttpRequest.BodyPublishers.noBody();
        builder.method(request.getMethod(), body);

        HttpResponse<InputStream> relayed;
        try {
            relayed = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "Interrupted while reaching workspace '" + workspace + "'");
            return;
        }
        catch (IOException e) {
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "Workspace '" + workspace + "' unreachable: " + e.getMessage());
            return;
        }

        response.setStatus(relayed.statusCode());
        for (Map.Entry<String, List<String>> header : relayed.headers().map().entrySet()) {
            if (ProxyHeaders.relayResponse(header.getKey())) {
                for (String value : header.getValue()) {
                    response.addHeader(header.getKey(), value);
                }
            }
        }

        activeStreams.incrementAndGet();
        try (InputStream in = relayed.body()) {
            OutputStream out = response.getOutputStream();
            byte[] buffer = new byte[chunkBytes];
            int read;
            while ((read = in.read(buffer)) != -1) {
                try {
                    out.write(buffer, 0, read);
                    out.flush();
                    response.flushBuffer();
                }
                catch (IOException clientGone) {
                    clientDisconnects.incrementAndGet();
                    logger.debug("Client left /kzen/{}{} mid-stream; closing upstream", workspace, subPath);
                    return;
                }
            }
        }
        finally {
            activeStreams.decrementAndGet();
        }
    }


    private static boolean hasBody(HttpServletRequest request) {
        return request.getContentLengthLong() > 0
                || (request.getHeader("Transfer-Encoding") != null && !"GET".equals(request.getMethod()));
    }
}
