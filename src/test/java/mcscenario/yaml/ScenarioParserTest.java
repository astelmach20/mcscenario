package mcscenario.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import mcscenario.model.Assertion;
import mcscenario.model.Assertion.Comparison;
import mcscenario.model.Assertion.Quantifier;
import mcscenario.model.Phase;
import mcscenario.model.Probe;
import mcscenario.model.Scenario;
import mcscenario.model.ScenarioException;
import mcscenario.model.ServerConfig;
import mcscenario.model.Step;

class ScenarioParserTest {
    private static final Path BASE = Path.of("scenarios").toAbsolutePath();

    /** Valid scenario; tests append to the single run step ({@code hold}, {@code phases}) or add top-level keys. */
    private static final String MINIMAL = """
        name: demo
        server:
          workDir: srv
          command: [run]
        steps:
          - run:
              name: only
        """;

    private static final String WITH_PROBE = """
        name: demo
        server:
          workDir: srv
          command: [run]
        steps:
          - run:
              name: only
        probes:
          p: 'x'
        assertions:
          - run: only
            probe: p
        """;

    private static Scenario parse(String yaml) {
        return ScenarioParser.parse(yaml, BASE);
    }

    private static void assertError(String yaml, String... fragments) {
        ScenarioException e = assertThrows(ScenarioException.class, () -> parse(yaml));
        for (String fragment : fragments) {
            assertTrue(e.getMessage().contains(fragment), () -> "expected \"" + fragment + "\" in: " + e.getMessage());
        }
    }

    @Test
    void parsesEveryField() {
        Scenario scenario = parse("""
            name: create-logistics-desync
            description: free text
            server:
              workDir: ../create-src
              command: ["./gradlew", "--no-daemon", "runServer"]
              runDir: run/server
              levelName: level
              packFormat: 57
              acceptEula: true
              runTimeout: 90s
              properties: {online-mode: false, max-players: 4, motd: hi, since: 2024-01-01}
              environment: {JAVA_OPTS: -Xmx2G, DEBUG: 1}
            steps:
              - run:
                  name: setup
                  hold: 10s
                  phases:
                    - after: 1s
                      commands: ["forceload add 0 0", "say ready"]
                    - commands: ["say go"]
              - deleteWorldFile: data/create_logistics.dat
              - resetWorld: true
              - run:
                  name: reload
            probes:
              unloaded: '\\[DBG\\] .* unloaded=(?<value>-?\\d+)'
              loaded: 'loaded=(?<value>\\d+)'
            assertions:
              - run: reload
                probe: unloaded
                all: ">= 0"
              - run: setup
                probe: loaded
                count: "== 3"
            """);

        assertEquals("create-logistics-desync", scenario.name());
        assertEquals("free text", scenario.description());

        ServerConfig server = scenario.server();
        assertEquals(BASE.getParent().resolve("create-src"), server.workDir());
        assertEquals(List.of("./gradlew", "--no-daemon", "runServer"), server.command());
        assertEquals(Path.of("run", "server"), server.runDir());
        assertEquals("level", server.levelName());
        assertEquals(57, server.packFormat());
        assertTrue(server.acceptEula());
        assertEquals(Duration.ofSeconds(90), server.runTimeout());
        assertEquals(Map.of("online-mode", "false", "max-players", "4", "motd", "hi", "since", "2024-01-01"), server.properties());
        assertEquals(Map.of("JAVA_OPTS", "-Xmx2G", "DEBUG", "1"), server.environment());

        assertEquals(List.of(
            new Step.Run("setup", List.of(
                new Phase(20, List.of("forceload add 0 0", "say ready")),
                new Phase(0, List.of("say go"))), 200),
            new Step.DeleteWorldFile("data/create_logistics.dat"),
            new Step.ResetWorld(),
            new Step.Run("reload", List.of(), 0)), scenario.steps());

        assertEquals(List.of("unloaded", "loaded"), scenario.probes().stream().map(Probe::name).toList());
        assertEquals("\\[DBG\\] .* unloaded=(?<value>-?\\d+)", scenario.probes().getFirst().pattern().pattern());

        assertEquals(List.of(
            new Assertion("reload", "unloaded", Quantifier.ALL, Comparison.GE, 0),
            new Assertion("setup", "loaded", Quantifier.COUNT, Comparison.EQ, 3)), scenario.assertions());
    }

