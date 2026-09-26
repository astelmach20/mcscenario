package mcscenario.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Stand-in for a server launch command, used by {@link ServerProcessTest}. */
public final class FakeServer {
    private FakeServer() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        switch (args[0]) {
            case "colours" -> {
                System.out.println("\u001B[0;32m[Server thread/INFO]: Starting\u001B[m");
                System.out.println("\u001B[1;33m[Server thread/WARN]\u001B[0m: env=" + System.getenv("MCS_TEST"));
                System.out.println("plain line");
                System.out.flush();
                System.exit(3);
            }
            case "spawn" -> {
                List<String> child = new ArrayList<>(javaCommand());
                child.add("sleep");
                Process process = new ProcessBuilder(child)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
                System.out.println("child " + process.pid());
                System.out.flush();
                Thread.sleep(Long.MAX_VALUE);
            }
            case "sleep" -> Thread.sleep(Long.MAX_VALUE);
            case "echo" -> {
                BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                String line;
                while ((line = in.readLine()) != null && !line.equals("stop")) {
                    System.out.println("echo " + line);
                    System.out.flush();
                }
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    static List<String> javaCommand() {
        String java = ProcessHandle.current().info().command().orElseThrow();
        return List.of(java, "-cp", System.getProperty("java.class.path"), FakeServer.class.getName());
    }
}
