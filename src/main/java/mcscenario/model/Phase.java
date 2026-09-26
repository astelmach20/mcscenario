package mcscenario.model;

import java.util.List;

/**
 * Commands run together on one tick.
 *
 * @param delayTicks ticks to wait after the previous phase (or after the datapack loads, for the first phase)
 * @param commands   commands without a leading slash, executed at function permission level
 */
public record Phase(int delayTicks, List<String> commands) {
    public Phase {
        if (delayTicks < 0) {
            throw new IllegalArgumentException("delayTicks must be >= 0, was " + delayTicks);
        }
        commands = List.copyOf(commands);
    }
}
