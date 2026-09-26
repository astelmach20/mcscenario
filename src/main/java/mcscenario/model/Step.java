package mcscenario.model;

import java.util.List;

/** One step of a scenario: either a server run or an edit to the world on disk between runs. */
public sealed interface Step {
    /**
     * Launches the server, executes {@code phases} in order from an injected datapack, waits {@code holdTicks},
     * then saves and stops the server from inside the game so no data is lost.
     */
    record Run(String name, List<Phase> phases, int holdTicks) implements Step {
        public Run {
            phases = List.copyOf(phases);
        }
    }

    /** Deletes a file or directory under the world folder, e.g. to simulate lost or rolled-back saved data. */
    record DeleteWorldFile(String path) implements Step {
    }
}
