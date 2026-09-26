package mcscenario.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import mcscenario.model.ScenarioException;
import mcscenario.model.ServerConfig;

/**
 * A running server launch command. Output is read on a background thread: each line is ANSI-stripped, appended to
 * a log file and handed to a {@link LineListener}.
 */
public final class ServerProcess implements AutoCloseable {
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration KILL_TIMEOUT = Duration.ofSeconds(10);
    private static final List<String> WINDOWS_EXTENSIONS = List.of(".bat", ".cmd", ".exe");

    @FunctionalInterface
    public interface LineListener {
        void onLine(long lineNumber, String line);
    }

    private final Process process;
    private final Writer stdin;
    private final Thread reader;
    private final AtomicReference<RuntimeException> readerFailure = new AtomicReference<>();

    private ServerProcess(Process process, Writer log, LineListener listener) {
        this.process = process;
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.reader = Thread.ofVirtual()
            .name("server-output-" + process.pid())
            .start(() -> pump(log, listener));
    }

    public static ServerProcess start(ServerConfig config, Path logFile, LineListener listener) {
        List<String> command = resolveCommand(config.command(), config.workDir(), isWindows());
        ProcessBuilder builder = new ProcessBuilder(command)
            .directory(config.workDir().toFile())
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.PIPE);
        builder.environment().putAll(config.environment());

        Writer log;
        try {
            Path parent = logFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            log = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ScenarioException("Cannot open server log " + logFile, e);
        }
        try {
            return new ServerProcess(builder.start(), log, listener);
        } catch (IOException e) {
            closeQuietly(log);
            throw new ScenarioException("Cannot start server: " + String.join(" ", command), e);
        }
    }

    /**
     * Makes {@code command} launchable by {@link ProcessBuilder}. On Windows, an extensionless {@code command[0]}
     * such as {@code ./gradlew} is resolved to a sibling {@code .bat}, {@code .cmd} or {@code .exe} (relative paths
     * against {@code workDir}); batch files are run through {@code cmd.exe /c}. Otherwise the command is unchanged.
     */
    static List<String> resolveCommand(List<String> command, Path workDir, boolean windows) {
        if (!windows || command.isEmpty()) {
            return command;
        }
        Path executable = Path.of(command.getFirst());
        Path fileName = executable.getFileName();
        if (fileName == null || fileName.toString().contains(".")) {
            return command;
        }
        List<String> args = command.subList(1, command.size());
        for (String extension : WINDOWS_EXTENSIONS) {
            Path candidate = executable.resolveSibling(fileName + extension);
            Path absolute = workDir.resolve(candidate).toAbsolutePath().normalize();
            if (!Files.isRegularFile(absolute)) {
                continue;
            }
            List<String> resolved = new ArrayList<>();
            if (extension.equals(".exe")) {
                // CreateProcess resolves relative executables against our cwd, not the child's directory.
                resolved.add(absolute.toString());
            } else {
                // cmd.exe runs in the child's directory, so a relative path works and avoids quoting trouble.
                resolved.addAll(List.of("cmd.exe", "/c", candidate.toString()));
            }
            resolved.addAll(args);
            return List.copyOf(resolved);
        }
        return command;
    }

    /** Best-effort write of one console command to the server's stdin. */
    public void sendCommand(String command) {
        if (!process.isAlive()) {
            return;
        }
        synchronized (stdin) {
            try {
                stdin.write(command);
                stdin.write('\n');
                stdin.flush();
            } catch (IOException ignored) {
                // The process exited between the check and the write.
            }
        }
    }

    /**
     * Waits for the process to exit and for its output to be drained.
     *
     * @return the exit code, or empty if {@code timeout} elapsed first
     * @throws RuntimeException the first exception thrown by the listener or while writing the log
     */
    public OptionalInt awaitExit(Duration timeout) {
        try {
            if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                return OptionalInt.empty();
            }
            reader.join(DRAIN_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScenarioException("Interrupted while waiting for the server to exit", e);
        }
        RuntimeException failure = readerFailure.get();
        if (failure != null) {
            throw failure;
        }
        return OptionalInt.of(process.exitValue());
    }

    /** Forcibly kills the process and all its descendants, waiting up to 10s for them to exit. */
    public void killTree() {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        List<CompletableFuture<?>> exits = new ArrayList<>();
        descendants.forEach(handle -> exits.add(handle.onExit()));
        exits.add(process.onExit());
        try {
            CompletableFuture.allOf(exits.toArray(CompletableFuture<?>[]::new))
                .get(KILL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // Best effort: whatever survived is beyond our control.
        }
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public long pid() {
        return process.pid();
    }

    @Override
    public void close() {
        if (process.isAlive()) {
            killTree();
        }
    }

    private void pump(Writer log, LineListener listener) {
        try (log; BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            boolean logging = true;
            long lineNumber = 0;
            String raw;
            while ((raw = out.readLine()) != null) {
                String line = LogLines.stripAnsi(raw);
                lineNumber++;
                if (logging) {
                    try {
                        log.write(line);
                        log.write('\n');
                        log.flush();
                    } catch (IOException e) {
                        logging = false;
                        readerFailure.compareAndSet(null, new UncheckedIOException("Cannot write server log", e));
                    }
                }
                try {
                    listener.onLine(lineNumber, line);
                } catch (RuntimeException e) {
                    readerFailure.compareAndSet(null, e);
                }
            }
        } catch (IOException e) {
            // Stream closed: the process is gone or killed.
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static void closeQuietly(Writer writer) {
        try {
            writer.close();
        } catch (IOException ignored) {
            // Nothing useful to do.
        }
    }
}
