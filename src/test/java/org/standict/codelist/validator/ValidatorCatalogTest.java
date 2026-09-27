package org.standict.codelist.validator;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ValidatorCatalog} and its bundled resources. */
class ValidatorCatalogTest {
    @Test
    void loadsTheBundledReleasesInOrder() throws Exception {
        var catalog = ValidatorCatalog.load();

        assertFalse(catalog.releases().isEmpty());
        for (int i = 1; i < catalog.releases().size(); i++) {
            assertFalse(catalog.releases().get(i).effectiveDate()
                    .isBefore(catalog.releases().get(i - 1).effectiveDate()), catalog.releases().get(i).tag());
        }
    }

    /** 1.3.14.1 replaced 1.3.14 before it came into force, so it wins on their shared date. */
    @Test
    void theLaterOfTwoReleasesWithOneDateIsInForce() throws Exception {
        var catalog = ValidatorCatalog.load();

        assertEquals("validation-1.3.14.1", catalog.inForce(LocalDate.of(2025, 5, 15)).orElseThrow().tag());
        assertEquals("validation-1.3.14.1", catalog.inForce(LocalDate.of(2025, 6, 14)).orElseThrow().tag());
        assertEquals("validation-1.3.14.2", catalog.inForce(LocalDate.of(2025, 6, 15)).orElseThrow().tag());
        assertTrue(catalog.inForce(LocalDate.of(2018, 1, 1)).isEmpty());
    }

    /** The Time sheet holds UNTDID 2005 for UBL and UNTDID 2475 for CII; each syntax reads its own column. */
    @Test
    void mapsBrCl06ToTheColumnOfEachSyntax() throws Exception {
        var catalog = ValidatorCatalog.load();

        assertEquals("2005 Code", catalog.mapping(Syntax.UBL, "BR-CL-06").orElseThrow().spreadsheetColumn());
        assertEquals("2475 Code", catalog.mapping(Syntax.CII, "BR-CL-06").orElseThrow().spreadsheetColumn());
        assertEquals("ICD", catalog.mapping(Syntax.CII, "BR-CL-26").orElseThrow().codeList());
        assertTrue(catalog.mapping(Syntax.UBL, "BR-CL-99").isEmpty());
    }
}
