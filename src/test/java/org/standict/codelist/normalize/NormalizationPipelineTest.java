package org.standict.codelist.normalize;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

class NormalizationPipelineTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();
    private final ObjectNode registry = json.createObjectNode();
    private final ObjectNode revisions = json.createObjectNode().put("format_version", 1);

    private NormalizationPipeline pipeline() throws IOException {
        if (!revisions.has("assignments")) revisions.putArray("assignments");
        return new NormalizationPipeline(ReleaseRevisions.read(revisions));
    }

    private void assign(ObjectNode metadata, int revision) {
        String format = metadata.path("category").asText().endsWith("XLSX") ? "xlsx" : "gc";
        var assignment = revisions.withArray("assignments").addObject();
        assignment.put("effective_date", "2026-05-15").put("version", metadata.path("version").asText()).put("revision", revision);
        assignment.putObject("sources").put(format, metadata.path("actual_hash").asText());
    }

    @Test
    void keepsVersionsAndRevisionsSideBySideWithRepeatableOutputAndUntouchedSources() throws Exception {
        addArchive("older", "13", "EAS.gc", GenericodeNormalizerTest.xml("0009", "0002"));
        var old = addArchive("revision1", "17", "EAS.gc", GenericodeNormalizerTest.xml("0009", "0002"));
        old.put("superseded_by", "https://example.test/revision2");
        var current = addArchive("revision2", "17", "EAS.gc", GenericodeNormalizerTest.xml("0002"));
        assign(old, 1);
        assign(current, 2);
        saveRegistry();
        Map<Path, String> beforeSources = hashes(input());
        var pipeline = pipeline();
        var summary = pipeline.run(input(), output());
        assertEquals(new NormalizationPipeline.Summary(3, 3, 5, 0, 0), summary);
        assertTrue(Files.exists(output().resolve("13_2026-05-15/r01/normalized/gc/EAS.gc")));
        assertTrue(Files.exists(output().resolve("17_2026-05-15/r01/normalized/gc/EAS.gc")));
        assertTrue(Files.exists(output().resolve("17_2026-05-15/r02/normalized/gc/EAS.gc")));
        var manifest = json.readTree(output().resolve("17_2026-05-15/r02/normalized/gc/source.json").toFile());
        assertEquals(1, manifest.path("files").get(0).path("rows").asInt());
        assertEquals(registry.path("https://example.test/revision2").path("actual_hash"), manifest.path("source_sha256"));
        assertEquals(2, manifest.path("release_revision").asInt());
        assertEquals("17_2026-05-15", manifest.path("release_directory").asText());
        assertEquals("17_2026-05-15/r02", manifest.path("revision_directory").asText());
        assertEquals("revision2.zip", manifest.path("source_filename").asText());
        Map<Path, String> first = hashes(output());
        var modifiedTime = Files.getLastModifiedTime(output().resolve("17_2026-05-15/r01/normalized/gc/EAS.gc"));
        pipeline.run(input(), output());
        assertEquals(first, hashes(output()));
        assertEquals(modifiedTime, Files.getLastModifiedTime(output().resolve("17_2026-05-15/r01/normalized/gc/EAS.gc")));
        assertEquals(beforeSources, hashes(input()));
    }

    @Test
    void badHashDoesNotPublishPartialResults() throws Exception {
        addArchive("good", "13", "EAS.gc", GenericodeNormalizerTest.xml("1"));
        addArchive("bad", "17", "EAS.gc", GenericodeNormalizerTest.xml("2")).put("actual_hash", "0".repeat(64));
        saveRegistry();
        var error = assertThrows(IOException.class, () -> pipeline().run(input(), output()));
        assertTrue(error.getMessage().contains("SHA-256 mismatch"));
        assertFalse(Files.exists(output()));
    }

    @Test
    void invalidXmlLeavesPreviouslyGeneratedOutputIntact() throws Exception {
        addArchive("good", "13", "EAS.gc", GenericodeNormalizerTest.xml("1"));
        saveRegistry();
        var pipeline = pipeline();
        pipeline.run(input(), output());
        var before = hashes(output());
        addArchive("bad", "17", "EAS.gc", "<broken>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        saveRegistry();
        assertThrows(IOException.class, () -> pipeline.run(input(), output()));
        assertEquals(before, hashes(output()));
    }

    @Test
    void rejectsConflictingVersionDirectories() throws Exception {
        addArchive("one", "17", "EAS.gc", GenericodeNormalizerTest.xml("1"));
        addArchive("two", "17", "EAS.gc", GenericodeNormalizerTest.xml("2"));
        saveRegistry();
        assertThrows(IOException.class, () -> pipeline().run(input(), output()));
        assertFalse(Files.exists(output()));
    }

    @Test
    void rejectsOutputInsideDownloaderIncludingSymlinkAliases() throws Exception {
        addArchive("one", "17", "EAS.gc", GenericodeNormalizerTest.xml("1"));
        saveRegistry();
        var before = hashes(input());
        var pipeline = pipeline();
        assertThrows(IOException.class, () -> pipeline.run(input(), input().resolve("normalized")));
        Path alias = temp.resolve("alias");
        Files.createSymbolicLink(alias, input());
        assertThrows(IOException.class, () -> pipeline.run(input(), alias.resolve("normalized")));
        assertEquals(before, hashes(input()));
    }

    @Test
    void rejectsUnsafeArchiveEntriesAndMissingSourceFiles() throws Exception {
        var metadata = addArchive("one", "17", "../EAS.gc", GenericodeNormalizerTest.xml("1"));
        saveRegistry();
        assertThrows(IOException.class, () -> pipeline().run(input(), output()));
        Files.delete(input().resolve(metadata.path("localPath").asText()));
        assertThrows(IOException.class, () -> pipeline().run(input(), output()));
        assertFalse(Files.exists(output()));
    }

    @Test
    void keepsExtractedAndNormalizedOutputsIncludingRevisionsWithoutChangingSources() throws Exception {
        var gc = addArchive("gc", "17", "EAS.gc", GenericodeNormalizerTest.xml("2", "1"));
        var old = addWorkbook("old", "17", "Older attachment");
        old.put("superseded_by", "https://example.test/current");
        var current = addWorkbook("current", "17", "Current attachment");
        assign(gc, 2);
        assign(old, 1);
        assign(current, 2);
        saveRegistry();
        var beforeSources = hashes(input());
        var pipeline = pipeline();
        assertEquals(new NormalizationPipeline.Summary(1, 1, 2, 2, 4), pipeline.run(input(), output()));
        Path csv = output().resolve("17_2026-05-15/r02/extracted/xlsx/EAS.csv");
        assertEquals("\"Code\",\"Name\"\n\"0002\",\"Current attachment\"\n\"0001\",\"\"\n", Files.readString(csv));
        Path normalizedCsv = output().resolve("17_2026-05-15/r02/normalized/xlsx/EAS.csv");
        assertEquals("\"Code\",\"Name\"\n\"0001\",\"\"\n\"0002\",\"Current attachment\"\n",
                Files.readString(normalizedCsv));
        assertArrayEquals(GenericodeNormalizerTest.xml("2", "1"),
                Files.readAllBytes(output().resolve("17_2026-05-15/r02/extracted/gc/EAS.gc")));
        assertTrue(Files.exists(output().resolve("17_2026-05-15/r02/normalized/gc/EAS.gc")));
        assertFalse(Files.exists(output().resolve("17_2026-05-15/r01/normalized/gc")));
        assertTrue(Files.exists(output().resolve("17_2026-05-15/r02/extracted/xlsx/Index.csv")));
        Path revision = output().resolve("17_2026-05-15/r01/extracted/xlsx");
        assertTrue(Files.readString(revision.resolve("EAS.csv")).contains("Older attachment"));
        var manifest = json.readTree(csv.resolveSibling("source.json").toFile());
        assertFalse(manifest.path("normalized").asBoolean(true));
        var normalizedManifest = json.readTree(normalizedCsv.resolveSibling("source.json").toFile());
        assertTrue(normalizedManifest.path("normalized").asBoolean());
        assertEquals(NormalizationPipeline.sha256(normalizedCsv),
                normalizedManifest.path("files").get(1).path("sha256").asText());
        assertEquals("Index", manifest.path("files").get(0).path("source_sheet").asText());
        assertEquals(NormalizationPipeline.sha256(csv), manifest.path("files").get(1).path("sha256").asText());
        assertEquals(3, manifest.path("files").get(1).path("rows").asInt());
        assertEquals("[4,5]", manifest.path("files").get(1).path("omitted_rows").toString(),
                "the whitespace-only and the empty row, so CSV records can be cited by workbook row");
        var index = json.readTree(output().resolve("normalization.json").toFile());
        assertEquals(5, index.path("format_version").asInt());
        assertEquals("17_2026-05-15/r02/normalized/gc", index.path("archives").get(0).path("directory").asText());
        assertEquals("17_2026-05-15/r02/extracted/gc", index.path("archives").get(0).path("extracted_directory").asText());
        assertEquals("17_2026-05-15/r01/normalized/xlsx", index.path("workbooks").get(0).path("directory").asText());
        assertEquals(2, index.path("workbooks").size());
        var first = hashes(output());
        var modifiedTime = Files.getLastModifiedTime(csv);
        var normalizedModifiedTime = Files.getLastModifiedTime(normalizedCsv);
        // Source ordering in the registry must not affect revision names or bytes.
        var reversed = json.createObjectNode();
        var names = new java.util.ArrayList<String>();
        registry.fieldNames().forEachRemaining(names::add);
        java.util.Collections.reverse(names);
        for (String name : names) reversed.set(name, registry.get(name));
        json.writeValue(input().resolve("src/main/resources/downloaded-files.json").toFile(), reversed);
        beforeSources = hashes(input());
        pipeline.run(input(), output());
        assertEquals(first, hashes(output()));
        assertEquals(modifiedTime, Files.getLastModifiedTime(csv));
        assertEquals(normalizedModifiedTime, Files.getLastModifiedTime(normalizedCsv));
        assertEquals(beforeSources, hashes(input()));
    }

    @Test
    void retainsAmbiguousWorkbookVariantsAndSupportsSpreadsheetOnlyReleases() throws Exception {
        var one = addWorkbook("one", "6", "First variant");
        var two = addWorkbook("two", "6", "Second variant");
        assign(one, 1);
        assign(two, 2);
        addWorkbook("newer", "7", "Next release");
        saveRegistry();
        assertEquals(new NormalizationPipeline.Summary(0, 0, 0, 3, 6), pipeline().run(input(), output()));
        for (int revision : new int[] {1, 2}) {
            Path directory = output().resolve("06_2026-05-15/r0" + revision + "/extracted/xlsx");
            assertTrue(Files.exists(directory.resolve("EAS.csv")));
            assertFalse(json.readTree(directory.resolve("source.json").toFile()).has("superseded_by"));
        }
        assertTrue(Files.exists(output().resolve("07_2026-05-15/r01/extracted/xlsx/EAS.csv")));
    }

    @Test
    void invalidWorkbookAndWorkbookHashMismatchDoNotPublishPartialResults() throws Exception {
        addArchive("gc", "13", "EAS.gc", GenericodeNormalizerTest.xml("1"));
        var workbook = addWorkbook("workbook", "17", "Good workbook");
        String hash = workbook.path("actual_hash").asText();
        workbook.put("actual_hash", "0".repeat(64));
        saveRegistry();
        var pipeline = pipeline();
        var error = assertThrows(IOException.class, () -> pipeline.run(input(), output()));
        assertTrue(error.getMessage().contains("SHA-256 mismatch"));
        assertFalse(Files.exists(output()));
        workbook.put("actual_hash", hash);
        saveRegistry();
        pipeline.run(input(), output());
        var before = hashes(output());
        Path source = input().resolve(workbook.path("localPath").asText());
        Files.writeString(source, "This is not an Excel workbook");
        workbook.put("actual_hash", NormalizationPipeline.sha256(source));
        saveRegistry();
        assertThrows(IOException.class, () -> pipeline.run(input(), output()));
        assertEquals(before, hashes(output()));
    }

    @Test
    void ambiguousReleasesRequireEverySourceToBeAssignedBeforePublishing() throws Exception {
        var old = addWorkbook("old", "17", "Old");
        var current = addWorkbook("current", "17", "Current");
        var gc = addArchive("gc", "17", "EAS.gc", GenericodeNormalizerTest.xml("1"));
        saveRegistry();
        assertTrue(assertThrows(IOException.class, () -> pipeline().run(input(), output())).getMessage().contains("Missing release-revisions"));
        assign(old, 1);
        assign(current, 2);
        assertThrows(IOException.class, () -> pipeline().run(input(), output()));
        assertFalse(Files.exists(output()));
        assign(gc, 2);
        pipeline().run(input(), output());
        var before = hashes(output());
        addWorkbook("third", "17", "Unassigned new revision");
        saveRegistry();
        assertThrows(IOException.class, () -> pipeline().run(input(), output()));
        assertEquals(before, hashes(output()));
    }

    @Test
    void explicitlyPairsOriginalAndReplacementGcAndWorkbookSources() throws Exception {
        var gc1 = addArchive("gc1", "17", "EAS.gc", GenericodeNormalizerTest.xml("2", "1"));
        var gc2 = addArchive("gc2", "17", "EAS.gc", GenericodeNormalizerTest.xml("1"));
        var xlsx1 = addWorkbook("xlsx1", "17", "Original");
        var xlsx2 = addWorkbook("xlsx2", "17", "Replacement");
        gc1.put("superseded_by", gc2.path("url").asText());
        xlsx1.put("superseded_by", xlsx2.path("url").asText());
        assign(gc2, 2);
        assign(xlsx1, 1);
        assign(gc1, 1);
        assign(xlsx2, 2);
        saveRegistry();
        pipeline().run(input(), output());
        var sources = new ObjectNode[][] {{gc1, xlsx1}, {gc2, xlsx2}};
        for (int revision = 1; revision <= 2; revision++) {
            String release = "17_2026-05-15/r0" + revision;
            for (int format = 0; format < 2; format++) {
                var manifest = json.readTree(output().resolve(release + "/normalized/"
                        + (format == 0 ? "gc" : "xlsx") + "/source.json").toFile());
                assertEquals(sources[revision - 1][format].path("actual_hash"), manifest.path("source_sha256"));
                assertEquals("17_2026-05-15", manifest.path("release_directory").asText());
                assertEquals(release, manifest.path("revision_directory").asText());
                assertEquals(revision, manifest.path("release_revision").asInt());
            }
        }
    }

    @Test
    void explicitAssignmentWinsEvenWhenOnlyOneSourceIsAvailable() throws Exception {
        var current = addWorkbook("current", "17", "Only downloaded revision");
        assign(current, 2);
        saveRegistry();
        pipeline().run(input(), output());
        assertTrue(Files.exists(output().resolve("17_2026-05-15/r02/extracted/xlsx/EAS.csv")));
        assertFalse(Files.exists(output().resolve("17_2026-05-15/r01")));
    }

    private Path input() { return temp.resolve("EU-Codelist-Downloader"); }
    private Path output() { return temp.resolve("EU-Codelist-Normalizer/src/test/resources"); }

    private ObjectNode addWorkbook(String name, String version, String description) throws Exception {
        Path file = input().resolve("src/main/resources/downloaded-files/" + name + ".xlsx");
        Files.createDirectories(file.getParent());
        try (var workbook = new XSSFWorkbook()) {
            workbook.createSheet("Index").createRow(0).createCell(0).setCellValue("Original notes");
            var sheet = workbook.createSheet("EAS");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Code");
            header.createCell(1).setCellValue("Name");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("0002");
            row.createCell(1).setCellValue(description);
            sheet.createRow(2).createCell(0).setCellValue("0001");
            sheet.createRow(3).createCell(0).setCellValue(" \t");
            sheet.createRow(4);
            try (var stream = Files.newOutputStream(file)) { workbook.write(stream); }
        }
        return addMetadata(name, version, file, "EN 16931 code list - XLSX");
    }

    /**
     * The digest names the downloader snapshot the outputs came from, so it must identify the consumed sources and
     * nothing else: re-serializing the registry in another key order, or adding an entry this pipeline ignores, leaves
     * it unchanged, while a different artefact behind the same URL changes it.
     */
    @Test
    void sourcesDigestIdentifiesTheConsumedSourcesOnly() throws Exception {
        addArchive("only", "17", "EAS.gc", GenericodeNormalizerTest.xml("0009", "0002"));
        saveRegistry();
        pipeline().run(input(), output());
        String digest = digest();
        assertTrue(digest.matches("[0-9a-f]{64}"), digest);

        // An entry outside the consumed categories must not move the digest.
        registry.putObject("https://example.test/guidance").put("url", "https://example.test/guidance")
                .put("category", "Technical guidance").put("downloaded", true);
        saveRegistry();
        pipeline().run(input(), output());
        assertEquals(digest, digest());

        // A different artefact behind the same URL must move it.
        addArchive("only", "17", "EAS.gc", GenericodeNormalizerTest.xml("0002"));
        saveRegistry();
        pipeline().run(input(), output());
        assertNotEquals(digest, digest());
    }

    private String digest() throws IOException {
        return json.readTree(output().resolve("normalization.json").toFile()).path("sources_digest").asText();
    }

    private ObjectNode addArchive(String name, String version, String entry, byte[] contents) throws Exception {
        Path archive = input().resolve("src/main/resources/downloaded-files/" + name + ".zip");
        Files.createDirectories(archive.getParent());
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(contents);
            zip.closeEntry();
        }
        return addMetadata(name, version, archive, "EN 16931 code list - GeneriCode");
    }

    private ObjectNode addMetadata(String name, String version, Path archive, String category) throws IOException {
        ObjectNode metadata = registry.putObject("https://example.test/" + name);
        metadata.put("url", "https://example.test/" + name);
        metadata.put("category", category);
        metadata.put("downloaded", true);
        metadata.put("localPath", input().relativize(archive).toString());
        metadata.put("actual_hash", NormalizationPipeline.sha256(archive));
        metadata.put("version", version);
        metadata.putArray("effective_date").add(2026).add(5).add(15);
        return metadata;
    }

    private void saveRegistry() throws IOException {
        json.writeValue(input().resolve("src/main/resources/downloaded-files.json").toFile(), registry);
    }

    private Map<Path, String> hashes(Path root) throws IOException {
        var result = new HashMap<Path, String>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                result.put(root.relativize(path), NormalizationPipeline.sha256(path));
            }
        }
        return result;
    }
}
