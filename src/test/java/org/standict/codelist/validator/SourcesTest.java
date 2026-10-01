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

    private static void write(Path file, String contents) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents, StandardCharsets.UTF_8);
    }
}
