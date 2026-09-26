package mcscenario.assertion;

import mcscenario.model.Assertion;

/** The outcome of one assertion, with a short human-readable explanation. */
public record AssertionResult(Assertion assertion, boolean passed, String detail) {
}
