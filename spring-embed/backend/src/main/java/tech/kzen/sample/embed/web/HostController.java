package tech.kzen.sample.embed.web;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.kzen.sample.embed.proxy.KzenProxyController;
import tech.kzen.sample.embed.workspace.KzenWorkspace;
import tech.kzen.sample.embed.workspace.KzenWorkspaces;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * The host's own pages: the workspace list (the portlet page — the host, not an embedded launcher, is the
 * way into each workspace), a JSON view of the workspaces and the proxy's counters for a test to watch
 * (live copies, disconnects), and an administrative stop of one workspace while the others keep serving.
 */
@RestController
public class HostController {
    private final KzenWorkspaces workspaces;
    private final KzenProxyController proxy;
    private final ApplicationContext applicationContext;


    public HostController(KzenWorkspaces workspaces, KzenProxyController proxy, ApplicationContext applicationContext) {
        this.workspaces = workspaces;
        this.proxy = proxy;
        this.applicationContext = applicationContext;
    }


    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String index() {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset=\"utf-8\"><title>kzen workspaces</title></head><body>");
        html.append("<h1>kzen workspaces</h1><ul>");
        for (KzenWorkspace workspace : workspaces.all()) {
            html.append("<li><a href=\"/kzen/").append(workspace.name()).append("/index.html\">")
                    .append(workspace.name()).append("</a>")
                    .append(workspace.isRunning() ? "" : " (stopped)")
                    .append(" &mdash; ").append(workspace.directory()).append("</li>");
        }
        html.append("</ul></body></html>");
        return html.toString();
    }


    @GetMapping(value = "/kzen-host/workspaces", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> list() {
        return workspaces.all().stream().map(workspace -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", workspace.name());
            row.put("port", workspace.port());
            row.put("directory", workspace.directory().toString());
            row.put("running", workspace.isRunning());
            return row;
        }).toList();
    }


    @GetMapping(value = "/kzen-host/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("activeStreams", proxy.activeStreams());
        stats.put("relayedRequests", proxy.relayedRequests());
        stats.put("clientDisconnects", proxy.clientDisconnects());
        return stats;
    }


    @DeleteMapping("/kzen-host/workspaces/{name}")
    public ResponseEntity<String> stop(@PathVariable String name) {
        return workspaces.stopWorkspace(name)
                ? ResponseEntity.ok("stopped " + name)
                : ResponseEntity.notFound().build();
    }


    /**
     * Graceful exit on request (a sample's stand-in for the operator's signal, which a Windows child process
     * never receives): the context closes off the request thread, so Spring's lifecycle stops the workspaces
     * (server, then context — run cancelled and joined, work root released) before Tomcat stops.
     */
    @PostMapping("/kzen-host/shutdown")
    public String shutdown() {
        Thread exit = new Thread(() -> {
            try {
                Thread.sleep(200);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.exit(SpringApplication.exit(applicationContext, () -> 0));
        }, "kzen-host-shutdown");
        exit.setDaemon(true);
        exit.start();
        return "shutting down";
    }
}
