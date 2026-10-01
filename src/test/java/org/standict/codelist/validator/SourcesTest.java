package org.standict.codelist.validator;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.standict.codelist.statistics.Canonical;

/** Unit tests for {@link Sources}, over one release revision's extracted copies. */
class SourcesTest {
    @TempDir Path temp;

    private static final String BLOB = "https://github.com/o/EU-Codelist-Normalizer/blob/master/";
    private static final String ORIGINALS = "https://github.com/o/EU-Codelist-Downloader/blob/master/";
    private static final String REVISION = "17_2026-05-15/r02";
    private Sources sources;

    @BeforeEach
    void setUp() throws Exception {
        Path extracted = temp.resolve(REVISION).resolve("extracted");
        write(extracted.resolve("xlsx/Index.csv"), """
                "Publication date","4/14/26"
                "Effective date","5/15/26"
                "Code lists","Tab name","Changes","Remark on updates"
                "ISO 4217","Currency","Yes","Added XCG"
                """);
        // A name wrapped over two lines moves the next code one line further down.
        write(extracted.resolve("xlsx/VAT ID.csv"), """
                "Name","Code"
                "Netherlands Antillean
                guilder","ANG"
                "Caribbean guilder","XCG"
                """);
        write(extracted.resolve("xlsx/source.json"), """
                {"source_path": "src/main/resources/downloaded-files/EN 16931 code list - XLSX/v17b.xlsx",
                 "source_filename": "v17b.xlsx",
                 "files": [{"filename": "Index.csv", "omitted_rows": [3, 4]}, {"filename": "VAT ID.csv"}]}
                """);
        write(extracted.resolve("gc/Currency.gc"), """
                <gc:CodeList xmlns:gc="http://docs.oasis-open.org/codelist/ns/genericode/1.0/">
                  <ColumnSet><Key Id="k"><ShortName>k</ShortName><ColumnRef Ref="code"/></Key></ColumnSet>
                  <SimpleCodeList>
                    <Row>
                      <Value ColumnRef="name"><SimpleValue>XCG</SimpleValue></Value>
                      <Value ColumnRef="code">
                        <SimpleValue>ANG</SimpleValue>
                      </Value>
                    </Row>
                  </SimpleCodeList>
                </gc:CodeList>
                """);
        sources = new Sources(temp, BLOB, "src/test/resources", ORIGINALS);
    }

    @Test
    void linksACodeToItsLineAndNamesItsWorkbookCell() {
        var spot = sources.code(REVISION, "xlsx", "VAT ID", "XCG");

        assertEquals(BLOB + "src/test/resources/17_2026-05-15/r02/extracted/xlsx/VAT%20ID.csv?plain=1#L4", spot.url());
        assertEquals("VAT ID sheet, cell B3 — v17b.xlsx", spot.title());
    }

    /** "XCG" also appears as a name; only the key column counts. */
    @Test
    void linksAGenericodeCodeToTheLineOfItsKeyValue() {
        var spot = sources.code(REVISION, "gc", "Currency", "ANG");

        assertEquals(BLOB + "src/test/resources/17_2026-05-15/r02/extracted/gc/Currency.gc#L7", spot.url());
        assertFalse(sources.lists(REVISION, "gc", "Currency", "XCG"));
    }

    @Test
    void linksAMissingCodeToTheFileThatLacksIt() {
        var spot = sources.code(REVISION, "gc", "Currency", "XCG");

        assertEquals(BLOB + "src/test/resources/17_2026-05-15/r02/extracted/gc/Currency.gc", spot.url());
        assertTrue(spot.title().startsWith("XCG is not in the Currency.gc"), spot.title());
    }

    /** Rows 3 and 4 of the workbook were blank, so the Currency row, CSV record 4, is workbook row 6. */
    @Test
    void linksAnIndexCellAndTheOriginal() {
        var remark = sources.indexCell(REVISION, "Currency", "Remark on updates");

        assertEquals(BLOB + "src/test/resources/17_2026-05-15/r02/extracted/xlsx/Index.csv?plain=1#L4", remark.url());
        assertEquals("Index sheet, cell D6 (Remark on updates) — v17b.xlsx", remark.title());
        assertEquals("Index sheet, cell B2 (Effective date) — v17b.xlsx", sources.indexDate(REVISION).title());
        assertEquals(List.of(ORIGINALS + "src/main/resources/downloaded-files/EN%2016931%20code%20list%20-%20XLSX/v17b.xlsx"),
                sources.originals(REVISION).stream().map(Sources.Spot::url).toList());
    }

