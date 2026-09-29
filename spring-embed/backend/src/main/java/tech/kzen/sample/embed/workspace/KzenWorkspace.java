package tech.kzen.sample.embed.workspace;

import io.ktor.server.application.Application;
import io.ktor.server.cio.CIO;
import io.ktor.server.engine.EmbeddedServer;
import io.ktor.server.engine.EmbeddedServerKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kzen.auto.server.KzenAutoMainKt;
import tech.kzen.auto.server.context.KzenAutoConfig;
import tech.kzen.auto.server.context.KzenAutoContext;
import tech.kzen.auto.server.context.KzenAutoHost;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.TimeUnit;


/**
 * One embedded workspace: a {@link KzenAutoContext} over its own module root ({@code <dir>/src/main/resources/
 * notation/main}, what kzen's locator expects) and work root ({@code <dir>/work}, claimed on the process-global
 * runtime), served by its own loopback CIO server. The host owns logging, so kzen's managed {@code logs/} area
 * is off. Start is context then server; stop is server (stop, await) then context (cancel and join the run,
 * release the claim) — the standalone order, under the host's lifecycle instead of a shutdown hook.
 */
public final class KzenWorkspace {
    private static final Logger logger = LoggerFactory.getLogger(KzenWorkspace.class);
    private static final String host = "127.0.0.1";
    private static final String jsModuleName = "kzen-sample-embed-ui";
    private static final long stopGraceMillis = 1_000;
    private static final long stopTimeoutMillis = 5_000;

    private final String name;
    private final int port;
    private final Path directory;
    private final Path workRoot;
    private final KzenAutoHost services;

    private KzenAutoContext context;
    private EmbeddedServer<?, ?> server;


    public KzenWorkspace(String name, int port, Path directory, Path workRootOrNull, KzenAutoHost services) {
        this.name = name;
        this.port = port;
        this.directory = directory;
        this.workRoot = workRootOrNull == null ? directory.resolve("work") : workRootOrNull;
        this.services = services;
    }


    public String name() {
        return name;
    }

    public int port() {
        return port;
    }

    public Path directory() {
        return directory;
    }

    public synchronized boolean isRunning() {
        return server != null;
    }


    /** Creates the context (claiming the work root) and binds the server; a failure leaves nothing behind. */
    public synchronized void start() {
        if (server != null) {
            return;
        }
        Path moduleRoot = directory;
        try {
            Files.createDirectories(moduleRoot.resolve("src/main/resources/notation/main"));
            Files.createDirectories(workRoot);
        }
        catch (IOException e) {
            throw new IllegalStateException("Workspace '" + name + "': cannot create " + directory, e);
        }

        KzenAutoContext created = KzenAutoContext.Companion.create(new KzenAutoConfig(
                jsModuleName,
                port,
                host,
                moduleRoot,
                false,
                null,
                null,
                workRoot,
                services,
                false));
        try {
            // Ktor's module parameter is a suspend lambda; from Java that is a Function2 taking the continuation.
            Function2<Application, Continuation<? super Unit>, Object> module = (application, continuation) -> {
                KzenAutoMainKt.ktorMain(application, created);
                return Unit.INSTANCE;
            };
            EmbeddedServer<?, ?> bound = EmbeddedServerKt.embeddedServer(
                    CIO.INSTANCE, port, host, Collections.<String>emptyList(), module);
            bound.start(false);
            context = created;
            server = bound;
        }
        catch (RuntimeException e) {
            created.close();
            throw new IllegalStateException("Workspace '" + name + "': server did not start on " + host + ":" + port, e);
        }
        logger.info("Workspace '{}' serving http://{}:{} from {}", name, host, port, directory);
    }


    /** Stops the server (grace, then timeout), closes the context (cancel, join, release), reports the claim. */
    public synchronized void stop() {
        if (server == null) {
            return;
        }
        EmbeddedServer<?, ?> running = server;
        KzenAutoContext open = context;
        server = null;
        context = null;
        try {
            running.stop(stopGraceMillis, stopTimeoutMillis, TimeUnit.MILLISECONDS);
        }
        finally {
            open.close();
        }
        if (open.isWorkRootReleased()) {
            logger.info("Workspace '{}' stopped; work root released", name);
        }
        else {
            logger.error("Workspace '{}' stopped, but its run did not join; work root {} stays claimed", name, workRoot);
        }
    }
}
