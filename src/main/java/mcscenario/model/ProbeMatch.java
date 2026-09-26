package mcscenario.model;

import java.util.Map;
import java.util.OptionalDouble;

/** One log line that matched a probe during a run. */
public record ProbeMatch(String run, String probe, long lineNumber, String line, Map<String, String> groups, OptionalDouble value) {
    public ProbeMatch {
        groups = Map.copyOf(groups);
    }
}
