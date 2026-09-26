package mcscenario.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;

import mcscenario.assertion.AssertionResult;

/** Machine-readable report, pretty-printed with a two-space indent. */
public final class JsonReport {
    private JsonReport() {
    }

    public static void write(ScenarioResult result, Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, toJson(result) + "\n");
    }

    public static String toJson(ScenarioResult result) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        field(out, 1, "scenario", string(result.scenario()), true);
        field(out, 1, "passed", Boolean.toString(result.passed()), true);
        indent(out, 1).append("\"runs\": ");
        array(out, 1, result.runs(), JsonReport::run);
        out.append(",\n");
        indent(out, 1).append("\"assertions\": ");
        array(out, 1, result.assertions(), JsonReport::assertion);
        out.append("\n}");
        return out.toString();
    }

    private static void run(StringBuilder out, RunResult run) {
        field(out, 3, "name", string(run.name()), true);
        field(out, 3, "completed", Boolean.toString(run.completed()), true);
        field(out, 3, "exitCode", run.exitCode().isPresent() ? Integer.toString(run.exitCode().getAsInt()) : "null", true);
        field(out, 3, "elapsedMillis", Long.toString(run.elapsed().toMillis()), true);
        field(out, 3, "logFile", run.logFile() == null ? "null" : string(run.logFile().toString()), true);
        field(out, 3, "probeMatches", Integer.toString(run.probeMatches()), false);
    }

    private static void assertion(StringBuilder out, AssertionResult result) {
        field(out, 3, "description", string(result.assertion().describe()), true);
        field(out, 3, "passed", Boolean.toString(result.passed()), true);
        field(out, 3, "detail", string(result.detail()), false);
    }

    private static <T> void array(StringBuilder out, int level, List<T> items, BiConsumer<StringBuilder, T> writer) {
        if (items.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append("[\n");
        for (int i = 0; i < items.size(); i++) {
            indent(out, level + 1).append("{\n");
            writer.accept(out, items.get(i));
            indent(out, level + 1).append(i < items.size() - 1 ? "},\n" : "}\n");
        }
        indent(out, level).append(']');
    }

    private static void field(StringBuilder out, int level, String name, String jsonValue, boolean more) {
        indent(out, level).append(string(name)).append(": ").append(jsonValue).append(more ? ",\n" : "\n");
    }

    private static StringBuilder indent(StringBuilder out, int level) {
        return out.append("  ".repeat(level));
    }

    static String string(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u%04x".formatted((int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