    @Test
    void mapsAComparedNormalizedFileToItsExtractedCopy() {
        var spot = sources.published(REVISION + "/normalized/xlsx/VAT ID.csv", "ANG");

        assertEquals(BLOB + "src/test/resources/17_2026-05-15/r02/extracted/xlsx/VAT%20ID.csv?plain=1#L3", spot.url(),
                "the line the code is written on, below the first line of the name wrapped before it");
        assertEquals(BLOB + "src/test/resources/17_2026-05-15/r02/extracted/gc/Currency.gc",
                sources.declared(REVISION + "/normalized/gc/Currency.gc").url(), "the file itself");
        assertEquals(BLOB + "src/test/resources/17_2026-05-15/r02/extracted/xlsx/VAT%20ID.csv?plain=1",
                sources.declared(REVISION + "/normalized/xlsx/VAT ID.csv [Code]").url(), "a labelled column's sheet");
    }

    @Test
    void linksNothingWithoutAPublishedCopy() {
        assertNull(Sources.none().code(REVISION, "xlsx", "VAT ID", "XCG"));
        assertTrue(Sources.none().originals(REVISION).isEmpty());
    }

    @Test
    void recordsTheLineEachCsvRecordStartsOn() {
        var records = Canonical.parseCsvRecords("\"a\",\"b\"\n\"c\nd\",\"e\"\n\"f\",\"\"\n");

        assertEquals(List.of(1, 2, 4), records.stream().map(Canonical.CsvRecord::line).toList());
        assertEquals(List.of("c\nd", "e"), records.get(1).cells());
        assertEquals(3, records.get(1).lineOf(1), "the cell after a wrapped one starts a line further down");
        assertEquals("AA7", Sources.cell(26, 7));
    }

    /**
     * A change links to the code-history commit of its release, at the line after the change, or, for a code that went,
     * at the line before it: the commit's diff shows both, dated by the effective date.
     */
    @Test
    void linksAChangeToItsLineInTheHistoryCommitOfItsRelease() throws Exception {
        Path repository = Files.createDirectories(temp.resolve("normalizer"));
        Path releases = Files.createDirectories(repository.resolve("src/test/resources"));
        git(repository, "init", "-q", "-b", "master");
        git(repository, "remote", "add", "origin", "https://github.com/o/EU-Codelist-Normalizer.git");
        write(releases.resolve("README.md"), "releases\n");
        commit(repository, "release tree", "");
        git(repository, "checkout", "-q", "--orphan", "code-history");
        git(repository, "rm", "-rq", "--cached", ".");
        write(repository.resolve("xlsx/ICD.csv"), "\"Code\",\"Name\"\n\"0199\",\"Legal Entity\"\n\"0241\",\"Name unknown\"\n");
        commit(repository, "16", "Code-List-Release: 16_2025-11-15");
        write(repository.resolve("xlsx/ICD.csv"), "\"Code\",\"Name\"\n\"0241\",\"Hitachi Rail\"\n\"0245\",\"New\"\n");
        commit(repository, "17", "Code-List-Release: 17_2026-05-15");
        String commit = gitOutput(repository, "rev-parse", "HEAD");
        git(repository, "checkout", "-q", "master");

        var history = Sources.of(releases, null);
        String diff = "https://github.com/o/EU-Codelist-Normalizer/commit/" + commit + "#diff-"
                + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest("xlsx/ICD.csv".getBytes(StandardCharsets.UTF_8)));

        assertEquals(diff + "R2", history.change("17_2026-05-15/r02", "xlsx", "ICD", "0241", false).url(),
                "the renamed code at its line after the change");
        assertEquals(diff + "R3", history.change("17_2026-05-15/r02", "xlsx", "ICD", "0245", false).url());
        assertEquals(diff + "L2", history.change("17_2026-05-15/r02", "xlsx", "ICD", "0199", true).url(),
                "the removed code at its line before the change");
        assertTrue(history.change("17_2026-05-15/r02", "xlsx", "ICD", "0241", false).title()
                .contains("in force from 2026-05-15"));
        assertNull(history.change("18_2026-11-15/r01", "xlsx", "ICD", "0241", false), "no commit for that date yet");
    }

    private static void commit(Path repository, String message, String trailer) throws Exception {
        git(repository, "add", "-A");
        git(repository, "-c", "user.name=Test", "-c", "user.email=test@example.org", "commit", "-q", "-m", message,
                "-m", trailer.isEmpty() ? "-" : trailer);
    }

    private static void git(Path directory, String... arguments) throws Exception {
        gitOutput(directory, arguments);
    }

    private static String gitOutput(Path directory, String... arguments) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        // The developer's own Git configuration, such as commit signing, must not reach the test repository.
        String nullDevice = System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null";
        builder.environment().put("GIT_CONFIG_GLOBAL", nullDevice);
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        Process process = builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File(nullDevice))).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output.strip();
    }

    private static void write(Path file, String contents) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents, StandardCharsets.UTF_8);
    }
}
