package mcscenario.datapack;

import mcscenario.model.Phase;
import mcscenario.model.ScenarioException;
import mcscenario.model.Step;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatapackWriterTest {
    @TempDir
    Path world;

    private Path pack() {
        return world.resolve("datapacks/mcscenario");
    }

    /** Every file under the pack, keyed by path relative to the pack with forward slashes. */
    private Map<String, String> packFiles() throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(pack())) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                files.put(pack().relativize(p).toString().replace('\\', '/'), Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    @Test
    void writesThreePhaseRun() throws IOException {
        Step.Run run = new Step.Run("Warm-Up 1", List.of(
            new Phase(20, List.of("/time set day", "  weather clear  ")),
            new Phase(0, List.of("say mid")),
            new Phase(5, List.of("kill @e[type=zombie]"))
        ), 100);

        DatapackWriter.write(world, run, 48);

        Map<String, String> expected = new TreeMap<>();
        expected.put("pack.mcmeta", """
            {
              "pack": {
                "pack_format": 48,
                "supported_formats": {"min_inclusive": 1, "max_inclusive": 2147483647},
                "description": "mcscenario run Warm-Up 1"
              }
            }
            """);
        for (String dir : List.of("function", "functions")) {
            String fn = "data/mcscenario/" + dir + "/warm_up_1/";
            expected.put(fn + "start.mcfunction", """
                say [mcscenario] run-start Warm-Up 1
                schedule function mcscenario:warm_up_1/p0 20t
                """);
            expected.put(fn + "p0.mcfunction", """
                time set day
                weather clear
                function mcscenario:warm_up_1/p1
                """);
            expected.put(fn + "p1.mcfunction", """
                say mid
                schedule function mcscenario:warm_up_1/p2 5t
                """);
            expected.put(fn + "p2.mcfunction", """
                kill @e[type=zombie]
                schedule function mcscenario:warm_up_1/finish 100t
                """);
            expected.put(fn + "finish.mcfunction", """
                say [mcscenario] run-complete Warm-Up 1
                save-all flush
                stop
                """);
            expected.put("data/minecraft/tags/" + dir + "/load.json", "{\"values\": [\"mcscenario:warm_up_1/start\"]}\n");
        }
        assertEquals(expected, packFiles());
    }

    @Test
    void bothLayoutsAreIdentical() throws IOException {
        DatapackWriter.write(world, new Step.Run("r", List.of(new Phase(3, List.of("say hi"))), 7), 15);

        Map<String, String> files = packFiles();
        Map<String, String> singular = new TreeMap<>();
        Map<String, String> plural = new TreeMap<>();
        files.forEach((path, content) -> {
            if (path.contains("/function/")) {
                singular.put(path.replace("/function/", "/"), content);
            } else if (path.contains("/functions/")) {
                plural.put(path.replace("/functions/", "/"), content);
            }
        });
        assertEquals(4, singular.size());
        assertEquals(singular, plural);
    }

    @Test
    void zeroPhaseRunChainsStraightToFinish() throws IOException {
        DatapackWriter.write(world, new Step.Run("empty", List.of(), 40), 48);

        Map<String, String> files = packFiles();
        assertEquals("""
            say [mcscenario] run-start empty
            schedule function mcscenario:empty/finish 40t
            """, files.get("data/mcscenario/function/empty/start.mcfunction"));
        assertEquals(List.of("finish.mcfunction", "start.mcfunction"),
            files.keySet().stream().filter(k -> k.startsWith("data/mcscenario/function/"))
                .map(k -> k.substring(k.lastIndexOf('/') + 1)).sorted().toList());
    }

    @Test
    void zeroHoldAndFirstDelayUseDirectCalls() throws IOException {
        DatapackWriter.write(world, new Step.Run("z", List.of(new Phase(0, List.of("say a"))), 0), 48);

        Map<String, String> files = packFiles();
        assertEquals("say [mcscenario] run-start z\nfunction mcscenario:z/p0\n",
            files.get("data/mcscenario/functions/z/start.mcfunction"));
        assertEquals("say a\nfunction mcscenario:z/finish\n", files.get("data/mcscenario/functions/z/p0.mcfunction"));
    }

    @Test
    void packMcmetaEscapesJsonStrings() throws IOException {
        DatapackWriter.write(world, new Step.Run("a \"q\" \\ b\tc", List.of(), 1), 71);

        assertEquals("""
            {
              "pack": {
                "pack_format": 71,
                "supported_formats": {"min_inclusive": 1, "max_inclusive": 2147483647},
                "description": "mcscenario run a \\"q\\" \\\\ b\\tc"
              }
            }
            """, Files.readString(pack().resolve("pack.mcmeta")));
        assertEquals("\"\\u0001\"", DatapackWriter.jsonString("\u0001"));
    }

    @Test
    void slugIsLowercaseResourcePath() throws IOException {
        assertEquals("my_run__2_", DatapackWriter.slug("My Run (2)"));
        assertEquals("caf_", DatapackWriter.slug("Café"));
        assertEquals("already_ok_9", DatapackWriter.slug("already_ok_9"));

        DatapackWriter.write(world, new Step.Run("Hello/World.x", List.of(), 1), 48);
        assertTrue(Files.isRegularFile(pack().resolve("data/mcscenario/function/hello_world_x/start.mcfunction")));
    }

    @Test
    void sanitizeStripsOneLeadingSlashAndTrims() {
        assertEquals("say hi", DatapackWriter.sanitize("/say hi"));
        assertEquals("/say hi", DatapackWriter.sanitize("//say hi"));
        assertEquals("say hi", DatapackWriter.sanitize("  say hi \t"));
        assertEquals("say hi", DatapackWriter.sanitize("/ say hi"));
    }

    @Test
    void rejectsUnsafeCommands() {
        for (String bad : List.of("say a\nstop", "say a\rstop", "", "   ", "/", "/  ", "say a \\")) {
            Step.Run run = new Step.Run("r", List.of(new Phase(1, List.of(bad))), 1);
            assertThrows(ScenarioException.class, () -> DatapackWriter.write(world, run, 48), bad);
        }
        assertFalse(Files.exists(pack()));
    }

    @Test
    void rejectsUnsafeRunNames() {
        for (String bad : List.of("a\nb", "a\rb", "", "  ", "@a")) {
            Step.Run run = new Step.Run(bad, List.of(), 1);
            assertThrows(ScenarioException.class, () -> DatapackWriter.write(world, run, 48), bad);
        }
    }

    @Test
    void rejectsNegativeHoldTicks() {
        assertThrows(ScenarioException.class, () -> DatapackWriter.write(world, new Step.Run("r", List.of(), -1), 48));
    }

    @Test
    void rewriteReplacesPreviousPack() throws IOException {
        DatapackWriter.write(world, new Step.Run("first", List.of(
            new Phase(1, List.of("say 1")), new Phase(1, List.of("say 2")), new Phase(1, List.of("say 3"))), 1), 48);
        Files.writeString(pack().resolve("stray.txt"), "x");

        DatapackWriter.write(world, new Step.Run("second", List.of(new Phase(1, List.of("say only"))), 1), 48);

        List<String> expected = new ArrayList<>(List.of("pack.mcmeta"));
        for (String dir : List.of("function", "functions")) {
            for (String fn : List.of("start", "p0", "finish")) {
                expected.add("data/mcscenario/" + dir + "/second/" + fn + ".mcfunction");
            }
            expected.add("data/minecraft/tags/" + dir + "/load.json");
        }
        assertEquals(expected.stream().sorted().toList(), List.copyOf(packFiles().keySet()));
    }

    @Test
    void markersMatchGeneratedSayLines() throws IOException {
        DatapackWriter.write(world, new Step.Run("m", List.of(), 1), 48);
        Map<String, String> files = packFiles();
        assertTrue(files.get("data/mcscenario/function/m/start.mcfunction")
            .contains(DatapackWriter.RUN_START_MARKER + "m"));
        assertTrue(files.get("data/mcscenario/function/m/finish.mcfunction")
            .contains(DatapackWriter.RUN_COMPLETE_MARKER + "m"));
    }
}
