package mcscenario.yaml;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Tag;

import mcscenario.model.Assertion;
import mcscenario.model.Assertion.Comparison;
import mcscenario.model.Assertion.Quantifier;
import mcscenario.model.Phase;
import mcscenario.model.Probe;
import mcscenario.model.Scenario;
import mcscenario.model.ScenarioException;
import mcscenario.model.ServerConfig;
import mcscenario.model.Step;

/** Reads scenario files. Every validation error is a {@link ScenarioException} naming the offending field path. */
public final class ScenarioParser {
    private static final Pattern SCENARIO_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]*");
    private static final Pattern PROBE_NAME = Pattern.compile("[a-z0-9_-]+");
    private static final Pattern TICKS = Pattern.compile("(\\d+)t");
    private static final Pattern SECONDS = Pattern.compile("(\\d+(?:\\.\\d+)?)s");
    private static final Pattern WALL_CLOCK = Pattern.compile("(\\d+)([smh])");
    private static final Pattern OPERAND = Pattern.compile("\\s*(<=|>=|==|!=|<|>)\\s*(-?\\d+(?:\\.\\d+)?)\\s*");
    private static final BigDecimal TICKS_PER_SECOND = BigDecimal.valueOf(20);

    private static final List<String> TOP_KEYS = List.of("name", "description", "server", "steps", "probes", "assertions");
    private static final List<String> SERVER_KEYS = List.of(
        "workDir", "command", "runDir", "levelName", "packFormat", "acceptEula", "runTimeout", "properties", "environment");
    private static final List<String> STEP_KEYS = List.of("run", "deleteWorldFile", "resetWorld");
    private static final List<String> RUN_KEYS = List.of("name", "hold", "phases");
    private static final List<String> PHASE_KEYS = List.of("after", "commands");
    private static final List<String> QUANTIFIER_KEYS = List.of("all", "any", "none", "count");
    private static final List<String> ASSERTION_KEYS = List.of("run", "probe", "all", "any", "none", "count");

    private ScenarioParser() {
    }

    /** Parses a scenario file; relative paths in it are resolved against the file's directory. */
    public static Scenario parse(Path file) {
        String yaml;
        try {
            yaml = Files.readString(file);
        } catch (IOException e) {
            throw new ScenarioException("cannot read scenario file " + file + ": " + e, e);
        }
        return parse(yaml, file.toAbsolutePath().getParent());
    }

    /** Parses scenario YAML; relative paths in it are resolved against {@code baseDir}. */
    public static Scenario parse(String yaml, Path baseDir) {
        Object document = load(yaml);
        if (document == null) {
            throw new ScenarioException("scenario is empty");
        }
        MapNode root = new Node("", document).map(TOP_KEYS);

        String name = matching(root.required("name"), SCENARIO_NAME);
        String description = root.get("description").stringOr("");
        ServerConfig server = server(root.required("server").map(SERVER_KEYS), baseDir);
        List<Step> steps = steps(root.required("steps"));
        List<Probe> probes = probes(root.get("probes").anyMap());
        List<Assertion> assertions = assertions(root.get("assertions"), steps, probes);
        return new Scenario(name, description, server, steps, probes, assertions);
    }

    private static Object load(String yaml) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try {
            return new Yaml(new ScenarioConstructor(options)).load(yaml);
        } catch (YAMLException e) {
            throw new ScenarioException("invalid YAML: " + e.getMessage(), e);
        }
    }

    private static ServerConfig server(MapNode server, Path baseDir) {
        Node workDirNode = server.get("workDir");
        String workDirValue = workDirNode.isAbsent() ? "." : workDirNode.nonBlankString();
        Path workDir = baseDir.resolve(path(workDirNode, workDirValue)).toAbsolutePath().normalize();

        List<Node> commandNodes = server.required("command").list();
        if (commandNodes.isEmpty()) {
            throw server.get("command").error("must not be empty");
        }
        List<String> command = commandNodes.stream().map(Node::nonBlankString).toList();

        Node runDirNode = server.get("runDir");
        Path runDir = path(runDirNode, runDirNode.isAbsent() ? "." : runDirNode.nonBlankString()).normalize();

        Node levelNameNode = server.get("levelName");
        String levelName = levelNameNode.isAbsent() ? "world" : levelNameNode.nonBlankString();

        Node packFormatNode = server.get("packFormat");
        int packFormat = packFormatNode.intOr(48);
        if (packFormat <= 0) {
            throw packFormatNode.error("must be > 0, got " + packFormat);
        }

        boolean acceptEula = server.get("acceptEula").boolOr(false);
        Node timeoutNode = server.get("runTimeout");
        Duration runTimeout = timeoutNode.isAbsent() ? Duration.ofMinutes(10) : wallClock(timeoutNode);

        return new ServerConfig(workDir, command, runDir, levelName, packFormat,
            stringMap(server.get("properties")), acceptEula, runTimeout, stringMap(server.get("environment")));
    }

    private static List<Step> steps(Node stepsNode) {
        List<Node> items = stepsNode.list();
        if (items.isEmpty()) {
            throw stepsNode.error("must not be empty");
        }
        List<Step> steps = new ArrayList<>();
        Set<String> runNames = new HashSet<>();
        for (Node item : items) {
            MapNode step = item.map(STEP_KEYS);
            if (step.size() != 1) {
                throw item.error("expected a map with exactly one of: " + String.join(", ", STEP_KEYS));
            }
            if (step.keys().contains("run")) {
                MapNode runMap = step.required("run").map(RUN_KEYS);
                Step.Run run = run(runMap);
                if (!runNames.add(run.name())) {
                    throw runMap.get("name").error("duplicate run name \"" + run.name() + "\"");
                }
                steps.add(run);
            } else if (step.keys().contains("deleteWorldFile")) {
                steps.add(new Step.DeleteWorldFile(worldRelativePath(step.required("deleteWorldFile"))));
            } else {
                Node reset = step.get("resetWorld");
                if (!Boolean.TRUE.equals(reset.value())) {
                    throw reset.error("expected true, got " + Node.describe(reset.value()));
                }
                steps.add(new Step.ResetWorld());
            }
        }
        if (runNames.isEmpty()) {
            throw stepsNode.error("must contain at least one run step");
        }
        return steps;
    }

    private static Step.Run run(MapNode run) {
        Node nameNode = run.required("name");
        String name = nameNode.nonBlankString();
        if (name.contains("\n") || name.contains("\r")) {
            throw nameNode.error("must not contain line breaks");
        }
        int hold = ticks(run.get("hold"));
        List<Phase> phases = new ArrayList<>();
        for (Node phaseNode : run.get("phases").list()) {
            MapNode phase = phaseNode.map(PHASE_KEYS);
            int after = ticks(phase.get("after"));
            phases.add(new Phase(after, commands(phase.required("commands"))));
        }
        return new Step.Run(name, phases, hold);
    }

    private static List<String> commands(Node commandsNode) {
        List<Node> items = commandsNode.list();
        if (items.isEmpty()) {
            throw commandsNode.error("must not be empty");
        }
        List<String> commands = new ArrayList<>();
        for (Node item : items) {
            String command = item.nonBlankString();
            if (command.contains("\n") || command.contains("\r")) {
                throw item.error("must be a single line");
            }
            if (command.startsWith("/")) {
                throw item.error("must not start with \"/\"");
            }
            commands.add(command);
        }
        return commands;
    }

    private static String worldRelativePath(Node node) {
        String value = node.nonBlankString();
        Path path = path(node, value);
        Path normalized = path.normalize();
        if (path.getRoot() != null || normalized.toString().isEmpty() || normalized.startsWith("..")) {
            throw node.error("must be a relative path inside the world folder, got \"" + value + "\"");
        }
        return value;
    }

    private static List<Probe> probes(MapNode probesMap) {
        List<Probe> probes = new ArrayList<>();
        for (String name : probesMap.keys()) {
            Node node = probesMap.get(name);
            if (!PROBE_NAME.matcher(name).matches()) {
                throw node.error("probe name must match " + PROBE_NAME.pattern());
            }
            String regex = node.nonBlankString();
            try {
                probes.add(new Probe(name, Pattern.compile(regex)));
            } catch (PatternSyntaxException e) {
                throw node.error("invalid regex: " + e.getDescription() + " near index " + e.getIndex());
            }
        }
        return probes;
    }

    private static List<Assertion> assertions(Node assertionsNode, List<Step> steps, List<Probe> probes) {
        Set<String> runNames = new HashSet<>();
        steps.forEach(step -> {
            if (step instanceof Step.Run run) {
                runNames.add(run.name());
            }
        });
        Set<String> probeNames = new HashSet<>();
        probes.forEach(probe -> probeNames.add(probe.name()));

        List<Assertion> assertions = new ArrayList<>();
        for (Node item : assertionsNode.list()) {
            MapNode assertion = item.map(ASSERTION_KEYS);
            Node runNode = assertion.required("run");
            String run = runNode.string();
            if (!runNames.contains(run)) {
                throw runNode.error("no run step named \"" + run + "\"");
            }
            Node probeNode = assertion.required("probe");
            String probe = probeNode.string();
            if (!probeNames.contains(probe)) {
                throw probeNode.error("no probe named \"" + probe + "\"");
            }
            List<String> quantifiers = QUANTIFIER_KEYS.stream().filter(assertion.keys()::contains).toList();
            if (quantifiers.size() != 1) {
                throw item.error("expected exactly one of: " + String.join(", ", QUANTIFIER_KEYS));
            }
            String key = quantifiers.getFirst();
            Quantifier quantifier = Quantifier.valueOf(key.toUpperCase());
            Node operandNode = assertion.required(key);
            Matcher m = OPERAND.matcher(operandNode.string());
            if (!m.matches()) {
                throw operandNode.error("expected a comparison like \">= 0\" (one of < <= == != >= >), got "
                    + Node.describe(operandNode.value()));
            }
            if (quantifier == Quantifier.COUNT && !m.group(2).matches("\\d+")) {
                throw operandNode.error("count must be compared with a non-negative integer, got " + m.group(2));
            }
            Comparison comparison = comparison(m.group(1));
            assertions.add(new Assertion(run, probe, quantifier, comparison, Double.parseDouble(m.group(2))));
        }
        return assertions;
    }

    private static Comparison comparison(String symbol) {
        for (Comparison c : Comparison.values()) {
            if (c.symbol().equals(symbol)) {
                return c;
            }
        }
        throw new IllegalArgumentException(symbol);
    }

    /** Ticks as {@code "<n>t"}, {@code "<x>s"} (a whole number of ticks), or a bare integer. Absent means 0. */
    static int ticks(Node node) {
        Object value = node.value();
        if (value == null) {
            return 0;
        }
        if (value instanceof Integer i) {
            if (i < 0) {
                throw node.error("ticks must be >= 0, got " + i);
            }
            return i;
        }
        if (value instanceof String s) {
            Matcher t = TICKS.matcher(s);
            if (t.matches()) {
                try {
                    return Integer.parseInt(t.group(1));
                } catch (NumberFormatException e) {
                    throw node.error("too many ticks: \"" + s + "\"");
                }
            }
            Matcher sec = SECONDS.matcher(s);
            if (sec.matches()) {
                BigDecimal ticks = new BigDecimal(sec.group(1)).multiply(TICKS_PER_SECOND);
                if (ticks.stripTrailingZeros().scale() > 0) {
                    throw node.error("\"" + s + "\" is not a whole number of ticks (1 tick = 0.05s)");
                }
                try {
                    return ticks.intValueExact();
                } catch (ArithmeticException e) {
                    throw node.error("too many ticks: \"" + s + "\"");
                }
            }
        }
        throw node.error("expected ticks like \"20t\" or \"1.5s\", got " + Node.describe(value));
    }

    private static Duration wallClock(Node node) {
        Matcher m = WALL_CLOCK.matcher(node.value() instanceof String s ? s : "");
        if (m.matches()) {
            try {
                long n = Long.parseLong(m.group(1));
                if (n > 0) {
                    return switch (m.group(2)) {
                        case "s" -> Duration.ofSeconds(n);
                        case "m" -> Duration.ofMinutes(n);
                        default -> Duration.ofHours(n);
                    };
                }
            } catch (NumberFormatException | ArithmeticException e) {
                throw node.error("duration too large: " + Node.describe(node.value()));
            }
        }
        throw node.error("expected a positive duration like \"90s\", \"10m\" or \"1h\", got " + Node.describe(node.value()));
    }

    private static Map<String, String> stringMap(Node node) {
        MapNode map = node.anyMap();
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : map.keys()) {
            result.put(key, map.get(key).scalarString());
        }
        return result;
    }

    private static String matching(Node node, Pattern pattern) {
        String value = node.string();
        if (!pattern.matcher(value).matches()) {
            throw node.error("must match " + pattern.pattern() + ", got \"" + value + "\"");
        }
        return value;
    }

    private static Path path(Node node, String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            throw node.error("invalid path \"" + value + "\": " + e.getReason());
        }
    }

    /** Safe constructor that keeps timestamps as plain strings, so e.g. {@code motd: 2024-01-01} stays text. */
    private static final class ScenarioConstructor extends SafeConstructor {
        ScenarioConstructor(LoaderOptions options) {
            super(options);
            yamlConstructors.put(Tag.TIMESTAMP, new ConstructYamlStr());
        }
    }
}
