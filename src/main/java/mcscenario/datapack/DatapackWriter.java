package mcscenario.datapack;

import mcscenario.model.Phase;
import mcscenario.model.ScenarioException;
import mcscenario.model.Step;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes the datapack that drives one run from inside the game: it announces the run, executes each phase on its
 * tick schedule, then saves and stops the server. Commands reach the server this way however it was launched.
 */
public final class DatapackWriter {
    public static final String RUN_START_MARKER = "[mcscenario] run-start ";
    public static final String RUN_COMPLETE_MARKER = "[mcscenario] run-complete ";

    static final String NAMESPACE = "mcscenario";

    /** Pre-1.21 servers read the plural directory names, 1.21+ the singular ones. */
    private static final List<String> FUNCTION_DIRS = List.of("function", "functions");

    private DatapackWriter() {
    }

    /** Replaces {@code worldDir/datapacks/mcscenario} with a pack that performs {@code run}. */
    public static void write(Path worldDir, Step.Run run, int packFormat) {
        String name = run.name();
        if (name == null || name.isBlank() || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0) {
            throw new ScenarioException("Run name must be non-blank and single-line: " + quote(name));
        }
        if (name.indexOf('@') >= 0) {
            // `say` expands target selectors, which would break the log markers the runner waits for.
            throw new ScenarioException("Run name must not contain '@': " + quote(name));
        }
        String slug = slug(name);
        Map<String, String> functions = functions(name, slug, run);

        Path packDir = worldDir.resolve("datapacks").resolve(NAMESPACE);
        try {
            if (Files.exists(packDir)) {
                WorldFiles.deleteRecursively(packDir);
            }
            Files.createDirectories(packDir);
            writeFile(packDir.resolve("pack.mcmeta"), packMcmeta(name, packFormat));
            String loadTag = "{\"values\": [" + jsonString(functionId(slug, "start")) + "]}\n";
            for (String dir : FUNCTION_DIRS) {
                Path functionDir = packDir.resolve("data").resolve(NAMESPACE).resolve(dir).resolve(slug);
                for (Map.Entry<String, String> function : functions.entrySet()) {
                    writeFile(functionDir.resolve(function.getKey() + ".mcfunction"), function.getValue());
                }
                writeFile(packDir.resolve("data/minecraft/tags").resolve(dir).resolve("load.json"), loadTag);
            }
        } catch (IOException e) {
            throw new ScenarioException("Could not write datapack to " + packDir, e);
        }
    }

    /** Lowercases {@code runName} and replaces every character outside {@code [a-z0-9_]} with {@code _}. */
    static String slug(String runName) {
        return runName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    static String packMcmeta(String runName, int packFormat) {
        return """
            {
              "pack": {
                "pack_format": %d,
                "supported_formats": {"min_inclusive": 1, "max_inclusive": 2147483647},
                "description": %s
              }
            }
            """.formatted(packFormat, jsonString("mcscenario run " + runName));
    }

    /** Function file contents by function name, in execution order. */
    private static Map<String, String> functions(String name, String slug, Step.Run run) {
        List<Phase> phases = run.phases();
        Map<String, String> functions = new LinkedHashMap<>();
        functions.put("start", lines(
            List.of("say " + RUN_START_MARKER + name),
            phases.isEmpty() ? chain(slug, "finish", run.holdTicks()) : chain(slug, "p0", phases.getFirst().delayTicks())
        ));
        for (int i = 0; i < phases.size(); i++) {
            List<String> commands = new ArrayList<>();
            for (String command : phases.get(i).commands()) {
                commands.add(sanitize(command));
            }
            String next = i + 1 < phases.size()
                ? chain(slug, "p" + (i + 1), phases.get(i + 1).delayTicks())
                : chain(slug, "finish", run.holdTicks());
            functions.put("p" + i, lines(commands, next));
        }
        functions.put("finish", lines(List.of("say " + RUN_COMPLETE_MARKER + name, "save-all flush"), "stop"));
        return functions;
    }

    private static String chain(String slug, String function, int delayTicks) {
        if (delayTicks < 0) {
            throw new ScenarioException("Delay must be >= 0 ticks, was " + delayTicks);
        }
        String id = functionId(slug, function);
        return delayTicks == 0 ? "function " + id : "schedule function " + id + " " + delayTicks + "t";
    }

    private static String functionId(String slug, String function) {
        return NAMESPACE + ":" + slug + "/" + function;
    }

    static String sanitize(String command) {
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            throw new ScenarioException("Command must be a single line: " + quote(command));
        }
        String trimmed = (command.startsWith("/") ? command.substring(1) : command).strip();
        if (trimmed.isEmpty()) {
            throw new ScenarioException("Command must not be blank: " + quote(command));
        }
        if (trimmed.endsWith("\\")) {
            // A trailing backslash continues the command onto the next function line (1.20.2+).
            throw new ScenarioException("Command must not end with a backslash: " + quote(command));
        }
        return trimmed;
    }

    private static String lines(List<String> body, String last) {
        StringBuilder sb = new StringBuilder();
        for (String line : body) {
            sb.append(line).append('\n');
        }
        return sb.append(last).append('\n').toString();
    }

    private static void writeFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u%04x".formatted((int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String quote(String s) {
        return s == null ? "null" : jsonString(s);
    }
}
