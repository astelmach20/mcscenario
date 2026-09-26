package mcscenario.model;

import java.util.List;

/**
 * A reproducible sequence of server runs and world edits, with probes that extract values from the server log
 * and assertions over what those probes captured.
 */
public record Scenario(
    String name,
    String description,
    ServerConfig server,
    List<Step> steps,
    List<Probe> probes,
    List<Assertion> assertions
) {
    public Scenario {
        steps = List.copyOf(steps);
        probes = List.copyOf(probes);
        assertions = List.copyOf(assertions);
    }
}
