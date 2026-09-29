package tech.kzen.sample.embed.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;


/**
 * {@code kzen.*}: the home directory under which each workspace keeps its notation and scratch, the optional
 * plugin root the process-global runtime is pinned to, and the workspaces themselves (name + loopback port).
 */
@ConfigurationProperties(prefix = "kzen")
public record KzenHostProperties(
        Path home,
        Path pluginRoot,
        List<Workspace> workspaces,
        Host host
) {
    public KzenHostProperties {
        if (home == null) {
            throw new IllegalArgumentException("kzen.home is required");
        }
        host = host == null ? new Host(null, null, null) : host;
        workspaces = workspaces == null ? List.of() : List.copyOf(workspaces);
        long distinctNames = workspaces.stream().map(Workspace::name).distinct().count();
        if (distinctNames != workspaces.size()) {
            throw new IllegalArgumentException("kzen.workspaces names must be distinct: " + workspaces);
        }
    }


    /**
     * The host's own domain services: the ITCH day file they load (optional; without it the services report
     * "no day"), the durable data area for the derived store (default {@code <user.home>/kzen-data/itch}), and the weighted
     * budget every materialization — the host's reports and both kzen workspaces alike — acquires from (bytes;
     * default 4 GiB).
     */
    public record Host(Path dayFile, Path dataRoot, Long budgetBytes) {
        public static final long defaultBudgetBytes = 4L << 30;

        public Host {
            if (budgetBytes != null && budgetBytes <= 0) {
                throw new IllegalArgumentException("kzen.host.budget-bytes must be positive: " + budgetBytes);
            }
        }

        public long budgetBytesOrDefault() {
            return budgetBytes == null ? defaultBudgetBytes : budgetBytes;
        }
    }


    /** A workspace: its name (the URL prefix segment and its directory under the home), its loopback port,
     *  and optionally an explicit work root (default {@code <home>/<name>/work}; two workspaces on one root is
     *  the documented failure, not a sharing mode). */
    public record Workspace(String name, int port, Path workRoot) {
        public Workspace {
            if (name == null || name.isBlank() || !name.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("Workspace name must be a plain path segment: '" + name + "'");
            }
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Workspace '" + name + "' needs a loopback port: " + port);
            }
        }
    }
}
