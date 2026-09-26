package mcscenario.assertion;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import mcscenario.model.Assertion;
import mcscenario.model.Assertion.Quantifier;

import static mcscenario.model.Assertion.formatNumber;
import mcscenario.model.ProbeMatch;

/** Checks assertions against the probe matches collected from all runs. */
public final class AssertionEvaluator {
    private AssertionEvaluator() {
    }

    public static List<AssertionResult> evaluate(List<Assertion> assertions, List<ProbeMatch> matches) {
        return assertions.stream()
            .map(assertion -> evaluate(assertion, matches.stream()
                .filter(m -> m.run().equals(assertion.run()) && m.probe().equals(assertion.probe()))
                .toList()))
            .toList();
    }

    private static AssertionResult evaluate(Assertion assertion, List<ProbeMatch> matches) {
        if (assertion.quantifier() == Quantifier.COUNT) {
            boolean passed = assertion.comparison().test(matches.size(), assertion.operand());
            return new AssertionResult(assertion, passed, "count was " + matches.size());
        }

        Optional<ProbeMatch> missing = matches.stream().filter(m -> m.value().isEmpty()).findFirst();
        if (missing.isPresent()) {
            return new AssertionResult(assertion, false,
                "line " + missing.get().lineNumber() + " matched but has no numeric \"value\" group");
        }
        if (matches.isEmpty()) {
            return new AssertionResult(assertion, assertion.quantifier() == Quantifier.NONE, "no matches");
        }

        Map<Boolean, List<ProbeMatch>> partition = matches.stream().collect(Collectors.partitioningBy(
            m -> assertion.comparison().test(m.value().getAsDouble(), assertion.operand())));
        List<ProbeMatch> satisfying = partition.get(true);
        List<ProbeMatch> violating = partition.get(false);
        int total = matches.size();
        String condition = assertion.comparison().symbol() + " " + formatNumber(assertion.operand());

        return switch (assertion.quantifier()) {
            case ALL -> violating.isEmpty()
                ? new AssertionResult(assertion, true, total + " values, all " + condition)
                : new AssertionResult(assertion, false, violated(violating, total));
            case ANY -> satisfying.isEmpty()
                ? new AssertionResult(assertion, false, "none of " + total + " values " + condition)
                : new AssertionResult(assertion, true, satisfying.size() + " of " + total + " values " + condition
                    + "; first at " + at(satisfying.getFirst()));
            case NONE -> satisfying.isEmpty()
                ? new AssertionResult(assertion, true, total + " values, none " + condition)
                : new AssertionResult(assertion, false, violated(satisfying, total));
            case COUNT -> throw new AssertionError("handled above");
        };
    }

    private static String violated(List<ProbeMatch> violating, int total) {
        return violating.size() + " of " + total + " values violated; first at " + at(violating.getFirst());
    }

    private static String at(ProbeMatch match) {
        return "line " + match.lineNumber() + ": " + formatNumber(match.value().getAsDouble());
    }
}
