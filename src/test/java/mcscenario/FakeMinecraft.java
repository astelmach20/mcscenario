package mcscenario;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Properties;

/**
 * Stand-in for a Minecraft server that loads the injected datapack and interprets just enough of it
 * ({@code say}, {@code function}, {@code schedule function}, {@code stop}) to drive {@link ScenarioRunnerTest}.
 * Scheduled delays are ignored, {@code crash} exits with code 2, and any other command is echoed as
 * {@code ran <command>}.
 */
public final class FakeMinecraft {
    private FakeMinecraft() {
    }

    /** Usage: {@code FakeMinecraft <runDir>}, run with the scenario's work directory as the current directory. */
    public static void main(String[] args) throws IOException {
        Path runDir = Path.of(args[0]);
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(runDir.resolve("server.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        Path world = runDir.resolve(properties.getProperty("level-name"));
        Path levelDat = world.resolve("level.dat");
        log("existed=" + (Files.exists(levelDat) ? 1 : 0));
        Files.createDirectories(world);
        Files.writeString(levelDat, "level");

        if (!"4".equals(properties.getProperty("function-permission-level"))) {
            log("function-permission-level too low");
            System.exit(1);
        }

        Path functions = world.resolve("datapacks/mcscenario/data/mcscenario/function");
        Deque<String> queue = new ArrayDeque<>();
        try (var tags = Files.list(functions)) {
            tags.forEach(dir -> queue.add("mcscenario:" + dir.getFileName() + "/start"));
        }
        while (!queue.isEmpty()) {
            String id = queue.poll();
            Path file = functions.resolve(id.substring(id.indexOf(':') + 1) + ".mcfunction");
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.startsWith("say ")) {
                    log("[Server] " + line.substring(4));
                } else if (line.startsWith("schedule function ")) {
                    queue.add(line.split(" ")[2]);
                } else if (line.startsWith("function ")) {
                    queue.addFirst(line.substring(9));
                } else if (line.equals("crash")) {
                    System.exit(2);
                } else if (line.equals("stop")) {
                    log("Stopping server");
                    System.exit(0);
                } else {
                    log("ran " + line);
                }
            }
        }
    }

    private static void log(String message) {
        System.out.println("[Server thread/INFO]: " + message);
        System.out.flush();
    }

    static List<String> javaCommand(String runDir) {
        String java = ProcessHandle.current().info().command().orElseThrow();
        return List.of(java, "-cp", System.getProperty("java.class.path"), FakeMinecraft.class.getName(), runDir);
    }
}
