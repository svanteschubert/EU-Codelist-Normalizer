package org.standict.codelist.delivery;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link DeliveryExtractor}. */
class DeliveryExtractorTest {
    @TempDir Path temp;

    @Test
    void writesArchiveEntriesVerbatimAndNormalizesCodeLists() throws Exception {
        Path archive = zip("digital-genericodes-2026-05-15.zip",
                entry("EAS.gc", genericode("0009", "0002")));

        var summary = new DeliveryExtractor().extract(List.of(artefact(archive, 1)), downloaded(), normalized());

        assertEquals(new DeliveryExtractor.Summary(1, 1, 1, 1), summary);
        Path verbatim = downloaded().resolve("2026-05-15/digital-genericodes-2026-05-15/EAS.gc");
        Path canonical = normalized().resolve("2026-05-15/digital-genericodes-2026-05-15/EAS.gc");
        assertArrayEquals(genericode("0009", "0002"), Files.readAllBytes(verbatim),
                "the downloaded copy must stay byte-identical to the archive entry");
        assertTrue(Files.exists(canonical));
        assertTrue(Files.readString(canonical).indexOf("0002") < Files.readString(canonical).indexOf("0009"),
                "the normalized copy must carry the sorted code order");
    }

    /** A correction lands beside its predecessor, never on top of it, because the suffix reaches the directory name. */
    @Test
    void keepsRevisionsSideBySide() throws Exception {
        Path first = zip("genericodes.zip", entry("EAS.gc", genericode("0009")));
        Path second = zip("genericodes-corrected.zip", entry("EAS.gc", genericode("0002")));

        new DeliveryExtractor().extract(
                List.of(artefact(first, 1, "genericodes.zip"), artefact(second, 2, "genericodes.zip")),
                downloaded(), normalized());

        assertTrue(Files.exists(downloaded().resolve("2026-05-15/genericodes/EAS.gc")));
        assertTrue(Files.exists(downloaded().resolve("2026-05-15/genericodes_revision02/EAS.gc")));
        assertEquals("0002", codeOf(downloaded().resolve("2026-05-15/genericodes_revision02/EAS.gc")));
        assertEquals("0009", codeOf(downloaded().resolve("2026-05-15/genericodes/EAS.gc")));
    }

    @Test
    void writesEverySheetAndItsSortedCounterpart() throws Exception {
        Path workbook = workbook("EN16931 code lists values v17.xlsx");

        var summary = new DeliveryExtractor().extract(List.of(artefact(workbook, 1)), downloaded(), normalized());

        assertEquals(1, summary.artefacts());
        Path folder = downloaded().resolve("2026-05-15/EN16931 code lists values v17");
        assertTrue(Files.exists(folder.resolve("Codes.csv")), Files.list(folder).toList().toString());
        assertTrue(Files.exists(normalized().resolve("2026-05-15/EN16931 code lists values v17/Codes.csv")));
    }

    /** Guidance and other artefacts with no canonical form are recorded, so the report cannot silently miss them. */
    @Test
    void recordsArtefactsWithoutACanonicalForm() throws Exception {
        Path guidance = temp.resolve("guidance.pdf");
        Files.writeString(guidance, "%PDF-1.7");

        var summary = new DeliveryExtractor().extract(List.of(artefact(guidance, 1)), downloaded(), normalized());

        assertEquals(new DeliveryExtractor.Summary(1, 1, 0, 0), summary);
        String manifest = Files.readString(downloaded().resolve("2026-05-15/delivery.json"));
        assertTrue(manifest.contains("\"extraction\" : \"none\""), manifest);
        assertTrue(manifest.contains("guidance.pdf"), manifest);
    }

    @Test
    void refusesAnArchiveEntryThatEscapesItsDirectory() throws Exception {
        Path archive = zip("evil.zip", entry("../escaped.gc", "x".getBytes(StandardCharsets.UTF_8)));

        var failure = assertThrows(IOException.class,
                () -> new DeliveryExtractor().extract(List.of(artefact(archive, 1)), downloaded(), normalized()));
        assertTrue(failure.getMessage().contains("Unsafe entry"), failure.getMessage());
    }

    @Test
    void writesOneManifestPerDelivery() throws Exception {
        Path archive = zip("genericodes.zip", entry("EAS.gc", genericode("0002")));

        new DeliveryExtractor().extract(List.of(artefact(archive, 1)), downloaded(), normalized());

        String manifest = Files.readString(downloaded().resolve("2026-05-15/delivery.json"));
        assertTrue(manifest.contains("\"effective_date\" : \"2026-05-15\""), manifest);
        assertTrue(manifest.contains("\"revision\" : 1"), manifest);
        assertTrue(manifest.contains("\"source_sha256\""), manifest);
    }

    /** A minimal Genericode code list; the order of the codes is what normalization is expected to change. */
    private static byte[] genericode(String... codes) {
        var rows = new StringBuilder();
        for (String code : codes) {
            rows.append("<Row><Value ColumnRef=\"Code\"><SimpleValue>").append(code)
                    .append("</SimpleValue></Value></Row>");
        }
        return ("""
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
                  <SimpleCodeList>%s</SimpleCodeList>
                </gc:CodeList>
                """.formatted(rows)).getBytes(StandardCharsets.UTF_8);
    }

    private String codeOf(Path genericode) throws IOException {
        String xml = Files.readString(genericode);
        int value = xml.indexOf("<SimpleValue>");
        return xml.substring(value + "<SimpleValue>".length(), xml.indexOf("</SimpleValue>", value));
    }

    private DeliveryLayout.Artefact artefact(Path source, int revision) {
        return artefact(source, revision, source.getFileName().toString());
    }

    private DeliveryLayout.Artefact artefact(Path source, int revision, String filename) {
        return new DeliveryLayout.Artefact("https://example.test/" + filename + "#" + revision,
                java.time.LocalDate.of(2026, 5, 15), "EN 16931 code list - GeneriCode", "17", filename,
                "0".repeat(64), source, revision);
    }

    private record Entry(String name, byte[] contents) {}

    private Entry entry(String name, byte[] contents) {
        return new Entry(name, contents);
    }

    private Path zip(String filename, Entry... entries) throws IOException {
        Path archive = temp.resolve(filename);
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.contents());
                zip.closeEntry();
            }
        }
        return archive;
    }

    private Path workbook(String filename) throws IOException {
        Path file = temp.resolve(filename);
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Codes");
            sheet.createRow(0).createCell(0).setCellValue("Code");
            sheet.createRow(1).createCell(0).setCellValue("0002");
            try (var out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }
        return file;
    }

    private Path downloaded() {
        return temp.resolve("out/downloaded");
    }

    private Path normalized() {
        return temp.resolve("out/normalized");
    }
}
