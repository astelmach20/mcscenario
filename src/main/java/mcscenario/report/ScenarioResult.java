package mcscenario.report;

import java.util.List;

import mcscenario.assertion.AssertionResult;

/** Everything a scenario execution produced. */
public record ScenarioResult(String scenario, List<RunResult> runs, List<AssertionResult> assertions) {
    public ScenarioResult {
        runs = List.copyOf(runs);
        assertions = List.copyOf(assertions);
    }

    /** Every run completed and every assertion passed. */
    public boolean passed() {
        return runs.stream().allMatch(RunResult::completed) && assertions.stream().allMatch(AssertionResult::passed);
    }
}
