package mcscenario.datapack;

import mcscenario.model.ScenarioException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldFilesTest {
    @TempDir
    Path root;

    private Path world() throws IOException {
        return Files.createDirectories(root.resolve("world"));
    }

    @Test
    void deletesFile() throws IOException {
        Path world = world();
        Path file = Files.writeString(Files.createDirectories(world.resolve("data")).resolve("x.dat"), "x");

        assertTrue(WorldFiles.delete(world, "data/x.dat"));
        assertFalse(Files.exists(file));
        assertTrue(Files.isDirectory(world.resolve("data")));
    }

    @Test
    void deletesDirectoryRecursively() throws IOException {
        Path world = world();
        Path deep = Files.createDirectories(world.resolve("region/a/b"));
        Files.writeString(deep.resolve("f"), "1");
        Files.writeString(world.resolve("region/g"), "2");
        Files.writeString(world.resolve("level.dat"), "keep");

        assertTrue(WorldFiles.delete(world, "region"));
        assertFalse(Files.exists(world.resolve("region")));
        assertTrue(Files.exists(world.resolve("level.dat")));
    }

    @Test
    void missingReturnsFalse() throws IOException {
        assertFalse(WorldFiles.delete(world(), "data/nope.dat"));
    }

    @Test
    void resetDeletesWholeWorld() throws IOException {
        Path world = world();
        Files.writeString(Files.createDirectories(world.resolve("region")).resolve("r.0.0.mca"), "x");

        assertTrue(WorldFiles.reset(world));
        assertFalse(Files.exists(world));
        assertTrue(Files.isDirectory(root));
        assertFalse(WorldFiles.reset(world));
    }

    @Test
    void normalizedInsidePathIsAllowed() throws IOException {
        Path world = world();
        Files.writeString(world.resolve("a.dat"), "x");

        assertTrue(WorldFiles.delete(world, "data/../a.dat"));
    }

    @Test
    void rejectsPathsOutsideWorld() throws IOException {
        Path world = world();
        Path sibling = Files.writeString(root.resolve("outside.txt"), "keep");
        String absolute = sibling.toAbsolutePath().toString();

        for (String bad : new String[] {"..", "../outside.txt", "data/../../outside.txt", "", ".", "data/..", absolute}) {
            assertThrows(ScenarioException.class, () -> WorldFiles.delete(world, bad), bad);
        }
        assertTrue(Files.exists(sibling));
        assertTrue(Files.isDirectory(world));
    }
}
