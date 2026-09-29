package tech.kzen.sample.embed.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tech.kzen.auto.server.context.KzenAutoHost;
import tech.kzen.auto.server.context.runtime.KzenAutoRuntime;
import tech.kzen.auto.server.context.runtime.KzenAutoRuntimeConfig;
import tech.kzen.sample.embed.config.KzenHostProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


/**
 * The host's workspaces under Spring's lifecycle: on start, the process-global {@link KzenAutoRuntime} is
 * initialized once (plugin root from configuration), then each workspace is started in order; a later
 * failure stops every workspace already started, in reverse, before the failure propagates — so a half-built
 * host leaves no bound port and no claimed work root behind. On stop, each running workspace is stopped
 * (server, then context). The runtime itself is never closed: it is the process's extension universe.
 */
@Component
public class KzenWorkspaces implements SmartLifecycle {
    private static final Logger logger = LoggerFactory.getLogger(KzenWorkspaces.class);

    private final KzenHostProperties properties;
    private final KzenAutoHost hostServices;
    private final Map<String, KzenWorkspace> workspaces = new LinkedHashMap<>();
    private volatile boolean running;


    public KzenWorkspaces(KzenHostProperties properties, KzenAutoHost hostServices) {
        this.properties = properties;
        this.hostServices = hostServices;
    }


    public List<KzenWorkspace> all() {
        synchronized (workspaces) {
            return List.copyOf(workspaces.values());
        }
    }


    public Optional<KzenWorkspace> find(String name) {
        synchronized (workspaces) {
            return Optional.ofNullable(workspaces.get(name));
        }
    }


    @Override
    public void start() {
        Path home = properties.home().toAbsolutePath().normalize();
        KzenAutoRuntime.Companion.initialize(new KzenAutoRuntimeConfig(
                properties.pluginRoot() == null ? null : properties.pluginRoot().toAbsolutePath().normalize()));

        List<KzenWorkspace> started = new ArrayList<>();
        try {
            for (KzenHostProperties.Workspace configured : properties.workspaces()) {
                KzenWorkspace workspace = new KzenWorkspace(
                        configured.name(), configured.port(), home.resolve(configured.name()),
                        configured.workRoot() == null ? null : configured.workRoot().toAbsolutePath().normalize(),
                        hostServices);
                workspace.start();
                started.add(workspace);
            }
        }
        catch (RuntimeException failure) {
            logger.error("Workspace start failed after {} started: {}", started.size(), failure.getMessage());
            Collections.reverse(started);
            for (KzenWorkspace workspace : started) {
                try {
                    workspace.stop();
                    logger.error("Rolled back workspace '{}'", workspace.name());
                }
                catch (RuntimeException rollbackFailure) {
                    logger.error("Rollback of workspace '{}' failed", workspace.name(), rollbackFailure);
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
        synchronized (workspaces) {
            for (KzenWorkspace workspace : started) {
                workspaces.put(workspace.name(), workspace);
            }
        }
        running = true;
        logger.info("{} workspace(s) started under {}", started.size(), home);
    }


    @Override
    public void stop() {
        for (KzenWorkspace workspace : all()) {
            stopQuietly(workspace);
        }
        running = false;
    }


    /** Stops one workspace (its server, then its context) while the others keep serving. */
    public boolean stopWorkspace(String name) {
        Optional<KzenWorkspace> workspace = find(name);
        if (workspace.isEmpty() || !workspace.get().isRunning()) {
            return false;
        }
        stopQuietly(workspace.get());
        return true;
    }


    private void stopQuietly(KzenWorkspace workspace) {
        try {
            workspace.stop();
        }
        catch (RuntimeException e) {
            logger.error("Workspace '{}' did not stop cleanly", workspace.name(), e);
        }
    }


    @Override
    public boolean isRunning() {
        return running;
    }


    /**
     * Before the web server on the way up and after it on the way down (lower phases start first and stop
     * last; Boot's web server sits near the top): the proxy targets exist whenever the proxy can be reached.
     */
    @Override
    public int getPhase() {
        return 0;
    }
}
