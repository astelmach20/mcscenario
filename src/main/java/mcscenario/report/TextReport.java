package mcscenario.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import mcscenario.assertion.AssertionResult;

/** Compact human-readable summary. ASCII only, so it renders on any console. */
public final class TextReport {
    private static final int ELAPSED_COLUMN = 4;

    private TextReport() {
    }

    public static String render(ScenarioResult result) {
        String verdict = verdict(result.passed());
        StringBuilder out = new StringBuilder();
        out.append("scenario ").append(result.scenario()).append(": ").append(verdict).append('\n');

        List<List<String>> rows = new ArrayList<>();
        for (RunResult run : result.runs()) {
            rows.add(List.of(
                "run",
                run.name(),
                run.completed() ? "completed" : "INCOMPLETE",
                run.exitCode().isPresent() ? "exit " + run.exitCode().getAsInt() : "no exit",
                String.format(Locale.ROOT, "%.1fs", run.elapsed().toMillis() / 1000.0),
                run.probeMatches() + (run.probeMatches() == 1 ? " match" : " matches")));
        }
        table(out, rows);

        for (AssertionResult assertion : result.assertions()) {
            out.append("  ").append(verdict(assertion.passed())).append(' ')
                .append(assertion.assertion().describe()).append(" -- ").append(assertion.detail()).append('\n');
        }

        long incomplete = result.runs().stream().filter(run -> !run.completed()).count();
        long failed = result.assertions().stream().filter(assertion -> !assertion.passed()).count();
        out.append(verdict).append(": ")
            .append(result.runs().size() - incomplete).append(" of ").append(result.runs().size()).append(" runs completed, ")
            .append(result.assertions().size() - failed).append(" of ").append(result.assertions().size())
            .append(" assertions passed\n");
        return out.toString();
    }

    private static String verdict(boolean passed) {
        return passed ? "PASS" : "FAIL";
    }

    private static void table(StringBuilder out, List<List<String>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        int columns = rows.getFirst().size();
        int[] widths = new int[columns];
        for (List<String> row : rows) {
            for (int i = 0; i < columns; i++) {
                widths[i] = Math.max(widths[i], row.get(i).length());
            }
        }
        for (List<String> row : rows) {
            StringBuilder line = new StringBuilder("  ");
            for (int i = 0; i < columns; i++) {
                String cell = row.get(i);
                line.append(i == ELAPSED_COLUMN ? " ".repeat(widths[i] - cell.length()) + cell : cell + " ".repeat(widths[i] - cell.length()));
                line.append(i == 0 ? " " : "  ");
            }
            out.append(line.toString().stripTrailing()).append('\n');
        }
    }
}
