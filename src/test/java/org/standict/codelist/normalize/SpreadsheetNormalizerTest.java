package org.standict.codelist.normalize;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SpreadsheetNormalizerTest {
    private final SpreadsheetNormalizer normalizer = new SpreadsheetNormalizer();

    @Test
    void sortsByBase36PreservingValuesDuplicatesHeadersAndBlankRows() {
        var header = List.of("Code Values", "Name");
        var cbb = List.of("CBB", "Order shipment grouping reference");
        var cd = List.of("CD", " Credit note number");
        var cec = List.of("CEC", "Ceding company");
        var duplicate = List.of("CD", "  Grüß, \"世界\"\nmore  ");
        var blank = List.of("", "");
        var rows = List.of(header, cbb, blank, cd, cec, duplicate);
        var result = normalizer.normalize("1153", rows);
        assertEquals(List.of(header, cd, blank, duplicate, cbb, cec), result);
        assertEquals(List.of(header, cbb, blank, cd, cec, duplicate), rows);
        assertEquals(result, normalizer.normalize("1153", result));
    }

    @Test
    void locatesCountryCurrencyAndUnitCodesInsteadOfSortingDescriptionsOrSources() {
        for (var header : List.of(
                List.of("English short name", "Alpha-2 code"),
                List.of("Currency", "Alphabetic Code"),
                List.of("Source", "Code"))) {
            var rows = List.of(header, List.of("A", "CBB"), List.of("Z", "CD"), List.of("B", "CEC"));
            assertEquals(List.of(header, rows.get(2), rows.get(1), rows.get(3)), normalizer.normalize("Codes", rows));
        }
        var currency = List.of(List.of("ENTITY", "Currency", "Alphabetic Code"),
                List.of("A", "First", "USD"), List.of("Z", "Second", "EUR"));
        assertEquals(List.of(currency.get(0), currency.get(2), currency.get(1)), normalizer.normalize("Currency", currency));
    }

    @Test
    void preservesMultipleHeaderRowsAndSortsWholeCrossSyntaxMappingRows() {
        var title = List.of("UBL and UN/EDIFACT", "", "UN/CEFACT Cross Industry Invoice", "");
        var header = List.of("2005 Code", "Value", "2475 Code", "Value");
        var ten = List.of("10", "ten", "4", "ten mapping");
        var two = List.of("2", "two", "5", "two mapping");
        assertEquals(List.of(title, header, two, ten), normalizer.normalize("Time", List.of(title, header, ten, two)));
        var subtitle = List.of("Subset of UNECE 5153", "", "Subset of UNECE 1153", "");
        var vatHeader = List.of("Code", "Value", "Code", "Value");
        assertEquals(List.of(title, subtitle, vatHeader, two, ten),
                normalizer.normalize("VAT ID", List.of(title, subtitle, vatHeader, ten, two)));
    }

    @Test
    void preservesDocumentationAndEmptySheetsAndUsesTheGenericodeRulesForZerosAndPunctuation() {
        var rows = List.of(List.of("Code", "Name"), List.of("B", "First"), List.of("A", "Second"));
        assertEquals(rows, normalizer.normalize("Index", rows));
        assertEquals(rows, normalizer.normalize("Main", rows));
        var notes = List.of(List.of("Notes"), List.of("Zulu"), List.of("Alpha"));
        assertEquals(notes, normalizer.normalize("Notes", notes));
        assertEquals(List.of(), normalizer.normalize("Empty", List.of()));
        var mixed = List.of(List.of("Code"), List.of("VATEX-EU-2"), List.of("ZZ"), List.of("001"), List.of("VATEX-EU-1"));
        assertEquals(List.of(mixed.get(0), mixed.get(3), mixed.get(2), mixed.get(4), mixed.get(1)),
                normalizer.normalize("Mixed", mixed));
    }
}
