package mcscenario.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mcscenario.assertion.AssertionResult;
import mcscenario.model.Assertion;
import mcscenario.model.Assertion.Comparison;
import mcscenario.model.Assertion.Quantifier;

class ReportTest {
    private static final Assertion ALL_GE_0 = new Assertion("reload", "unloaded", Quantifier.ALL, Comparison.GE, 0);

    private static ScenarioResult sample() {
        return new ScenarioResult("demo",
            List.of(
                new RunResult("setup", true, OptionalInt.of(0), Duration.ofMillis(42_100), Path.of("setup.log"), 3),
                new RunResult("reload", false, OptionalInt.empty(), Duration.ofMillis(38_700), null, 1)),
            List.of(new AssertionResult(ALL_GE_0, false, "1 of 1 values violated; first at line 7: -1")));
    }

    @Test
    void passedRequiresCompletedRunsAndPassingAssertions() {
        RunResult ok = new RunResult("a", true, OptionalInt.of(0), Duration.ZERO, null, 0);
        RunResult incomplete = new RunResult("b", false, OptionalInt.of(0), Duration.ZERO, null, 0);
        AssertionResult pass = new AssertionResult(ALL_GE_0, true, "fine");
        AssertionResult fail = new AssertionResult(ALL_GE_0, false, "bad");
        assertTrue(new ScenarioResult("s", List.of(ok), List.of(pass)).passed());
        assertTrue(new ScenarioResult("s", List.of(ok), List.of()).passed());
        assertFalse(new ScenarioResult("s", List.of(ok, incomplete), List.of(pass)).passed());
        assertFalse(new ScenarioResult("s", List.of(ok), List.of(pass, fail)).passed());
    }

    @Test
    void jsonHasExactStructure() {
        assertEquals("""
            {
              "scenario": "demo",
              "passed": false,
              "runs": [
                {
                  "name": "setup",
                  "completed": true,
                  "exitCode": 0,
                  "elapsedMillis": 42100,
                  "logFile": "setup.log",
                  "probeMatches": 3
                },
                {
                  "name": "reload",
                  "completed": false,
                  "exitCode": null,
                  "elapsedMillis": 38700,
                  "logFile": null,
                  "probeMatches": 1
                }
              ],
              "assertions": [
                {
                  "description": "reload/unloaded: all values >= 0",
                  "passed": false,
                  "detail": "1 of 1 values violated; first at line 7: -1"
                }
              ]
            }""", JsonReport.toJson(sample()));
    }

    @Test
    void jsonEmptyArrays() {
        assertEquals("""
            {
              "scenario": "s",
              "passed": true,
              "runs": [],
              "assertions": []
            }""", JsonReport.toJson(new ScenarioResult("s", List.of(), List.of())));
    }

    @Test
    void jsonEscapesStrings() {
        assertEquals("\"a\\\"b\\\\c\\u000ad\\u0009e\\u0000f\\u001fé\"",
            JsonReport.string("a\"b\\c\nd\te\0f\u001fé"));
    }

    @Test
    void writeCreatesFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("out/report.json");
        JsonReport.write(sample(), file);
        assertEquals(JsonReport.toJson(sample()) + "\n", Files.readString(file));
    }

    @Test
    void textReportShape() {
        assertEquals("""
            scenario demo: FAIL
              run setup   completed   exit 0   42.1s  3 matches
              run reload  INCOMPLETE  no exit  38.7s  1 match
              FAIL reload/unloaded: all values >= 0 -- 1 of 1 values violated; first at line 7: -1
            FAIL: 1 of 2 runs completed, 0 of 1 assertions passed
            """, TextReport.render(sample()));
    }

    @Test
    void textReportPassing() {
        ScenarioResult result = new ScenarioResult("ok",
            List.of(new RunResult("only", true, OptionalInt.of(0), Duration.ofMillis(5_000), null, 639)),
            List.of(new AssertionResult(ALL_GE_0, true, "639 values, all >= 0")));
        String text = TextReport.render(result);
        assertTrue(text.startsWith("scenario ok: PASS\n"), text);
        assertTrue(text.contains("  PASS reload/unloaded: all values >= 0 -- 639 values, all >= 0\n"), text);
        assertTrue(text.endsWith("PASS: 1 of 1 runs completed, 1 of 1 assertions passed\n"), text);
        assertTrue(text.chars().allMatch(c -> c < 128), "ASCII only");
    }
}
