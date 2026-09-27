package org.standict.codelist.normalize;

import static org.junit.jupiter.api.Assertions.*;

import com.helger.genericode.Genericode10CodeListMarshaller;
import com.helger.genericode.Genericode10Helper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GenericodeNormalizerTest {
    private final GenericodeNormalizer normalizer = new GenericodeNormalizer();

    @Test
    void reproducesTheEarlierEasExample() throws Exception {
        byte[] input = getClass().getResourceAsStream("/13-EAS-input.gc").readAllBytes();
        byte[] expected = getClass().getResourceAsStream("/13-EAS-expected.gc").readAllBytes();
        var actual = normalizer.normalize(input);
        // Compare the complete JAXB model; XML serializers may spell equivalent markup differently.
        var reader = new Genericode10CodeListMarshaller();
        assertEquals(reader.read(new ByteArrayInputStream(expected)), reader.read(new ByteArrayInputStream(actual.xml())));
        assertArrayEquals(actual.xml(), normalizer.normalize(actual.xml()).xml());
    }

    @Test
    void sortsWithoutChangingLeadingZerosLabelsRemarksOrIdentification() throws Exception {
        var result = normalizer.normalize(xml("0106", "AN", "0007", "0002", "0009"));
        var reader = new Genericode10CodeListMarshaller();
        var before = reader.read(new ByteArrayInputStream(xml("0106", "AN", "0007", "0002", "0009")));
        var after = reader.read(new ByteArrayInputStream(result.xml()));
        assertEquals(List.of("0002", "0007", "0009", "AN", "0106"),
                after.getSimpleCodeList().getRow().stream().map(r -> Genericode10Helper.getRowValue(r, "Code")).toList());
        assertEquals(before.getIdentification(), after.getIdentification());
        assertEquals(before.getColumnSet(), after.getColumnSet());
        assertEquals(before.getAnnotation(), after.getAnnotation());
        for (var row : after.getSimpleCodeList().getRow()) {
            assertEquals("  Keep spaces & accents: é\nsecond line  ", Genericode10Helper.getRowValue(row, "Name"));
            assertEquals("Original remark", Genericode10Helper.getRowValue(row, "Remark"));
        }
        assertEquals(5, result.rows());
    }

    @Test
    void sortsDifferentLengthCodesByBase36ValueRatherThanAlphabetically() throws Exception {
        var result = normalizer.normalize(xml("CBB", "CD", "CEC"));
        var after = new Genericode10CodeListMarshaller().read(new ByteArrayInputStream(result.xml()));
        assertEquals(List.of("CD", "CBB", "CEC"),
                after.getSimpleCodeList().getRow().stream().map(r -> Genericode10Helper.getRowValue(r, "Code")).toList());
    }

    @Test
    void orderingHandlesLongCodesNumericTiesAndPunctuationConsistently() {
        var codes = new ArrayList<>(List.of("a", "A", "0001", "1", "2", "10", "VATEX-EU", "99999999999999999999999", "A-1"));
        codes.sort(GenericodeNormalizer::compareCodes);
        assertEquals(List.of("0001", "1", "2", "A", "a", "10", "99999999999999999999999", "A-1", "VATEX-EU"), codes);
        for (String a : codes) for (String b : codes) for (String c : codes) {
            if (GenericodeNormalizer.compareCodes(a, b) <= 0 && GenericodeNormalizer.compareCodes(b, c) <= 0) {
                assertTrue(GenericodeNormalizer.compareCodes(a, c) <= 0);
            }
        }
    }

    @Test
    void rejectsMissingAndDuplicateCodes() {
        assertThrows(IOException.class, () -> normalizer.normalize(xml("1", "1")));
        assertThrows(IOException.class, () -> normalizer.normalize(xml("")));
        String missing = new String(xml("1"), StandardCharsets.UTF_8)
                .replace("<Value ColumnRef=\"Code\"><SimpleValue>1</SimpleValue></Value>", "");
        assertThrows(IOException.class, () -> normalizer.normalize(missing.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsMalformedXmlAndExternalEntities() {
        assertThrows(IOException.class, () -> normalizer.normalize("<broken>".getBytes(StandardCharsets.UTF_8)));
        String doctype = "<!DOCTYPE gc:CodeList [<!ENTITY external SYSTEM 'file:///not-to-be-read'>]>";
        String xml = new String(xml("1"), StandardCharsets.UTF_8).replace("<gc:CodeList", doctype + "<gc:CodeList");
        assertThrows(IOException.class, () -> normalizer.normalize(xml.getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] xml(String... codes) {
        StringBuilder rows = new StringBuilder();
        for (String code : codes) rows.append("""
                <Row><Value ColumnRef="Code"><SimpleValue>%s</SimpleValue></Value>
                <Value ColumnRef="Name"><SimpleValue>  Keep spaces &amp; accents: é
                second line  </SimpleValue></Value>
                <Value ColumnRef="Remark"><SimpleValue>Original remark</SimpleValue></Value></Row>
                """.formatted(code));
        return ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <gc:CodeList xmlns:gc="http://docs.oasis-open.org/codelist/ns/genericode/1.0/">
                  <Annotation><AppInfo><note xmlns="urn:example">Keep original</note></AppInfo></Annotation>
                  <Identification><ShortName>Example</ShortName><Version>2026-05-15</Version>
                    <CanonicalUri>urn:example</CanonicalUri><CanonicalVersionUri>urn:example:2026-05-15</CanonicalVersionUri>
                  </Identification>
                  <ColumnSet>
                    <Column Id="Code" Use="required"><ShortName>Code</ShortName><Data Type="string"/></Column>
                    <Column Id="Name" Use="required"><ShortName>Name</ShortName><Data Type="string"/></Column>
                    <Column Id="Remark" Use="optional"><ShortName>Remark</ShortName><Data Type="string"/></Column>
                    <Key Id="CodeKey"><ShortName>Code</ShortName><ColumnRef Ref="Code"/></Key>
                  </ColumnSet>
                  <SimpleCodeList>%s</SimpleCodeList>
                </gc:CodeList>
                """.formatted(rows)).getBytes(StandardCharsets.UTF_8);
    }
}
