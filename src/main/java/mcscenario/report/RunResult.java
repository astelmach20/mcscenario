package mcscenario.report;

import java.nio.file.Path;
import java.time.Duration;
import java.util.OptionalInt;

/**
 * The outcome of one run step.
 *
 * @param completed    the run-complete marker was seen in the log, i.e. every phase executed
 * @param exitCode     empty if the process was killed or never exited normally
 * @param logFile      captured server log, or {@code null} if the server never started
 * @param probeMatches total number of probe matches in this run
 */
public record RunResult(String name, boolean completed, OptionalInt exitCode, Duration elapsed, Path logFile, int probeMatches) {
}
