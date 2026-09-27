package org.standict.codelist.normalize;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratedOutputsTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();
    private final String hash = "a".repeat(64);

    @Test
    void migratesFlatRevisionDirectoriesAndPrunesOnlyObsoleteTrackedFiles() throws Exception {
        Path root = temp.resolve("output");
        Path staging = temp.resolve("staging");
        writeOutputs(root, true);
        writeOutputs(staging, false);
        Files.writeString(root.resolve("2026-05-15_17_r01/notes.txt"), "Unrelated notes");
        Files.writeString(root.resolve("fixture.gc"), "Unrelated fixture");
        GeneratedOutputs.read(root, json).publish(staging);
        assertEquals("Unrelated notes", Files.readString(root.resolve("2026-05-15_17_r01/notes.txt")));
        assertEquals("Unrelated fixture", Files.readString(root.resolve("fixture.gc")));
        for (String stage : new String[] {"extracted", "normalized"}) {
            assertEquals("Original XML bytes", Files.readString(root.resolve("17_2026-05-15/r01/" + stage + "/gc/EAS.gc")));
            assertFalse(Files.exists(root.resolve("2026-05-15_17_r01/" + stage)));
        }
        assertEquals(5, json.readTree(root.resolve("normalization.json").toFile()).path("format_version").asInt());
    }

    @Test
    void refusesToMigrateChangedOrMissingTrackedOutputs() throws Exception {
        Path root = temp.resolve("output");
        writeOutputs(root, true);
        Path file = root.resolve(legacyDirectory("normalized") + "/EAS.gc");
        Files.writeString(file, "User edits");
        var before = hashes(root);
        assertThrows(IOException.class, () -> GeneratedOutputs.read(root, json));
        assertEquals(before, hashes(root));
        Files.delete(file);
        assertThrows(IOException.class, () -> GeneratedOutputs.read(root, json));
    }

    @Test
    void refusesUntrackedDestinationCollisionsBeforePublishingAnyFiles() throws Exception {
        Path root = temp.resolve("output");
        Path staging = temp.resolve("staging");
        writeOutputs(root, true);
        writeOutputs(staging, false);
        Path collision = root.resolve("17_2026-05-15/r01/normalized/gc/EAS.gc");
        Files.createDirectories(collision.getParent());
        Files.writeString(collision, "User file");
        var before = hashes(root);
        assertThrows(IOException.class, () -> GeneratedOutputs.read(root, json).publish(staging));
        assertEquals(before, hashes(root));
    }

    @Test
    void rejectsUnsafeManifestPathsAndSymlinkedOutputs() throws Exception {
        Path root = temp.resolve("output");
        writeOutputs(root, true);
        Path indexPath = root.resolve("normalization.json");
        var index = (ObjectNode) json.readTree(indexPath.toFile());
        ((ObjectNode) index.path("archives").get(0)).put("directory", "../outside");
        json.writeValue(indexPath.toFile(), index);
        assertThrows(IOException.class, () -> GeneratedOutputs.read(root, json));
        writeOutputs(root, true);
        Path outside = temp.resolve("outside.gc");
        Files.writeString(outside, "Original XML bytes");
        Path file = root.resolve(legacyDirectory("extracted") + "/EAS.gc");
        Files.delete(file);
        Files.createSymbolicLink(file, outside);
        assertThrows(IOException.class, () -> GeneratedOutputs.read(root, json));
        assertEquals("Original XML bytes", Files.readString(outside));
    }

    private String legacyDirectory(String stage) {
        return "2026-05-15_17_r01/" + stage + "/gc";
    }

    private void writeOutputs(Path root, boolean legacy) throws IOException {
        var index = json.createObjectNode().put("format_version", legacy ? 4 : 5);
        index.putArray("workbooks");
        var archive = index.putArray("archives").addObject().put("source_sha256", hash);
        for (String stage : new String[] {"normalized", "extracted"}) {
            String directory = legacy ? legacyDirectory(stage) : "17_2026-05-15/r01/" + stage + "/gc";
            archive.put(stage.equals("normalized") ? "directory" : "extracted_directory", directory);
            Path destination = root.resolve(directory);
            Files.createDirectories(destination);
            Files.writeString(destination.resolve("EAS.gc"), "Original XML bytes");
            var manifest = json.createObjectNode().put("source_sha256", hash);
            manifest.putArray("files").addObject().put("filename", "EAS.gc")
                    .put("sha256", NormalizationPipeline.sha256(destination.resolve("EAS.gc")));
            json.writeValue(destination.resolve("source.json").toFile(), manifest);
        }
        json.writeValue(root.resolve("normalization.json").toFile(), index);
    }

    private Map<Path, String> hashes(Path root) throws IOException {
        var hashes = new HashMap<Path, String>();
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) hashes.put(root.relativize(file), NormalizationPipeline.sha256(file));
        }
        return hashes;
    }
}
