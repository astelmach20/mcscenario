package mcscenario;

import mcscenario.model.Scenario;
import mcscenario.model.ScenarioException;
import mcscenario.model.Step;
import mcscenario.report.JsonReport;
import mcscenario.report.ScenarioResult;
import mcscenario.report.TextReport;
import mcscenario.yaml.ScenarioParser;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

public final class Main {
    static final int EXIT_PASSED = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_USAGE = 2;
    static final int EXIT_ERROR = 3;

    private static final String USAGE = """
        usage: mcscenario run <scenario.yaml> [--out <dir>] [--work-dir <dir>]
               mcscenario validate <scenario.yaml>

        exit codes: 0 passed, 1 assertions or runs failed, 2 bad usage or invalid scenario, 3 could not run
        """;

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(List.of(args), System.out, System.err));
    }

    static int run(List<String> args, PrintStream out, PrintStream err) {
        if (args.isEmpty() || args.contains("-h") || args.contains("--help")) {
            (args.isEmpty() ? err : out).print(USAGE);
            return args.isEmpty() ? EXIT_USAGE : EXIT_PASSED;
        }

        String command = args.getFirst();
        List<String> rest = args.subList(1, args.size());
        return switch (command) {
            case "run" -> runCommand(rest, out, err);
            case "validate" -> validateCommand(rest, out, err);
            default -> {
                err.println("unknown command: " + command);
                err.print(USAGE);
                yield EXIT_USAGE;
            }
        };
    }

    private static int validateCommand(List<String> args, PrintStream out, PrintStream err) {
        if (args.size() != 1) {
            err.print(USAGE);
            return EXIT_USAGE;
        }
        Scenario scenario;
        try {
            scenario = ScenarioParser.parse(Path.of(args.getFirst()));
        } catch (ScenarioException e) {
            err.println("invalid scenario: " + e.getMessage());
            return EXIT_USAGE;
        }
        long runs = scenario.steps().stream().filter(Step.Run.class::isInstance).count();
        out.printf("%s: valid (%d steps, %d runs, %d probes, %d assertions)%n", scenario.name(),
            scenario.steps().size(), runs, scenario.probes().size(), scenario.assertions().size());
        return EXIT_PASSED;
    }

    private static int runCommand(List<String> args, PrintStream out, PrintStream err) {
        Path scenarioFile = null;
        Path outputDir = Path.of("mcscenario-out");
        Path workDir = null;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("--out") && i + 1 < args.size()) {
                outputDir = Path.of(args.get(++i));
            } else if (arg.equals("--work-dir") && i + 1 < args.size()) {
                workDir = Path.of(args.get(++i));
            } else if (scenarioFile == null && !arg.startsWith("-")) {
                scenarioFile = Path.of(arg);
            } else {
                err.println("unexpected argument: " + arg);
                err.print(USAGE);
                return EXIT_USAGE;
            }
        }
        if (scenarioFile == null) {
            err.print(USAGE);
            return EXIT_USAGE;
        }

        Scenario scenario;
        try {
            scenario = ScenarioParser.parse(scenarioFile);
        } catch (ScenarioException e) {
            err.println("invalid scenario: " + e.getMessage());
            return EXIT_USAGE;
        }
        if (workDir != null) {
            scenario = new Scenario(scenario.name(), scenario.description(), scenario.server().withWorkDir(workDir),
                scenario.steps(), scenario.probes(), scenario.assertions());
        }

        ScenarioResult result;
        try {
            result = new ScenarioRunner(scenario, outputDir, out).run();
        } catch (ScenarioException e) {
            err.println("could not run scenario: " + e.getMessage());
            return EXIT_ERROR;
        }

        Path report = outputDir.resolve(scenario.name() + "-report.json");
        out.println();
        out.print(TextReport.render(result));
        try {
            JsonReport.write(result, report);
            out.println("report: " + report);
        } catch (IOException e) {
            err.println("could not write report " + report + ": " + e.getMessage());
        }
        return result.passed() ? EXIT_PASSED : EXIT_FAILED;
    }
}
