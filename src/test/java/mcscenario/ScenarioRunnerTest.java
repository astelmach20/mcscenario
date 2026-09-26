package mcscenario;

import mcscenario.assertion.AssertionResult;
import mcscenario.model.Scenario;
import mcscenario.report.RunResult;
import mcscenario.report.ScenarioResult;
import mcscenario.yaml.ScenarioParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioRunnerTest {
    @TempDir
    Path dir;

    private Scenario scenario(String body) {
        String command = FakeMinecraft.javaCommand("run").stream()
            .map(arg -> "'" + arg.replace("'", "''") + "'")
            .collect(Collectors.joining(", ", "[", "]"));
        String yaml = """
            name: fake
            server:
              command: %s
              runDir: run
              packFormat: 48
              runTimeout: 1m
            %s""".formatted(command, body);
        return ScenarioParser.parse(yaml, dir);
    }

    private ScenarioResult run(Scenario scenario) {
        PrintStream console = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        return new ScenarioRunner(scenario, dir.resolve("out"), console).run();
    }

    @Test
    void runsPhasesAcrossRestartsAndEvaluatesAssertions() throws IOException {
        ScenarioResult result = run(scenario("""
            steps:
              - resetWorld: true
              - run:
                  name: first
                  phases:
                    - after: 1s
                      commands: [say value=5, time set day]
                  hold: 1s
              - deleteWorldFile: level.dat
              - run:
                  name: second
                  phases:
                    - commands: [say value=-1]
            probes:
              value: 'value=(?<value>-?\\d+)'
              existed: 'existed=(?<value>\\d)'
            assertions:
              - {run: first, probe: value, all: ">= 0"}
              - {run: second, probe: value, all: ">= 0"}
              - {run: second, probe: existed, all: "== 0"}
            """));

        assertEquals(List.of("first", "second"), result.runs().stream().map(RunResult::name).toList());
        assertTrue(result.runs().stream().allMatch(RunResult::completed));
        assertEquals(List.of(true, false, true), result.assertions().stream().map(AssertionResult::passed).toList());
        assertFalse(result.passed());

        Path world = dir.resolve("run/world");
        assertFalse(Files.exists(world.resolve("datapacks/mcscenario")), "pack must be removed after the scenario");
        assertTrue(Files.readString(dir.resolve("run/server.properties")).contains("function-permission-level=4"));
        assertTrue(Files.readString(dir.resolve("out/fake-first.log")).contains("ran time set day"));
    }

    @Test
    void stopsAfterRunThatDoesNotComplete() {
        ScenarioResult result = run(scenario("""
            steps:
              - run:
                  name: broken
                  phases:
                    - commands: [crash]
              - run: {name: never, hold: 1t}
            """));

        RunResult broken = result.runs().getFirst();
        assertEquals(1, result.runs().size());
        assertFalse(broken.completed());
        assertEquals(2, broken.exitCode().orElseThrow());
        assertFalse(result.passed());
    }
}
