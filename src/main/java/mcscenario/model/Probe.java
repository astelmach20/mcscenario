package mcscenario.model;

import java.util.regex.Pattern;

/**
 * Extracts a value from matching server log lines. ANSI colour codes are stripped before matching.
 * If the pattern has a named group {@code value}, it is parsed as a number and used by numeric assertions.
 */
public record Probe(String name, Pattern pattern) {
}
