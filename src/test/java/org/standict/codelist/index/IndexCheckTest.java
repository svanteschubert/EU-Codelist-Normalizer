package org.standict.codelist.index;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.standict.codelist.validator.CodeListReleases;

/** Unit tests for {@link IndexCheck}. */
class IndexCheckTest {
    @TempDir Path temp;

    private static final String HEADER = "\"Code lists\",\"Tab name\",\"Version/as published on\",\"Usage\",\"Changes\","
            + "\"Remark on updates\",\"EN business terms where the code list is used.\"\n";

    @Test
    void acceptsAnIndexRowThatStatesExactlyWhatChanged() {
        var findings = check("Yes", "Added CCC, removed BBB", changes(Set.of("CCC"), Set.of("BBB"), Set.of()),
                Set.of("AAA", "BBB"), Set.of("AAA", "CCC"));

        assertEquals(List.of(), findings);
    }

    @Test
    void reportsAFlagThatDeniesAChange() {
        var findings = check("No", "", changes(Set.of("XI"), Set.of(), Set.of()), Set.of("AA"), Set.of("AA", "XI"));

        assertEquals(List.of("Changes = No, but added: XI"), texts(findings));
    }

    @Test
    void reportsEveryClaimTheSheetDoesNotBearOut() {
        var findings = check("Yes", "Added SLE, removed SLL, TR name changed", changes(Set.of(), Set.of(), Set.of()),
                Set.of("SLE", "SLL", "TR"), Set.of("SLE", "SLL", "TR"));

        assertEquals(List.of("Changes = Yes, but no code was added, removed or renamed",
                "stated as added, but already listed before: SLE", "stated as removed, but still listed: SLL",
                "stated as renamed, but its name and columns are unchanged: TR"), texts(findings));
    }

    @Test
    void reportsChangesTheRemarkDoesNotState() {
        var findings = check("Yes", "Added 0239", changes(Set.of("0239", "0240"), Set.of("CUC"), Set.of("0096")),
                Set.of("0096", "CUC"), Set.of("0096", "0239", "0240"));

        assertEquals(List.of("added, not stated: 0240", "removed, not stated: CUC", "renamed, not stated: 0096"),
                texts(findings));
    }

    /** ICD 01'00 became 0100 in 2019, which the Index stated as "structure corrected". */
    @Test
    void acceptsAStatedSpellingCorrectionAndReportsAnUnstatedOne() {
        var stated = check("Yes", "0201 added, 0100 structure corrected",
                changes(Set.of("0100", "0201"), Set.of("01'00"), Set.of()), Set.of("01'00"), Set.of("0100", "0201"));
        assertEquals(List.of(), texts(stated));
        assertEquals("spelling corrected, as stated: 0100", stated.get(0).text());

        var unstated = check("Yes", "0201 added", changes(Set.of("0100", "0201"), Set.of("01'00"), Set.of()),
                Set.of("01'00"), Set.of("0100", "0201"));
        assertEquals(List.of("spelling corrected, not stated: 0100"), texts(unstated));
    }

    /** The sheet and the Genericode file of one revision must list the same codes. */
    @Test
    void reportsCodesTheSheetAndGenericodeDoNotShare() throws Exception {
        release("01_2021-05-17", "r01", "5/17/21", "", "", "AAA BBB", "AAA CCC", "AA");

        var currency = tab(new IndexCheck().check(CodeListReleases.read(temp)), "01_2021-05-17/r01", "Currency");

        assertEquals(Set.of("BBB"), currency.listed().removed(), "only in the sheet");
        assertEquals(Set.of("CCC"), currency.listed().added(), "only in Genericode");
        assertEquals(List.of("listed in the sheet, not in Genericode: BBB",
                "listed in Genericode, not in the sheet: CCC"), texts(currency.findings()));
    }

    /** A remark that counts instead of naming is accepted when the count is right. */
    @Test
    void acceptsACountThatMatches() {
        var findings = check("Yes", "adding 2 codes", changes(Set.of("XO1", "XO2"), Set.of(), Set.of()),
                Set.of("C62"), Set.of("C62", "XO1", "XO2"));

        assertEquals(IndexCheck.Severity.INFO, findings.get(0).severity(), findings.toString());
    }

