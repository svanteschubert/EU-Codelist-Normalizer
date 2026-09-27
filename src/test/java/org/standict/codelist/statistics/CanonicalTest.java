package org.standict.codelist.statistics;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link Canonical}. */
class CanonicalTest {
    @TempDir Path temp;

    /**
     * The EAS sheet heads its code column {@code "AES"}, a typo for the list it holds. Roles come from the column
     * positions, so the value is still read as a code and stays comparable with Genericode.
     */
    @Test
    void readsSpreadsheetRolesFromPositionsDespiteMisleadingHeaders() throws Exception {
        Path csv = temp.resolve("EAS.csv");
        Files.writeString(csv, """
                "AES","Scheme name"
                "0002","System Information et Repertoire"
                "0007","Organisationsnummer"
                """, StandardCharsets.UTF_8);

        List<Canonical.Row> rows = Canonical.readSpreadsheet(csv, "EAS");

        assertEquals(4, rows.size());
        var code = rows.get(0);
        assertEquals(Canonical.Role.CODE, code.role());
        assertEquals("AES", code.label(), "the source's own label is kept, however wrong it is");
        assertEquals("0002", code.value());
        var name = rows.get(1);
        assertEquals(Canonical.Role.NAME, name.role());
        assertEquals("Scheme name", name.label());
        assertEquals("System Information et Repertoire", name.value());
    }

    /** A third column is real published information, so it is kept as OTHER rather than dropped. */
    @Test
    void keepsColumnsBeyondTheNameAsOther() throws Exception {
        Path csv = temp.resolve("1001.csv");
        Files.writeString(csv, """
                "Code","Name","EN16931 interpretation"
                "71","Request for payment","Invoice"
                """, StandardCharsets.UTF_8);

        List<Canonical.Row> rows = Canonical.readSpreadsheet(csv, "1001");

        var other = rows.stream().filter(row -> row.role() == Canonical.Role.OTHER).findFirst().orElseThrow();
        assertEquals("EN16931 interpretation", other.label());
        assertEquals("Invoice", other.value());
    }

    @Test
    void skipsEmptyCellsAndBlankRows() throws Exception {
        Path csv = temp.resolve("VATEX.csv");
        Files.writeString(csv, """
                "CODE","Code name (english)","Remark"
                "VATEX-EU-132","Exempt based on article 132",""

                """, StandardCharsets.UTF_8);

        List<Canonical.Row> rows = Canonical.readSpreadsheet(csv, "VATEX");

        assertEquals(2, rows.size(), rows.toString());
        assertTrue(rows.stream().noneMatch(row -> row.value().isEmpty()));
    }

    /**
     * A repeated code is source data, not corruption: the Currency sheet is the ISO 4217 table with one row per
     * country, so ANG appears for Curacao and again for Sint Maarten. Both rows are read.
     */
    @Test
    void readsARepeatedCodeFromEveryRowThatCarriesIt() throws Exception {
        Path csv = temp.resolve("Currency.csv");
        Files.writeString(csv, """
                "ENTITY","Currency","Alphabetic Code"
                "CURA\u00c7AO","Netherlands Antillean Guilder","ANG"
                "SINT MAARTEN (DUTCH PART)","Netherlands Antillean Guilder","ANG"
                """, StandardCharsets.UTF_8);

        List<Canonical.Row> rows = Canonical.readSpreadsheet(csv, "Currency");

        assertEquals(2, rows.stream().filter(row -> row.role() == Canonical.Role.CODE).count());
        assertTrue(rows.stream().allMatch(row -> row.code().equals("ANG")), rows.toString());
        assertEquals(List.of("CURA\u00c7AO", "SINT MAARTEN (DUTCH PART)"), rows.stream()
                .filter(row -> row.label().equals("ENTITY")).map(Canonical.Row::value).toList());
    }

    /**
     * The Currency sheet moved its code column between deliveries, so the column is found from the labels: the code is
     * the one mentioning a code, and the name the one labelled after the list.
     */
    @Test
    void findsTheCodeColumnWhereverTheHeaderPutsIt() throws Exception {
        Path csv = temp.resolve("Currency.csv");
        Files.writeString(csv, """
                "ENTITY","Currency","Alphabetic Code"
                "AFGHANISTAN","Afghani","AFN"
                """, StandardCharsets.UTF_8);

        List<Canonical.Row> rows = Canonical.readSpreadsheet(csv, "Currency");

        assertEquals("AFN", rows.stream().filter(row -> row.role() == Canonical.Role.CODE)
                .findFirst().orElseThrow().value());
        assertEquals("Afghani", rows.stream().filter(row -> row.role() == Canonical.Role.NAME)
                .findFirst().orElseThrow().value());
    }

