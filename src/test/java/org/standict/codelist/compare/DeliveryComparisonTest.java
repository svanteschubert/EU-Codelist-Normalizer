package org.standict.codelist.compare;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link CodeListDiff} and {@link DeliveryComparison}. */
class DeliveryComparisonTest {
    @TempDir Path temp;

    @Test
    void reportsAddedRemovedAndRewordedCodes() throws Exception {
        var before = new CodeListReader().read(genericode(Map.of("0002", "France", "0009", "SIRET")), "before");
        var after = new CodeListReader().read(genericode(Map.of("0002", "France", "0088", "GLN")), "after");

        var result = new CodeListDiff().compare(before, after);

        assertEquals(1, result.added());
        assertEquals(1, result.removed());
        assertEquals(0, result.changed());
        assertEquals(1, result.unchanged());
        assertEquals(List.of("0002", "0009", "0088"),
                java.util.stream.Stream.concat(result.entries().stream().map(CodeListDiff.Entry::code),
                        java.util.stream.Stream.of("0002")).distinct().sorted().toList());
    }

    /** A reworded name keeps every document valid while changing what the code means, so it is reported. */
    @Test
    void reportsAColumnWhoseWordingChanged() throws Exception {
        var before = new CodeListReader().read(genericode(Map.of("0002", "System Information et Repertoire")), "before");
        var after = new CodeListReader().read(genericode(Map.of("0002", "SIRENE")), "after");

        var result = new CodeListDiff().compare(before, after);

        assertEquals(0, result.added() + result.removed());
        assertEquals(1, result.changed());
        var entry = result.entries().get(0);
        assertEquals(CodeListDiff.Change.CHANGED, entry.change());
        assertEquals("Name", entry.column());
        assertEquals("SIRENE", entry.after());
    }

    @Test
    void comparesConsecutiveDeliveriesAndWritesASummary() throws Exception {
        write("2025-11-15/digital-genericodes/EAS.gc", Map.of("0002", "France", "0009", "SIRET"));
        write("2026-05-15/digital-genericodes/EAS.gc", Map.of("0002", "France", "0088", "GLN"));

        var summary = new DeliveryComparison().compare(normalized(), compared());

        assertEquals(1, summary.deliveryPairs());
        assertEquals(1, summary.added());
        assertEquals(1, summary.removed());
        String report = Files.readString(compared().resolve("2025-11-15__2026-05-15/EAS.csv"));
        assertTrue(report.contains("\"removed\",\"0009\""), report);
        assertTrue(report.contains("\"added\",\"0088\""), report);
        assertTrue(Files.readString(compared().resolve("summary.csv")).contains("\"EAS.gc\",\"1\",\"1\""),
                Files.readString(compared().resolve("summary.csv")));
    }

    /** What a correction corrected is invisible today, because the superseded publication disappears. */
    @Test
    void comparesTheRevisionsOfACorrectedArtefactWithinOneDelivery() throws Exception {
        write("2026-05-15/digital-genericodes/EAS.gc", Map.of("0002", "France"));
        write("2026-05-15/digital-genericodes_revision02/EAS.gc", Map.of("0002", "France", "0088", "GLN"));

        var summary = new DeliveryComparison().compare(normalized(), compared());

        assertEquals(1, summary.corrections());
        String report = Files.readString(
                compared().resolve("2026-05-15-digital-genericodes-r1__2026-05-15-digital-genericodes-r2/EAS.csv"));
        assertTrue(report.contains("\"added\",\"0088\""), report);
    }

    /** The delivery's effective content is its highest revision, so the next delivery is compared against that. */
    @Test
    void comparesTheNextDeliveryAgainstTheHighestRevision() throws Exception {
        write("2025-11-15/digital-genericodes/EAS.gc", Map.of("0002", "France"));
        write("2025-11-15/digital-genericodes_revision02/EAS.gc", Map.of("0002", "France", "0088", "GLN"));
        write("2026-05-15/digital-genericodes/EAS.gc", Map.of("0002", "France", "0088", "GLN"));

        new DeliveryComparison().compare(normalized(), compared());

        assertFalse(Files.exists(compared().resolve("2025-11-15__2026-05-15/EAS.csv")),
                "against r02 the next delivery is unchanged, so there is nothing to report");
    }

    @Test
    void reportsNothingForAnUnchangedDelivery() throws Exception {
        write("2025-11-15/digital-genericodes/EAS.gc", Map.of("0002", "France"));
        write("2026-05-15/digital-genericodes/EAS.gc", Map.of("0002", "France"));

        var summary = new DeliveryComparison().compare(normalized(), compared());

        assertEquals(0, summary.added() + summary.removed() + summary.changed());
        assertEquals("\"from\",\"to\",\"code list\",\"added\",\"removed\",\"changed\",\"unchanged\"\n",
                Files.readString(compared().resolve("summary.csv")));
    }

    private void write(String relative, Map<String, String> codes) throws IOException {
        Path file = normalized().resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, genericode(codes));
    }

    private static byte[] genericode(Map<String, String> codes) {
        var rows = new StringBuilder();
        new java.util.TreeMap<>(codes).forEach((code, name) -> rows
                .append("<Row><Value ColumnRef=\"Code\"><SimpleValue>").append(code)
                .append("</SimpleValue></Value><Value ColumnRef=\"Name\"><SimpleValue>").append(name)
                .append("</SimpleValue></Value></Row>"));
        return ("""
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
                  <SimpleCodeList>%s</SimpleCodeList>
                </gc:CodeList>
                """.formatted(rows)).getBytes(StandardCharsets.UTF_8);
    }

    private Path normalized() {
        return temp.resolve("normalized");
    }

    private Path compared() {
        return temp.resolve("compared");
    }
}