    @Test
    void appliesDefaults() {
        Scenario scenario = parse(MINIMAL);
        assertEquals("", scenario.description());
        ServerConfig server = scenario.server();
        assertEquals(BASE.resolve("srv"), server.workDir());
        assertEquals(Path.of(""), server.runDir());
        assertEquals(BASE.resolve("srv"), server.resolvedRunDir());
        assertEquals("world", server.levelName());
        assertEquals(48, server.packFormat());
        assertFalse(server.acceptEula());
        assertEquals(Duration.ofMinutes(10), server.runTimeout());
        assertEquals(Map.of(), server.properties());
        assertEquals(Map.of(), server.environment());
        assertEquals(List.of(new Step.Run("only", List.of(), 0)), scenario.steps());
        assertEquals(List.of(), scenario.probes());
        assertEquals(List.of(), scenario.assertions());
    }

    @Test
    void fileOverloadResolvesAgainstFileDirectory(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("s.yaml");
        Files.writeString(file, MINIMAL);
        assertEquals(dir.toAbsolutePath().resolve("srv"), ScenarioParser.parse(file).server().workDir());
    }

    @Test
    void missingFileIsScenarioException(@TempDir Path dir) {
        assertThrows(ScenarioException.class, () -> ScenarioParser.parse(dir.resolve("nope.yaml")));
    }

    @Test
    void rejectsEmptyAndNonMapDocuments() {
        assertError("", "empty");
        assertError("- a\n- b\n", "scenario: expected a map");
    }

    @Test
    void rejectsUnknownKeys() {
        assertError(MINIMAL + "nmae: x\n", "nmae: unknown key");
        assertError(MINIMAL.replace("workDir: srv", "workDir: srv\n  wrkDir: x"), "server.wrkDir: unknown key");
        assertError(MINIMAL + "      holdd: 1t\n", "steps[0].run.holdd: unknown key");
        assertError(MINIMAL + "      phases:\n        - commands: [a]\n          aftr: 1t\n", "steps[0].run.phases[0].aftr: unknown key");
        assertError(MINIMAL + "  - bogus: x\n", "steps[1].bogus: unknown key");
        assertError(WITH_PROBE + "    all: '>= 0'\n    extra: 1\n", "assertions[0].extra: unknown key");
    }

    @Test
    void rejectsDuplicateYamlKeys() {
        assertError(MINIMAL + "name: again\n", "invalid YAML");
    }

    @Test
    void validatesName() {
        assertError(MINIMAL.replace("name: demo", "name: Demo"), "name: must match");
        assertError(MINIMAL.replace("name: demo", "name: -demo"), "name: must match");
        assertError(MINIMAL.replace("name: demo\n", ""), "name: required");
        assertError(MINIMAL.replace("name: demo", "name: [a]"), "name: expected a string, got a list");
    }

    @Test
    void workDirDefaultsToScenarioDirectory() {
        assertEquals(BASE, parse(MINIMAL.replace("  workDir: srv\n", "")).server().workDir());
    }

    @Test
    void validatesServer() {
        assertError(MINIMAL.replace("command: [run]", "command: []"), "server.command: must not be empty");
        assertError(MINIMAL.replace("command: [run]", "command: [run, ' ']"), "server.command[1]: must not be blank");
        assertError(MINIMAL.replace("command: [run]", "command: run"), "server.command: expected a list");
        assertError(MINIMAL.replace("command: [run]", "command: [run]\n  packFormat: 0"), "server.packFormat: must be > 0");
        assertError(MINIMAL.replace("command: [run]", "command: [run]\n  packFormat: x"), "server.packFormat: expected an integer");
        assertError(MINIMAL.replace("command: [run]", "command: [run]\n  acceptEula: yes please"), "server.acceptEula: expected true or false");
        assertError(MINIMAL.replace("command: [run]", "command: [run]\n  properties: {a: [1]}"), "server.properties.a: expected a string, number or boolean");
        assertError(MINIMAL.replace("command: [run]", "command: [run]\n  environment: [a]"), "server.environment: expected a map");
        assertError(MINIMAL.replace("server:\n  workDir: srv\n  command: [run]\n", ""), "server: required");
    }

