package tech.kzen.sample.plugin;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * The packaged plugin directory checked the way the host sees it, with kzen-auto's compatibility kit — in child
 * JVMs, since a plugin universe is pinned once per process: the folder install (only the host on the class
 * path) and the same jars on the application class path (plugin zero), each verified end to end (runtime pinned,
 * a standalone context, classes resolved, expression identity proven). Runs after `package` over `target/`;
 * the host jars come from `kzen.auto.libs` (a kzen-auto-jvm `build/libs` with its `dependencies/`), and the
 * test is skipped, not failed, when that directory is absent.
 */
class PluginDirectoryIT {
    private static final String kit = "tech.kzen.auto.server.context.runtime.kit.PluginCompatibilityKit";
    private static final List<String> readers = List.of(
            "tech.kzen.sample.itch-5@1", "tech.kzen.sample.world-cities@1");
    private static final List<String> documents = List.of(
            "auto-jvm/kzen-sample-plugin/sample-formats.yaml", "auto-jvm/kzen-sample-plugin/sample-workers.yaml");
    private static final List<String> classes = List.of(
            "tech.kzen.sample.plugin.itch.ItchFormat",
            "tech.kzen.sample.plugin.cities.WorldCitiesFormat",
            "tech.kzen.sample.plugin.analysis.ItchTradeVolumeWorker",
            "tech.kzen.sample.plugin.analysis.SymbolDayTradeVolumeWorker",
            "tech.kzen.sample.plugin.analysis.SymbolDayOrderLifecycleWorker",
            "tech.kzen.sample.plugin.analysis.SymbolDayBookSnapshotWorker",
            "tech.kzen.sample.plugin.analysis.SymbolDayOrdersWorker");
    private static final List<String> expressionClasses = List.of(
            "tech.kzen.sample.itch.day.SymbolDays", "tech.kzen.sample.itch.wire.ItchReader");

    private static List<Path> hostJars;
    private static Path adapterJar;
    private static Path coreJar;

    @TempDir
    static Path temp;


    @BeforeAll
    static void locateJars() throws IOException {
        String libs = System.getProperty("kzen.auto.libs", "");
        Path libsDir = libs.isBlank() ? null : Path.of(libs);
        Assumptions.assumeTrue(libsDir != null && Files.isDirectory(libsDir),
                "kzen.auto.libs must name a kzen-auto-jvm build/libs directory (with dependencies/)");
        hostJars = new ArrayList<>();
        try (Stream<Path> files = Files.list(libsDir)) {
            files.filter(p -> p.getFileName().toString().matches("kzen-auto-jvm-[0-9.]+(-SNAPSHOT)?\\.jar"))
                    .forEach(hostJars::add);
        }
        try (Stream<Path> files = Files.list(libsDir.resolve("dependencies"))) {
            files.filter(p -> p.toString().endsWith(".jar")).forEach(hostJars::add);
        }
        Assumptions.assumeTrue(hostJars.size() > 1, "kzen-auto-jvm jar and its dependencies/ expected under " + libsDir);

        Path target = Path.of("target").toAbsolutePath();
        try (Stream<Path> files = Files.list(target)) {
            adapterJar = files.filter(p -> p.getFileName().toString().matches("kzen-sample-adapter-.*(?<!-tests)\\.jar"))
                    .findFirst().orElseThrow();
        }
        try (Stream<Path> files = Files.list(target.resolve("lib"))) {
            coreJar = files.filter(p -> p.getFileName().toString().startsWith("kzen-sample-core-")).findFirst().orElseThrow();
        }
    }


    @Test
    void folderPluginVerifiesInItsOwnJvm() throws Exception {
        Path root = temp.resolve("folder-root");
        Path folder = root.resolve("kzen-sample");
        Files.createDirectories(folder);
        Files.copy(adapterJar, folder.resolve(adapterJar.getFileName()));
        Files.copy(coreJar, folder.resolve(coreJar.getFileName()));

        Result result = kit(hostJars, root, "--verify", "--expect-scope=kzen-sample");
        assertEquals(0, result.exit, result.output);
        assertTrue(result.output.contains("## kzen-sample — loaded"), result.output);
        assertTrue(result.output.contains("- version: 0.0.1"), result.output);
        assertTrue(result.output.contains("- spi: 1"), result.output);
    }


    @Test
    void applicationClasspathHostsTheSameContributions() throws Exception {
        Path root = temp.resolve("empty-root");
        Files.createDirectories(root);
        List<Path> classpath = new ArrayList<>(hostJars);
        classpath.add(adapterJar);
        classpath.add(coreJar);

        Result result = kit(classpath, root, "--verify");
        assertEquals(0, result.exit, result.output);
        assertTrue(result.output.contains("(application classpath) — loaded"), result.output);
        assertTrue(result.output.contains("- reader: " + readers.get(0)), result.output);
    }


    private Result kit(List<Path> classpath, Path root, String... flags) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElseThrow());
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-cp");
        command.add(String.join(java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList()));
        command.add(kit);
        command.add(root.toString());
        command.addAll(List.of(flags));
        for (String reader : readers) command.add("--expect-reader=" + reader);
        for (String document : documents) command.add("--expect-document=" + document);
        for (String klass : classes) command.add("--expect-class=" + klass);
        for (String klass : expressionClasses) command.add("--expect-expression=" + klass);

        Path log = temp.resolve("kit-" + System.nanoTime() + ".log");
        Process process = new ProcessBuilder(command)
                .directory(temp.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        assertTrue(process.waitFor(5, TimeUnit.MINUTES), "kit did not finish in time");
        return new Result(process.exitValue(), new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
    }


    private record Result(int exit, String output) {}
}
