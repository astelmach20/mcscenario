package mcscenario.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResolveCommandTest {
    @TempDir
    Path workDir;

    @Test
    void nonWindowsRunsAsGiven() throws IOException {
        Files.createFile(workDir.resolve("gradlew.bat"));
        List<String> command = List.of("./gradlew", "--no-daemon", "runServer");
        assertEquals(command, ServerProcess.resolveCommand(command, workDir, false));
    }

    @Test
    void batRunsThroughCmd() throws IOException {
        Files.createFile(workDir.resolve("gradlew.bat"));
        Files.createFile(workDir.resolve("gradlew.exe"));
        String bat = Path.of("./gradlew").resolveSibling("gradlew.bat").toString();
        assertEquals(List.of("cmd.exe", "/c", bat, "--no-daemon", "runServer"),
            ServerProcess.resolveCommand(List.of("./gradlew", "--no-daemon", "runServer"), workDir, true));
    }

    @Test
    void cmdPreferredOverExe() throws IOException {
        Files.createFile(workDir.resolve("run.cmd"));
        Files.createFile(workDir.resolve("run.exe"));
        assertEquals(List.of("cmd.exe", "/c", "run.cmd"), ServerProcess.resolveCommand(List.of("run"), workDir, true));
    }

    @Test
    void exeRunsDirectlyByAbsolutePath() throws IOException {
        Path bin = Files.createDirectories(workDir.resolve("bin"));
        Path exe = Files.createFile(bin.resolve("server.exe"));
        assertEquals(List.of(exe.toAbsolutePath().normalize().toString(), "nogui"),
            ServerProcess.resolveCommand(List.of("bin/server", "nogui"), workDir, true));
    }

    @Test
    void absoluteCommandIgnoresWorkDir(@TempDir Path elsewhere) throws IOException {
        Path bat = Files.createFile(elsewhere.resolve("start.bat"));
        assertEquals(List.of("cmd.exe", "/c", bat.toString(), "x"),
            ServerProcess.resolveCommand(List.of(elsewhere.resolve("start").toString(), "x"), workDir, true));
    }

    @Test
    void notFoundOrHasExtensionIsUnchanged() throws IOException {
        Files.createFile(workDir.resolve("java.bat"));
        List<String> withExtension = List.of("java.exe", "-jar", "server.jar");
        assertEquals(withExtension, ServerProcess.resolveCommand(withExtension, workDir, true));
        List<String> onPath = List.of("gradle", "runServer");
        assertEquals(onPath, ServerProcess.resolveCommand(onPath, workDir, true));
    }
}
