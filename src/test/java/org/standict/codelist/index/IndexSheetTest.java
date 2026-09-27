package org.standict.codelist.index;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link IndexSheet} and {@link BusinessTerms}. */
class IndexSheetTest {
    @TempDir Path temp;

    private static final String HEADER = "\"Code lists\",\"Tab name\",\"Version/as published on\",\"Usage\",\"Changes\","
            + "\"Remark on updates\",\"EN business terms where the code list is used.\"\n";

    /** Since November 2019 the dates open the sheet and the table starts on CSV row 5, workbook row 6. */
    @Test
    void readsDatesTableAndNotes() throws Exception {
        Path csv = write("Index.csv", "\"Publication date\",\"4/14/26\",\"\"\n\"Effective date\",\"5/15/26\",\"\"\n"
                + "\"Remarks\",\"This document contains a listing\"\n\"\",\"Only a simple listing\"\n" + HEADER
                + "\"ISO 4217 — Currency codes\",\"Currency\",\"2/1/25\",\"Full list\",\"Yes\",\"Added XCG\",\"BT-5, BT-6\"\n"
                + "\"ISO/IEC 6523\",\"ICD\",\"3/9/26\",\"Full list\",\"No\",\"\",\"BT-157-1, BT-29-1,BT-30-1\"\n"
                + "\"Note: version 17b\",\"\",\"\",\"\",\"\",\"\",\"\"\n");

        IndexSheet index = IndexSheet.read(csv);

        assertEquals(LocalDate.of(2026, 4, 14), index.publicationDate());
        assertEquals(LocalDate.of(2026, 5, 15), index.effectiveDate());
        assertEquals(List.of("Currency", "ICD"), index.entries().stream().map(IndexSheet.Entry::tab).toList());
        assertEquals("Added XCG", index.entries().get(0).remark());
        assertEquals(List.of("BT-29-1", "BT-30-1", "BT-157-1"), index.entries().get(1).businessTerms(),
                "in business-term order");
        assertEquals(List.of("Note: version 17b"), index.notes());
    }

    /** The 2019 workbooks open with the table and state their publication date on the Main sheet. */
    @Test
    void readsTheTableOnTheFirstRowAndDatesFromMain() throws Exception {
        Path csv = write("Index.csv", HEADER + "\"UNTDID 1001\",\"1001\",\"D16B\",\"Subset\",\"No\",\"\",\"BT-3\"\n");
        Path main = write("Main.csv", "\"Publication date\",\"3/15/19\"\n\"Remarks\",\"…\"\n");

        IndexSheet index = IndexSheet.read(csv).withDatesFrom(main);

        assertEquals(1, index.entries().size());
        assertEquals(LocalDate.of(2019, 3, 15), index.publicationDate());
        assertNull(index.effectiveDate());
    }

    @Test
    void normalizesBusinessTermSpellings() {
        assertEquals(List.of("BT-5", "BT-29-1", "BT-130"), IndexSheet.businessTerms("BT 130, bt-5; BT-29 - 1, BT-5"));
    }

    /** Every tab of the current workbooks has a 2017 row, and the two lists 2017 does not name are empty. */
    @Test
    void loadsThe2017Reference() throws Exception {
        var terms = BusinessTerms.load();

        assertEquals(List.of("BT-29-1", "BT-30-1", "BT-46-1", "BT-47-1", "BT-60-1", "BT-61-1", "BT-71-1", "BT-157-1"),
                terms.reference("ICD").orElseThrow().terms());
        assertEquals(List.of("BT-130", "BT-150"), terms.reference("Unit").orElseThrow().terms());
        assertTrue(terms.reference("FISCAL ID").orElseThrow().terms().isEmpty());
        for (String tab : List.of("1001", "1153", "5305", "Allowance", "Charge", "Country", "Currency", "EAS",
                "FISCAL ID", "ICD", "Item", "MIME", "Payment", "Text", "Time", "Unit", "VAT CAT", "VAT ID", "VATEX")) {
            assertTrue(terms.reference(tab).isPresent(), tab);
        }
    }

    @Test
    void comparesTheIndexWith2017AndWithThePreviousRelease() throws Exception {
        IndexSheet before = IndexSheet.read(write("before.csv", HEADER
                + "\"ISO 4217\",\"Currency\",\"\",\"\",\"No\",\"\",\"BT-5, BT-6\"\n"));
        IndexSheet after = IndexSheet.read(write("after.csv", HEADER
                + "\"ISO 4217\",\"Currency\",\"\",\"\",\"No\",\"\",\"BT-5, BT-184\"\n"
                + "\"UNTDID 5305\",\"5305\",\"\",\"\",\"No\",\"\",\"BT-95, BT-102, BT-118, BT-151\"\n"
                + "\"VAT category\",\"VAT CAT\",\"\",\"\",\"Fixed\",\"\",\"BT-95\"\n"));

        var checks = BusinessTerms.load().check(after, before, java.util.Map.of());

        var currency = checks.get(0);
        assertEquals(List.of("BT-184"), currency.onlyInIndex());
        assertEquals(List.of("BT-6"), currency.onlyIn2017());
        assertEquals(List.of("BT-184"), currency.addedSincePrevious());
        assertEquals(List.of("BT-6"), currency.removedSincePrevious());
        assertFalse(checks.get(1).differsFrom2017());
        assertEquals(List.of("VAT CAT"), checks.get(1).sharedWith().get("BT-95"));
    }

    /** "BT" alone is Bhutan; only the spelled-out BT-n counts as a business term outside the Index. */
    @Test
    void searchesComponentsForExplicitBusinessTermsOnly() throws Exception {
        Path sheets = Files.createDirectories(temp.resolve("xlsx"));
        Files.writeString(sheets.resolve("Country.csv"), "\"Code\",\"Name\"\n\"BT\",\"Bhutan\"\n\"BT 5\",\"x\"\n");
        Files.writeString(sheets.resolve("Unit.csv"), "\"Code\",\"Name\"\n\"C62\",\"one, see BT-130\"\n");

        var found = BusinessTerms.search(sheets, ".csv", List.of("Index.csv"));

        assertEquals(java.util.Map.of("Unit", java.util.Map.of("Unit.csv", List.of("BT-130"))), found);
    }

    private Path write(String name, String contents) throws Exception {
        Path file = temp.resolve(name);
        Files.writeString(file, contents, StandardCharsets.UTF_8);
        return file;
    }
}
