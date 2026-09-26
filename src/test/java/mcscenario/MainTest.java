package mcscenario;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {
    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int main(String... args) {
        return Main.run(List.of(args), new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void noArgumentsIsUsageError() {
        assertEquals(Main.EXIT_USAGE, main());
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("usage:"));
    }

    @Test
    void unknownCommandIsUsageError() {
        assertEquals(Main.EXIT_USAGE, main("frobnicate"));
    }

    @Test
    void validateReportsCounts() throws IOException {
        Path file = Files.writeString(dir.resolve("s.yaml"), """
            name: demo
            server:
              command: [java, -jar, server.jar]
              packFormat: 48
            steps:
              - run: {name: a, hold: 1s}
              - deleteWorldFile: data/x.dat
            """);

        assertEquals(Main.EXIT_PASSED, main("validate", file.toString()));
        assertEquals("demo: valid (2 steps, 1 runs, 0 probes, 0 assertions)",
            out.toString(StandardCharsets.UTF_8).strip());
    }

    @Test
    void invalidScenarioIsUsageError() throws IOException {
        Path file = Files.writeString(dir.resolve("s.yaml"), "name: demo\n");

        assertEquals(Main.EXIT_USAGE, main("validate", file.toString()));
        assertTrue(err.toString(StandardCharsets.UTF_8).startsWith("invalid scenario:"));
    }

    @Test
    void runRejectsExtraArguments() {
        assertEquals(Main.EXIT_USAGE, main("run", "a.yaml", "b.yaml"));
    }
}
