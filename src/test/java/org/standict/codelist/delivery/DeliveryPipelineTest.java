package org.standict.codelist.delivery;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link DeliveryPipeline}. */
class DeliveryPipelineTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();
    private final ObjectNode registry = json.createObjectNode();

    @Test
    void writesBothTreesWithAnIndexNamingTheSnapshot() throws Exception {
        add("genericodes", "digital-genericodes-2026-05-15.zip", 2026, 5, 15);
        save();

        var result = new DeliveryPipeline().run(downloader(), output());

        assertEquals(1, result.summary().deliveries());
        assertTrue(Files.exists(output().resolve(
                "downloaded/2026-05-15/digital-genericodes-2026-05-15/EAS.gc")));
        assertTrue(Files.exists(output().resolve(
                "normalized/2026-05-15/digital-genericodes-2026-05-15/EAS.gc")));
        var index = json.readTree(output().resolve("downloaded/delivery-index.json").toFile());
        assertEquals("downloaded", index.path("stage").asText());
        assertEquals(result.sourcesDigest(), index.path("sources_digest").asText());
        assertEquals("2026-05-15", index.path("effective_dates").get(0).asText());
    }

    /** A second run must replace its own output, and must reach the same bytes from the same registry. */
    @Test
    void republishesItsOwnOutputRepeatably() throws Exception {
        add("genericodes", "digital-genericodes-2026-05-15.zip", 2026, 5, 15);
        save();
        var first = new DeliveryPipeline().run(downloader(), output());
        var before = hashes(output());

        var second = new DeliveryPipeline().run(downloader(), output());

        assertEquals(first.sourcesDigest(), second.sourcesDigest());
        assertEquals(before, hashes(output()));
    }

    /** A delivery that disappears from the registry must disappear from the trees, not linger as a stale folder. */
    @Test
    void dropsDeliveriesThatLeaveTheRegistry() throws Exception {
        add("older", "digital-genericodes-2025-11-15.zip", 2025, 11, 15);
        add("newer", "digital-genericodes-2026-05-15.zip", 2026, 5, 15);
        save();
        new DeliveryPipeline().run(downloader(), output());
        assertTrue(Files.exists(output().resolve("downloaded/2025-11-15")));

        registry.remove("https://example.test/older");
        save();
        new DeliveryPipeline().run(downloader(), output());

        assertFalse(Files.exists(output().resolve("downloaded/2025-11-15")));
        assertTrue(Files.exists(output().resolve("downloaded/2026-05-15")));
    }

    /** Without its own index a directory is somebody else's, so it is refused instead of deleted. */
    @Test
    void refusesToReplaceADirectoryItDidNotGenerate() throws Exception {
        add("genericodes", "digital-genericodes-2026-05-15.zip", 2026, 5, 15);
        save();
        Path precious = output().resolve("downloaded");
        Files.createDirectories(precious);
        Files.writeString(precious.resolve("hand-written.txt"), "keep me");

        var failure = assertThrows(IOException.class, () -> new DeliveryPipeline().run(downloader(), output()));

        assertTrue(failure.getMessage().contains("delivery-index.json"), failure.getMessage());
        assertEquals("keep me", Files.readString(precious.resolve("hand-written.txt")));
    }

    @Test
    void refusesToWriteIntoTheDownloaderRepository() throws Exception {
        add("genericodes", "digital-genericodes-2026-05-15.zip", 2026, 5, 15);
        save();

        var failure = assertThrows(IOException.class,
                () -> new DeliveryPipeline().run(downloader(), downloader().resolve("src/test/resources")));

        assertTrue(failure.getMessage().contains("separate"), failure.getMessage());
    }

    /** A failure part way through must leave the previous trees untouched rather than half-replaced. */
    @Test
    void keepsThePreviousTreesWhenExtractionFails() throws Exception {
        add("genericodes", "digital-genericodes-2026-05-15.zip", 2026, 5, 15);
        save();
        new DeliveryPipeline().run(downloader(), output());
        var before = hashes(output());

        // An archive whose entry escapes its directory is refused by the extractor.
        Path broken = downloader().resolve("src/main/resources/downloaded-files/broken.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(broken))) {
            zip.putNextEntry(new ZipEntry("../escaped.gc"));
            zip.write("x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        metadata("broken", "broken.zip", broken, 2026, 5, 15);
        save();

        assertThrows(IOException.class, () -> new DeliveryPipeline().run(downloader(), output()));
        assertEquals(before, hashes(output()), "the previous trees must survive a failed run");
    }

    private void add(String name, String filename, int year, int month, int day) throws IOException {
        Path archive = downloader().resolve("src/main/resources/downloaded-files/" + name + ".zip");
        Files.createDirectories(archive.getParent());
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("EAS.gc"));
            zip.write(genericode());
            zip.closeEntry();
        }
        metadata(name, filename, archive, year, month, day);
    }

    private void metadata(String name, String filename, Path archive, int year, int month, int day)
            throws IOException {
        ObjectNode metadata = registry.putObject("https://example.test/" + name);
        metadata.put("url", "https://example.test/" + name);
        metadata.put("category", "EN 16931 code list - GeneriCode");
        metadata.put("downloaded", true);
        metadata.put("localPath", downloader().relativize(archive).toString());
        metadata.put("actual_hash", "0".repeat(64));
        metadata.put("version", "17");
        metadata.put("filename", filename);
        metadata.putArray("effective_date").add(year).add(month).add(day);
    }

    private static byte[] genericode() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <gc:CodeList xmlns:gc="http://docs.oasis-open.org/codelist/ns/genericode/1.0/">
                  <Identification><ShortName>Example</ShortName><Version>2026-05-15</Version>
                    <CanonicalUri>urn:example</CanonicalUri>
                    <CanonicalVersionUri>urn:example:2026-05-15</CanonicalVersionUri>
                  </Identification>
                  <ColumnSet>
                    <Column Id="Code" Use="required"><ShortName>Code</ShortName><Data Type="string"/></Column>
                    <Key Id="CodeKey"><ShortName>Code</ShortName><ColumnRef Ref="Code"/></Key>
                  </ColumnSet>
                  <SimpleCodeList>
                    <Row><Value ColumnRef="Code"><SimpleValue>0009</SimpleValue></Value></Row>
                    <Row><Value ColumnRef="Code"><SimpleValue>0002</SimpleValue></Value></Row>
                  </SimpleCodeList>
                </gc:CodeList>
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private Path downloader() throws IOException {
        Path root = temp.resolve("downloader");
        Files.createDirectories(root.resolve("src/main/resources"));
        return root;
    }

    private Path output() throws IOException {
        Path root = temp.resolve("normalizer/src/test/resources");
        Files.createDirectories(root);
        return root;
    }

    private void save() throws IOException {
        json.writeValue(downloader().resolve("src/main/resources/downloaded-files.json").toFile(), registry);
    }

    private java.util.Map<Path, String> hashes(Path root) throws IOException {
        var result = new java.util.HashMap<Path, String>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                result.put(root.relativize(path),
                        HexFormatOf(java.security.MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(path))));
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return result;
    }

    private static String HexFormatOf(byte[] digest) {
        return java.util.HexFormat.of().formatHex(digest);
    }
}
