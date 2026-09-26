package mcscenario.model;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * How to launch the server under test.
 *
 * @param workDir         directory the launch command runs in, e.g. a mod's Gradle project root
 * @param command         launch command, e.g. {@code ["./gradlew", "--no-daemon", "runServer"]}
 * @param runDir          server run directory relative to {@code workDir}; holds {@code server.properties}
 * @param levelName       world folder name inside {@code runDir}
 * @param packFormat      {@code pack_format} written to the injected datapack's {@code pack.mcmeta}
 * @param properties      entries merged into {@code server.properties} before every run
 * @param acceptEula      write {@code eula=true}; never defaulted on, the scenario author must opt in
 * @param runTimeout      hard limit for a single run before the process tree is killed
 * @param environment     extra environment variables for the launch command
 */
public record ServerConfig(
    Path workDir,
    List<String> command,
    Path runDir,
    String levelName,
    int packFormat,
    Map<String, String> properties,
    boolean acceptEula,
    Duration runTimeout,
    Map<String, String> environment
) {
    public ServerConfig {
        command = List.copyOf(command);
        properties = Map.copyOf(properties);
        environment = Map.copyOf(environment);
    }

    public Path resolvedRunDir() {
        return workDir.resolve(runDir).normalize();
    }

    public Path worldDir() {
        return resolvedRunDir().resolve(levelName);
    }
}
