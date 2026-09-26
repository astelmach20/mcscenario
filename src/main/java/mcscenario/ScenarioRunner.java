package mcscenario;

import mcscenario.assertion.AssertionEvaluator;
import mcscenario.datapack.DatapackWriter;
import mcscenario.datapack.ServerProperties;
import mcscenario.datapack.WorldFiles;
import mcscenario.model.Scenario;
import mcscenario.model.ScenarioException;
import mcscenario.model.ServerConfig;
import mcscenario.model.Step;
import mcscenario.probe.ProbeRecorder;
import mcscenario.report.RunResult;
import mcscenario.report.ScenarioResult;
import mcscenario.server.ServerProcess;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;

/** Executes a scenario's steps in order against a real server and evaluates its assertions. */
public final class ScenarioRunner {
    /** The injected datapack stops the server with {@code stop}, which needs permission level 4. */
    static final String FUNCTION_PERMISSION_LEVEL = "4";

    private final Scenario scenario;
    private final Path outputDir;
    private final PrintStream console;

    public ScenarioRunner(Scenario scenario, Path outputDir, PrintStream console) {
        this.scenario = scenario;
        this.outputDir = outputDir;
        this.console = console;
    }

    public ScenarioResult run() {
        ServerConfig server = scenario.server();
        Path worldDir = server.worldDir();
        try {
            Files.createDirectories(outputDir);
            Files.createDirectories(server.resolvedRunDir());
            ServerProperties.merge(server.resolvedRunDir().resolve("server.properties"), serverProperties(server));
            if (server.acceptEula()) {
                ServerProperties.acceptEula(server.resolvedRunDir());
            }
        } catch (IOException e) {
            throw new ScenarioException("could not prepare server directory " + server.resolvedRunDir(), e);
        }

        ProbeRecorder recorder = new ProbeRecorder(scenario.probes());
        List<RunResult> runs = new ArrayList<>();
        try {
            for (Step step : scenario.steps()) {
                boolean proceed = switch (step) {
                    case Step.Run run -> {
                        RunResult result = execute(run, recorder);
                        runs.add(result);
                        yield result.completed();
                    }
                    case Step.DeleteWorldFile delete -> {
                        boolean deleted = WorldFiles.delete(worldDir, delete.path());
                        console.printf("delete %s: %s%n", delete.path(), deleted ? "deleted" : "not present");
                        yield true;
                    }
                    case Step.ResetWorld ignored -> {
                        boolean existed = WorldFiles.reset(worldDir);
                        console.printf("reset world %s: %s%n", worldDir, existed ? "deleted" : "not present");
                        yield true;
                    }
                };
                if (!proceed) {
                    console.println("run did not complete; skipping remaining steps");
                    break;
                }
            }
        } finally {
            // Left in place, the pack would stop the developer's next ordinary server session on load.
            WorldFiles.delete(worldDir, DatapackWriter.PACK_PATH);
        }

        return new ScenarioResult(scenario.name(), runs, AssertionEvaluator.evaluate(scenario.assertions(), recorder.matches()));
    }

    private Map<String, String> serverProperties(ServerConfig server) {
        String permissionLevel = server.properties().get("function-permission-level");
        if (permissionLevel != null && !permissionLevel.equals(FUNCTION_PERMISSION_LEVEL)) {
            throw new ScenarioException("server.properties.function-permission-level must be "
                + FUNCTION_PERMISSION_LEVEL + " so the scenario can stop the server; got " + permissionLevel);
        }
        Map<String, String> properties = new LinkedHashMap<>(server.properties());
        properties.put("level-name", server.levelName());
        properties.put("function-permission-level", FUNCTION_PERMISSION_LEVEL);
        return properties;
    }

    private RunResult execute(Step.Run run, ProbeRecorder recorder) {
        ServerConfig server = scenario.server();
        DatapackWriter.write(server.worldDir(), run, server.packFormat());
        Path log = outputDir.resolve(scenario.name() + "-" + DatapackWriter.slug(run.name()) + ".log");
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();

        console.printf("run %s: starting %s%n", run.name(), String.join(" ", server.command()));
        long startNanos = System.nanoTime();
        OptionalInt exitCode;
        try (ServerProcess process = ServerProcess.start(server, log, (lineNumber, line) -> {
            if (line.contains(DatapackWriter.RUN_START_MARKER + run.name())) {
                started.set(true);
            } else if (line.contains(DatapackWriter.RUN_COMPLETE_MARKER + run.name())) {
                completed.set(true);
            }
            recorder.accept(run.name(), lineNumber, line);
        })) {
            exitCode = process.awaitExit(server.runTimeout());
            if (exitCode.isEmpty()) {
                console.printf("run %s: timed out after %s, killing process tree%n", run.name(), server.runTimeout());
                process.killTree();
            }
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        if (!started.get()) {
            console.printf("run %s: the injected datapack never ran; check the server log (%s) for a pack_format "
                + "mismatch or a startup crash%n", run.name(), log);
        }
        return new RunResult(run.name(), completed.get(), exitCode, elapsed, log, recorder.matches(run.name()).size());
    }
}
