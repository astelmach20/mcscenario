package mcscenario.assertion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;

import mcscenario.model.Assertion;
import mcscenario.model.Assertion.Comparison;
import mcscenario.model.Assertion.Quantifier;
import mcscenario.model.ProbeMatch;

class AssertionEvaluatorTest {

    private static ProbeMatch match(String run, String probe, long line, Double value) {
        return new ProbeMatch(run, probe, line, "line " + line, Map.of(),
            value == null ? OptionalDouble.empty() : OptionalDouble.of(value));
    }

    private static ProbeMatch match(long line, Double value) {
        return match("r", "p", line, value);
    }

    private static AssertionResult evaluate(Quantifier quantifier, Comparison comparison, double operand, ProbeMatch... matches) {
        Assertion assertion = new Assertion("r", "p", quantifier, comparison, operand);
        List<AssertionResult> results = AssertionEvaluator.evaluate(List.of(assertion), List.of(matches));
        assertEquals(1, results.size());
        assertEquals(assertion, results.getFirst().assertion());
        return results.getFirst();
    }

    private static void assertResult(boolean passed, String detail, AssertionResult result) {
        assertEquals(detail, result.detail());
        assertEquals(passed, result.passed());
    }

    @Test
    void allPasses() {
        assertResult(true, "3 values, all >= 0",
            evaluate(Quantifier.ALL, Comparison.GE, 0, match(1, 0.0), match(2, 5.0), match(3, 7.0)));
    }

    @Test
    void allFailsWithFirstViolation() {
        assertResult(false, "2 of 3 values violated; first at line 20: -1",
            evaluate(Quantifier.ALL, Comparison.GE, 0, match(10, 1.0), match(20, -1.0), match(30, -2.5)));
    }

    @Test
    void allFailsWithoutMatches() {
        assertResult(false, "no matches", evaluate(Quantifier.ALL, Comparison.GE, 0));
    }

    @Test
    void anyPassesAndFails() {
        assertResult(true, "1 of 2 values > 1.5; first at line 2: 2",
            evaluate(Quantifier.ANY, Comparison.GT, 1.5, match(1, 1.0), match(2, 2.0)));
        assertResult(false, "none of 2 values > 1.5",
            evaluate(Quantifier.ANY, Comparison.GT, 1.5, match(1, 1.0), match(2, 1.5)));
        assertResult(false, "no matches", evaluate(Quantifier.ANY, Comparison.GT, 1.5));
    }

    @Test
    void nonePassesAndFails() {
        assertResult(true, "2 values, none < 0",
            evaluate(Quantifier.NONE, Comparison.LT, 0, match(1, 0.0), match(2, 3.0)));
        assertResult(false, "1 of 2 values violated; first at line 2: -0.5",
            evaluate(Quantifier.NONE, Comparison.LT, 0, match(1, 0.0), match(2, -0.5)));
        assertResult(true, "no matches", evaluate(Quantifier.NONE, Comparison.LT, 0));
    }

    @Test
    void countComparesNumberOfMatches() {
        assertResult(true, "count was 0", evaluate(Quantifier.COUNT, Comparison.EQ, 0));
        assertResult(false, "count was 2", evaluate(Quantifier.COUNT, Comparison.EQ, 0, match(1, null), match(2, 3.0)));
        assertResult(true, "count was 2", evaluate(Quantifier.COUNT, Comparison.GE, 2, match(1, null), match(2, 3.0)));
    }

    @Test
    void missingValueFailsValueQuantifiers() {
        for (Quantifier quantifier : List.of(Quantifier.ALL, Quantifier.ANY, Quantifier.NONE)) {
            assertResult(false, "line 57 matched but has no numeric \"value\" group",
                evaluate(quantifier, Comparison.GE, 0, match(1, 1.0), match(57, null)));
        }
    }

    @Test
    void filtersByRunAndProbe() {
        Assertion assertion = new Assertion("r", "p", Quantifier.ALL, Comparison.GE, 0);
        List<ProbeMatch> matches = List.of(
            match("r", "p", 1, 1.0),
            match("other", "p", 2, -1.0),
            match("r", "q", 3, -1.0));
        AssertionResult result = AssertionEvaluator.evaluate(List.of(assertion), matches).getFirst();
        assertResult(true, "1 values, all >= 0", result);
    }

    @Test
    void preservesAssertionOrder() {
        Assertion a = new Assertion("r", "p", Quantifier.COUNT, Comparison.EQ, 1);
        Assertion b = new Assertion("r", "p", Quantifier.COUNT, Comparison.EQ, 2);
        List<AssertionResult> results = AssertionEvaluator.evaluate(List.of(a, b), List.of(match(1, 1.0)));
        assertEquals(List.of(a, b), results.stream().map(AssertionResult::assertion).toList());
        assertEquals(List.of(true, false), results.stream().map(AssertionResult::passed).toList());
    }
}
