package org.standict.codelist.validator;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SchematronCodeLists}. */
class SchematronCodeListsTest {
    private final SchematronCodeLists reader = new SchematronCodeLists();

    /** UBL's BR-CL-01 lists invoice and credit note type codes separately; a document may carry either. */
    @Test
    void acceptsTheUnionOfEveryListInOneAssertion() throws Exception {
        var rules = reader.read(schematron("""
                <rule context="cbc:InvoiceTypeCode | cbc:CreditNoteTypeCode">
                  <assert id="BR-CL-01" test="(self::cbc:InvoiceTypeCode and ((not(contains(normalize-space(.), ' ')) \
                and contains(' 380 71 326 ', concat(' ', normalize-space(.), ' '))))) or (self::cbc:CreditNoteTypeCode \
                and ((not(contains(normalize-space(.), ' ')) and contains(' 381 261 ', concat(' ', normalize-space(.), ' ')))))">x</assert>
                </rule>
                """), "test");

        assertEquals(1, rules.size());
        assertEquals("BR-CL-01", rules.get(0).rule());
        assertEquals(List.of("71", "261", "326", "380", "381"), rules.get(0).codes(), "base-36 code order");
        assertEquals("cbc:InvoiceTypeCode | cbc:CreditNoteTypeCode", rules.get(0).context());
    }

    /** BR-CL-24 is a chain of comparisons rather than an enumeration. A stray space inside a literal is kept. */
    @Test
    void readsComparisonsWithALiteralAndKeepsTheLiteralExactly() throws Exception {
        var rules = reader.read(schematron("""
                <rule context="cbc:EmbeddedDocumentBinaryObject[@mimeCode]">
                  <assert id="BR-CL-24" test="((@mimeCode = 'application/pdf' or @mimeCode  = 'image/png' \
                or @mimeCode = 'application/vnd.openxmlformats-officedocument. spreadsheetml.sheet'))">x</assert>
                </rule>
                """), "test");

        assertEquals(List.of("application/pdf", "application/vnd.openxmlformats-officedocument. spreadsheetml.sheet",
                "image/png"), rules.get(0).codes());
    }

    /** Each code is found on its own line, even in a test that spans lines and contains a {@code >}. */
    @Test
    void recordsTheLineEachCodeIsListedOn() throws Exception {
        var rules = reader.read(schematron("""
                <rule context="cac:TaxCategory/cbc:ID">
                  <assert
                    test="count(.) > 0 and contains( ' AE L M ',concat(' ',normalize-space(.),' ') ) or
                          contains(' B S ', concat(' ', normalize-space(.), ' '))"
                    id="BR-CL-17">x</assert>
                </rule>
                """), "test");

        // Line 1 holds the pattern element, line 2 the rule, line 3 the assert tag.
        assertEquals(java.util.Map.of("AE", 4, "L", 4, "M", 4, "B", 5, "S", 5), rules.get(0).lines());
        assertEquals(4, rules.get(0).listLine());
    }

    /** An assertion without a code list, such as a cardinality check, is not a code-list rule. */
    @Test
    void ignoresAssertionsWithoutACodeList() throws Exception {
        var rules = reader.read(schematron("""
                <rule context="cac:Party">
                  <assert id="BR-08" test="exists(cac:PostalAddress)">x</assert>
                  <assert id="BR-CL-06" test="contains(' 3 35 432 ', concat(' ', normalize-space(.), ' '))">x</assert>
                </rule>
                """), "test");

        assertEquals(List.of("BR-CL-06"), rules.stream().map(SchematronCodeLists.RuleCodes::rule).toList());
    }

    /** Without an identifier the codes could not be tied to a published list; the release is rejected loudly. */
    @Test
    void rejectsACodeListAssertionWithoutAnId() {
        var failure = assertThrows(java.io.IOException.class, () -> reader.read(schematron("""
                <rule context="cbc:DocumentCurrencyCode">
                  <assert test="contains(' EUR USD ', concat(' ', normalize-space(.), ' '))">x</assert>
                </rule>
                """), "release:file.sch"));
        assertTrue(failure.getMessage().contains("release:file.sch"), failure.getMessage());
    }

    private static byte[] schematron(String rules) {
        return ("<pattern xmlns=\"http://purl.oclc.org/dsdl/schematron\" id=\"Codesmodel\">\n" + rules + "</pattern>\n")
                .getBytes(StandardCharsets.UTF_8);
    }
}