    /**
     * Release 03 corrects its first revision, whose Index claimed a removal its sheet did not make. Release 02 has no
     * Genericode, so release 03's Genericode is compared with release 01's, across both Index sheets.
     */
    @Test
    void checksEveryRevisionAndComparesGenericodeAcrossAGap() throws Exception {
        release("01_2021-05-17", "r01", "5/17/21", "", "", "AAA BBB", "AAA BBB", "AA");
        release("02_2021-11-15", "r01", "11/15/21", "Yes", "Added CCC", "AAA BBB CCC", null, "AA XI");
        release("03_2022-05-16", "r01", "5/15/22", "Yes", "Added DDD, removed BBB", "AAA BBB CCC DDD",
                "AAA BBB CCC DDD", "AA XI");
        release("03_2022-05-16", "r02", "5/15/22", "Yes", "Added DDD, removed BBB", "AAA CCC DDD", "AAA CCC DDD",
                "AA XI");

        var report = new IndexCheck().check(CodeListReleases.read(temp));

        assertEquals(List.of("01_2021-05-17/r01", "02_2021-11-15/r01", "03_2022-05-16/r01", "03_2022-05-16/r02"),
                report.revisions().stream().map(IndexCheck.RevisionCheck::revision).toList());
        var country = tab(report, "02_2021-11-15/r01", "Country");
        assertEquals(List.of("Changes = No, but added: XI"), texts(country.findings()));

        var first = tab(report, "03_2022-05-16/r01", "Currency");
        assertTrue(texts(first.findings()).contains("stated as removed, but still listed: BBB"), first.toString());
        assertEquals("01_2021-05-17", first.genericodeBaseline());
        assertEquals(2, first.genericodeSpan());

        var corrected = tab(report, "03_2022-05-16/r02", "Currency");
        assertEquals(IndexCheck.Verdict.OK, corrected.verdict(), corrected.findings().toString());
        assertEquals(Set.of("CCC", "DDD"), corrected.genericode().added(), "CCC was claimed by release 02's Index");

        var dates = report.revisions().get(2).findings().stream().map(IndexCheck.Finding::text).toList();
        assertTrue(dates.contains("the Index states the effective date 2022-05-15, the release is filed under 2022-05-16"),
                dates.toString());
    }

    private static List<IndexCheck.Finding> check(String flag, String remark, ActualChanges changes, Set<String> before,
            Set<String> after) {
        var known = new java.util.HashSet<>(before);
        known.addAll(after);
        var findings = new ArrayList<IndexCheck.Finding>();
        IndexCheck.checkSheet(ChangeClaims.parse(flag, remark, known),
                new ActualChanges(changes.added(), changes.removed(), changes.renamed(), Set.of(), Set.of(), before,
                        after), findings);
        return findings;
    }

    private static ActualChanges changes(Set<String> added, Set<String> removed, Set<String> renamed) {
        return new ActualChanges(added, removed, renamed, Set.of(), Set.of(), Set.of(), Set.of());
    }

    private static List<String> texts(List<IndexCheck.Finding> findings) {
        return findings.stream().filter(finding -> finding.severity() == IndexCheck.Severity.MISMATCH)
                .map(IndexCheck.Finding::text).toList();
    }

    private static IndexCheck.TabCheck tab(IndexCheck.Report report, String revision, String tab) {
        return report.revisions().stream().filter(candidate -> candidate.revision().equals(revision)).findFirst()
                .orElseThrow().tabs().stream().filter(candidate -> candidate.tab().equals(tab)).findFirst()
                .orElseThrow();
    }

    /** One revision with a Currency and a Country sheet, and Currency as Genericode when {@code genericode} is set. */
    private void release(String release, String revision, String effective, String flag, String remark,
            String currencies, String genericode, String countries) throws IOException {
        Path root = temp.resolve(release).resolve(revision);
        write(root.resolve("extracted/xlsx/Index.csv"), "\"Publication date\",\"1/1/21\"\n\"Effective date\",\""
                + effective + "\"\n" + HEADER + "\"ISO 4217\",\"Currency\",\"\",\"Full list\",\"" + flag + "\",\""
                + remark + "\",\"BT-5, BT-6\"\n\"ISO 3166-1\",\"Country\",\"\",\"Extended\",\"No\",\"\","
                + "\"BT-40, BT-55, BT-69, BT-80, BT-159\"\n");
        write(root.resolve("normalized/xlsx/Currency.csv"), csv("\"Currency\",\"Alphabetic Code\"", currencies, true));
        write(root.resolve("normalized/xlsx/Country.csv"), csv("\"English short name\",\"Alpha-2 code\"", countries,
                true));
        if (genericode != null) {
            var rows = new StringBuilder();
            for (String code : genericode.split(" ")) {
                rows.append("<Row><Value ColumnRef=\"Code\"><SimpleValue>").append(code)
                        .append("</SimpleValue></Value><Value ColumnRef=\"Name\"><SimpleValue>Name of ").append(code)
                        .append("</SimpleValue></Value></Row>\n");
            }
            write(root.resolve("normalized/gc/Currency.gc"), """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <gc:CodeList xmlns:gc="http://docs.oasis-open.org/codelist/ns/genericode/1.0/" xmlns="">
                      <Identification><ShortName>Currency</ShortName><Version>1</Version>
                        <CanonicalUri>urn:test</CanonicalUri><CanonicalVersionUri>urn:test:1</CanonicalVersionUri>
                      </Identification>
                      <ColumnSet>
                        <Column Id="Code" Use="required"><ShortName>Code</ShortName><Data Type="string" /></Column>
                        <Column Id="Name" Use="optional"><ShortName>Name</ShortName><Data Type="string" /></Column>
                        <Key Id="CodeKey"><ShortName>Code</ShortName><ColumnRef Ref="Code" /></Key>
                      </ColumnSet>
                      <SimpleCodeList>
                    %s  </SimpleCodeList>
                    </gc:CodeList>
                    """.formatted(rows));
        }
    }

    /** A sheet whose name column comes first and code column second, as Currency and Country do. */
    private static String csv(String header, String codes, boolean nameFirst) {
        var text = new StringBuilder(header).append('\n');
        for (String code : codes.split(" ")) {
            text.append("\"Name of ").append(code).append("\",\"").append(code).append("\"\n");
        }
        return text.toString();
    }

    private static void write(Path file, String contents) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents, StandardCharsets.UTF_8);
    }
}