    @Test
    void readsGenericodeColumnsIntoTheSameRoles() throws Exception {
        Path genericode = temp.resolve("EAS.gc");
        Files.writeString(genericode, """
                <?xml version="1.0" encoding="UTF-8"?>
                <gc:CodeList xmlns:gc="http://docs.oasis-open.org/codelist/ns/genericode/1.0/">
                  <Identification><ShortName>EAS</ShortName><Version>1</Version>
                    <CanonicalUri>urn:example</CanonicalUri>
                    <CanonicalVersionUri>urn:example:1</CanonicalVersionUri>
                  </Identification>
                  <ColumnSet>
                    <Column Id="Code" Use="required"><ShortName>Code</ShortName><Data Type="string"/></Column>
                    <Column Id="Name" Use="required"><ShortName>Name</ShortName><Data Type="string"/></Column>
                    <Key Id="CodeKey"><ShortName>Code</ShortName><ColumnRef Ref="Code"/></Key>
                  </ColumnSet>
                  <SimpleCodeList>
                    <Row><Value ColumnRef="Code"><SimpleValue>0002</SimpleValue></Value>
                    <Value ColumnRef="Name"><SimpleValue>System Information et Repertoire</SimpleValue></Value></Row>
                  </SimpleCodeList>
                </gc:CodeList>
                """, StandardCharsets.UTF_8);

        List<Canonical.Row> rows = Canonical.readGenericode(genericode, "EAS");

        assertEquals(List.of(Canonical.Role.CODE, Canonical.Role.NAME), rows.stream().map(Canonical.Row::role).toList());
        assertEquals("System Information et Repertoire",
                rows.stream().filter(row -> row.role() == Canonical.Role.NAME).findFirst().orElseThrow().value());
    }

    /**
     * The EAS sheet wraps scheme names over several lines inside one quoted cell. The continuation belongs to that
     * cell; read as a row of its own, its text would become a code that no other component publishes.
     */
    @Test
    void readsACellThatSpansSeveralLinesAsOneValue() throws Exception {
        Path csv = temp.resolve("EAS.csv");
        Files.writeString(csv, "\"AES\",\"Scheme name\"\n"
                + "\"0106\",\"Vereniging van Kamers van Koophandel en Fabrieken in Nederland\n"
                + "Chambers of Commerce and Industry in the Netherlands)\"\n"
                + "\"0135\",\"SIA Object Identifiers\"\n", StandardCharsets.UTF_8);

        List<Canonical.Row> rows = Canonical.readSpreadsheet(csv, "EAS");

        assertEquals(List.of("0106", "0135"), rows.stream().filter(row -> row.role() == Canonical.Role.CODE)
                .map(Canonical.Row::value).toList());
        assertTrue(rows.get(1).value().endsWith("\nChambers of Commerce and Industry in the Netherlands)"));
    }

    /** The catalogue must state the known spreadsheet quirks, or the comparison silently mistakes them for changes. */
    @Test
    void catalogueNamesTheMetadataSheetAndTheSpreadsheetOnlyLists() throws Exception {
        Canonical.Catalog catalog = Canonical.catalog();

        assertFalse(catalog.isCodeList("Index"), "Index states the delivery's dates, it is not a code list");
        assertFalse(catalog.isCodeList("Definitions"), "Definitions explains the columns");
        assertTrue(catalog.isCodeList("VAT CAT"), "a real code list, published in one component only");
        assertTrue(catalog.isCodeList("EAS"));
        assertEquals("EAS", catalog.codeListOf("EAS"));
        // The EAS workbook renamed its sheet three times for the same list.
        assertEquals("EAS", catalog.codeListOf("EAS code list"));
        assertEquals("EAS", catalog.codeListOf("CEF code list"));
        assertEquals("EAS", catalog.codeListOf("CEF EAS code list"));
        assertEquals("VATEX", catalog.codeListOf("VATEX code list"));
    }
}
