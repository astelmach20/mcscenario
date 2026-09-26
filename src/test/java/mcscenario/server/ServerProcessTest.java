package mcscenario.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import mcscenario.model.ServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ServerProcessTest {
    private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(20);

    @TempDir
    Path dir;

    private ServerConfig config(String mode, Map<String, String> environment) {
        List<String> command = new ArrayList<>(FakeServer.javaCommand());
        command.add(mode);
        return new ServerConfig(dir, command, Path.of("run"), "world", 48, Map.of(), false, Duration.ofSeconds(30), environment);
    }

    @Test
    void streamsStrippedNumberedLinesToListenerAndLog() throws IOException {
        Path log = dir.resolve("logs/run.log");
        List<String> seen = new CopyOnWriteArrayList<>();
        try (ServerProcess server = ServerProcess.start(config("colours", Map.of("MCS_TEST", "hi")), log,
            (n, line) -> seen.add(n + ":" + line))) {
            assertEquals(OptionalInt.of(3), server.awaitExit(EXIT_TIMEOUT));
            assertFalse(server.isAlive());
        }
        assertEquals(List.of("1:[Server thread/INFO]: Starting", "2:[Server thread/WARN]: env=hi", "3:plain line"), seen);
        assertEquals(List.of("[Server thread/INFO]: Starting", "[Server thread/WARN]: env=hi", "plain line"),
            Files.readAllLines(log));
    }

    @Test
    void listenerFailureIsRethrownWithoutLosingLines() throws IOException {
        Path log = dir.resolve("run.log");
        IllegalStateException boom = new IllegalStateException("boom");
        List<Long> seen = new CopyOnWriteArrayList<>();
        try (ServerProcess server = ServerProcess.start(config("colours", Map.of()), log, (n, line) -> {
            seen.add(n);
            if (n >= 2) {
                throw boom;
            }
        })) {
            assertSame(boom, assertThrows(IllegalStateException.class, () -> server.awaitExit(EXIT_TIMEOUT)));
        }
        assertEquals(List.of(1L, 2L, 3L), seen);
        assertEquals(3, Files.readAllLines(log).size());
    }

    @Test
    void killTreeKillsDescendants() throws Exception {
        CompletableFuture<Long> childPid = new CompletableFuture<>();
        try (ServerProcess server = ServerProcess.start(config("spawn", Map.of()), dir.resolve("run.log"), (n, line) -> {
            if (line.startsWith("child ")) {
                childPid.complete(Long.parseLong(line.substring(6).trim()));
            }
        })) {
            long pid = childPid.get(20, TimeUnit.SECONDS);
            ProcessHandle child = ProcessHandle.of(pid).orElseThrow();
            assertTrue(child.isAlive());

            assertEquals(OptionalInt.empty(), server.awaitExit(Duration.ofMillis(200)));
            server.killTree();

            assertFalse(server.isAlive());
            assertFalse(child.isAlive());
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void launchesExtensionlessBatchScriptOnWindows() throws IOException {
        Files.writeString(dir.resolve("gradlew.bat"), "@echo off\r\necho args %1 %2\r\nexit /b 5\r\n");
        ServerConfig config = new ServerConfig(dir, List.of("./gradlew", "--no-daemon", "runServer"), Path.of("run"),
            "world", 48, Map.of(), false, Duration.ofSeconds(30), Map.of());
        List<String> seen = new CopyOnWriteArrayList<>();
        try (ServerProcess server = ServerProcess.start(config, dir.resolve("run.log"), (n, line) -> seen.add(line))) {
            assertEquals(OptionalInt.of(5), server.awaitExit(EXIT_TIMEOUT));
        }
        assertEquals(List.of("args --no-daemon runServer"), seen);
    }

    @Test
    void sendCommandRoundTrips() throws Exception {
        CompletableFuture<String> echoed = new CompletableFuture<>();
        try (ServerProcess server = ServerProcess.start(config("echo", Map.of()), dir.resolve("run.log"),
            (n, line) -> echoed.complete(line))) {
            server.sendCommand("say hello");
            assertEquals("echo say hello", echoed.get(20, TimeUnit.SECONDS));
            server.sendCommand("stop");
            assertEquals(OptionalInt.of(0), server.awaitExit(EXIT_TIMEOUT));
            server.sendCommand("ignored after exit");
        }
    }
}