    @ParameterizedTest
    @CsvSource({"30s, 30", "10m, 600", "2h, 7200"})
    void parsesRunTimeout(String value, long seconds) {
        Scenario scenario = parse(MINIMAL.replace("command: [run]", "command: [run]\n  runTimeout: " + value));
        assertEquals(Duration.ofSeconds(seconds), scenario.server().runTimeout());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0m", "10", "1.5m", "10d", "-1s", "abc"})
    void rejectsBadRunTimeout(String value) {
        assertError(MINIMAL.replace("command: [run]", "command: [run]\n  runTimeout: " + value), "server.runTimeout: expected a positive duration");
    }

    private static int hold(String value) {
        Step.Run run = (Step.Run) parse(MINIMAL + "      hold: " + value + "\n").steps().getFirst();
        return run.holdTicks();
    }

    @ParameterizedTest
    @CsvSource({"0t, 0", "20t, 20", "1s, 20", "1.5s, 30", "0.05s, 1", "0s, 0", "7, 7", "0, 0"})
    void parsesTicks(String value, int ticks) {
        assertEquals(ticks, hold(value));
    }

    @Test
    void rejectsBadTicks() {
        assertError(MINIMAL + "      hold: 0.03s\n", "steps[0].run.hold: \"0.03s\" is not a whole number of ticks");
        assertError(MINIMAL + "      hold: -1t\n", "steps[0].run.hold: expected ticks like \"20t\" or \"1.5s\", got \"-1t\"");
        assertError(MINIMAL + "      hold: -1\n", "steps[0].run.hold: ticks must be >= 0");
        assertError(MINIMAL + "      hold: 1.5\n", "steps[0].run.hold: expected ticks");
        assertError(MINIMAL + "      hold: 99999999999t\n", "steps[0].run.hold: too many ticks");
        assertError(MINIMAL + "  - run:\n      name: two\n      phases:\n        - after: abc\n          commands: [a]\n",
            "steps[1].run.phases[0].after: expected ticks like \"20t\" or \"1.5s\", got \"abc\"");
    }

    @Test
    void validatesSteps() {
        assertError(MINIMAL.replace("steps:\n  - run:\n      name: only\n", "steps: []\n"), "steps: must not be empty");
        assertError(MINIMAL.replace("steps:\n  - run:\n      name: only\n", ""), "steps: required");
        assertError(MINIMAL.replace("steps:\n  - run:\n      name: only\n", "steps:\n  - deleteWorldFile: a.dat\n"),
            "steps: must contain at least one run step");
        assertError(MINIMAL + "  - {run: {name: b}, deleteWorldFile: x}\n", "steps[1]: expected a map with exactly one of");
        assertError(MINIMAL + "  - {}\n", "steps[1]: expected a map with exactly one of");
        assertError(MINIMAL + "  - just a string\n", "steps[1]: expected a map");
    }

    @Test
    void validatesRunSteps() {
        assertError(MINIMAL + "  - run:\n      name: only\n", "steps[1].run.name: duplicate run name \"only\"");
        assertError(MINIMAL + "  - run:\n      hold: 1t\n", "steps[1].run.name: required");
        assertError(MINIMAL + "  - run:\n      name: \"a\\nb\"\n", "steps[1].run.name: must not contain line breaks");
        assertError(MINIMAL + "      phases:\n        - after: 1t\n", "steps[0].run.phases[0].commands: required");
        assertError(MINIMAL + "      phases:\n        - commands: []\n", "steps[0].run.phases[0].commands: must not be empty");
        assertError(MINIMAL + "      phases:\n        - commands: [ok, '/say hi']\n", "steps[0].run.phases[0].commands[1]: must not start with \"/\"");
        assertError(MINIMAL + "      phases:\n        - commands: [\"a\\nb\"]\n", "steps[0].run.phases[0].commands[0]: must be a single line");
    }

    @Test
    void parsesResetWorld() {
        Scenario scenario = parse(MINIMAL + "  - resetWorld: true\n");
        assertEquals(new Step.ResetWorld(), scenario.steps().get(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "yes please", "1", "''", "[true]"})
    void resetWorldMustBeTrue(String value) {
        assertError(MINIMAL + "  - resetWorld: " + value + "\n", "steps[1].resetWorld: expected true");
    }

    @Test
    void resetWorldWithoutValueIsRejected() {
        assertError(MINIMAL + "  - resetWorld:\n", "steps[1].resetWorld: expected true, got nothing");
    }

    @ParameterizedTest
    @ValueSource(strings = {"''", "/abs/file", "../escape.dat", "a/../..", "."})
    void rejectsDeleteOutsideWorld(String path) {
        assertError(MINIMAL + "  - deleteWorldFile: " + path + "\n", "steps[1].deleteWorldFile:");
    }

    @Test
    void validatesProbes() {
        assertError(MINIMAL + "probes:\n  Bad: x\n", "probes.Bad: probe name must match");
        assertError(MINIMAL + "probes:\n  p: '(unclosed'\n", "probes.p: invalid regex");
        assertError(MINIMAL + "probes:\n  p: [x]\n", "probes.p: expected a string");
        assertError(MINIMAL + "probes: [x]\n", "probes: expected a map");
    }

    private static Assertion assertion(String line) {
        return parse(WITH_PROBE + "    " + line + "\n").assertions().getFirst();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "all: '>= 0'     | ALL   | GE | 0",
        "all: '>=0'      | ALL   | GE | 0",
        "any: '  <  -1.5 ' | ANY | LT | -1.5",
        "none: '== 2'    | NONE  | EQ | 2",
        "none: '!=3'     | NONE  | NE | 3",
        "all: '<= 10.25' | ALL   | LE | 10.25",
        "any: '> -7'     | ANY   | GT | -7",
        "count: '== 0'   | COUNT | EQ | 0",
        "count: '>=12'   | COUNT | GE | 12",
    })
    void parsesAssertionOperands(String line, Quantifier quantifier, Comparison comparison, double operand) {
        assertEquals(new Assertion("only", "p", quantifier, comparison, operand), assertion(line));
    }

    @ParameterizedTest
    @ValueSource(strings = {"all: '=> 0'", "all: '= 0'", "all: '>= '", "all: '0'", "all: '>= abc'", "all: 5", "all: '>= 1e3'"})
    void rejectsBadOperands(String line) {
        assertError(WITH_PROBE + "    " + line + "\n", "assertions[0].all:");
    }

    @Test
    void countOperandMustBeNonNegativeInteger() {
        assertError(WITH_PROBE + "    count: '>= -1'\n", "assertions[0].count: count must be compared with a non-negative integer");
        assertError(WITH_PROBE + "    count: '== 1.5'\n", "assertions[0].count: count must be compared with a non-negative integer");
    }

    @Test
    void validatesAssertions() {
        assertError(WITH_PROBE + "    all: '>= 0'\n    any: '>= 0'\n", "assertions[0]: expected exactly one of: all, any, none, count");
        assertError(WITH_PROBE, "assertions[0]: expected exactly one of");
        assertError(WITH_PROBE.replace("- run: only", "- run: missing") + "    all: '>= 0'\n", "assertions[0].run: no run step named \"missing\"");
        assertError(WITH_PROBE.replace("probe: p", "probe: missing") + "    all: '>= 0'\n", "assertions[0].probe: no probe named \"missing\"");
        assertError(WITH_PROBE.replace("    probe: p\n", "") + "    all: '>= 0'\n", "assertions[0].probe: required");
    }

    @Test
    void safeConstructorRejectsJavaTypes() {
        assertError(MINIMAL.replace("workDir: srv", "workDir: !!java.io.File srv"), "invalid YAML");
        assertError("!!java.util.ArrayList []\n", "invalid YAML");
    }
}
