package org.standict.codelist.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.standict.codelist.compare.CodeListReader;
import org.standict.codelist.normalize.GenericodeNormalizer;
import org.standict.codelist.statistics.Canonical;
import org.standict.codelist.validator.CodeListReleases;

/**
 * Checks what each release's Index sheet says changed against what changed in its code list sheets and its
 * Genericode files, and checks its business terms against EN 16931-1:2017.
 *
 * <p>Every revision is checked on its own, against the release before it. A correction such as release 17's second
 * revision exists because the first one's Index claimed a change its sheet did not make; checking only the corrected
 * revision would hide exactly that.
 *
 * <p>The Genericode files were first published in 2021 and not for every release since. A Genericode file is
 * therefore compared with the latest earlier release that has one, and when releases without Genericode lie between
 * the two, with everything those releases claimed as well.
 */
public final class IndexCheck {
    public enum Severity { MISMATCH, INFO }

    public enum Verdict { OK, INFO, MISMATCH }

    /**
     * One observation.
     *
     * @param component what it is about: {@code sheet}, {@code genericode}, {@code index} or {@code structure}
     */
    public record Finding(Severity severity, String component, String message, List<String> codes) {
        public String text() {
            return message + (codes.isEmpty() ? "" : ": " + String.join(" ", codes));
        }
    }

    /**
     * One row of one Index sheet, checked.
     *
     * @param sheet {@code null} for the first release, which has nothing to be compared with
     * @param genericode {@code null} when this revision or every earlier release lacks the Genericode file
     * @param genericodeBaseline the release the Genericode file was compared with, or empty
     * @param genericodeSpan how many releases the Genericode comparison spans; 1 for the previous release
     * @param listed the sheet (before) against the Genericode file (after) of this revision: {@code removed} are the
     *     codes only the sheet lists, {@code added} those only Genericode lists; {@code null} without Genericode
     */
    public record TabCheck(String tab, ChangeClaims claims, ActualChanges sheet, ActualChanges genericode,
            String genericodeBaseline, int genericodeSpan, List<Finding> findings, ActualChanges listed) {
        public Verdict verdict() {
            return IndexCheck.verdict(findings);
        }
    }

    /** One revision of one release, with its Index sheet checked row by row. */
    public record RevisionCheck(String revision, LocalDate directoryDate, String previous, IndexSheet index,
            List<Finding> findings, List<TabCheck> tabs, List<BusinessTerms.Check> terms) {
        public Verdict verdict() {
            var all = new ArrayList<>(findings);
            tabs.forEach(tab -> all.addAll(tab.findings()));
            return IndexCheck.verdict(all);
        }
    }

    public record Report(List<RevisionCheck> revisions) {}

    /** Tabs that are defined by EN 16931 itself and have never been published as Genericode. */
    private static final Set<String> NO_GENERICODE_EXPECTED = Set.of("VAT ID", "FISCAL ID", "VAT CAT", "Time");

    private final BusinessTerms businessTerms;
    private final Canonical.Catalog catalog;
    private final Map<Path, CodeListReader.CodeList> cache = new HashMap<>();

    public IndexCheck() throws IOException {
        this(BusinessTerms.load());
    }

    IndexCheck(BusinessTerms businessTerms) throws IOException {
        this.businessTerms = businessTerms;
        this.catalog = Canonical.catalog();
    }

    public Report check(CodeListReleases codeLists) throws IOException {
        var revisions = new ArrayList<RevisionCheck>();
        var releases = codeLists.releases();
        // The claims of the revision in force of every release checked so far, for Genericode comparisons that span
        // releases without Genericode.
        var claimsByRelease = new TreeMap<LocalDate, Map<String, ChangeClaims>>();
        for (int r = 0; r < releases.size(); r++) {
            CodeListReleases.Release release = releases.get(r);
            CodeListReleases.Release previous = r == 0 ? null : releases.get(r - 1);
            CodeListReleases.Release genericodeBaseline = null;
            for (int earlier = r - 1; earlier >= 0 && genericodeBaseline == null; earlier--) {
                if (releases.get(earlier).genericode() != null) {
                    genericodeBaseline = releases.get(earlier);
                }
            }
            for (CodeListReleases.Revision revision : release.revisions()) {
                RevisionCheck checked = check(revision, previous, genericodeBaseline,
                        genericodeBaseline == null ? 0 : r - releases.indexOf(genericodeBaseline), claimsByRelease);
                if (checked == null) {
                    continue;
                }
                revisions.add(checked);
                if (revision == release.revisions().get(release.revisions().size() - 1)) {
                    var claims = new HashMap<String, ChangeClaims>();
                    checked.tabs().forEach(tab -> claims.put(tab.tab(), tab.claims()));
                    claimsByRelease.put(release.effectiveDate(), claims);
                }
            }
        }
        return new Report(List.copyOf(revisions));
    }

    private RevisionCheck check(CodeListReleases.Revision revision, CodeListReleases.Release previous,
            CodeListReleases.Release genericodeBaseline, int span,
            java.util.NavigableMap<LocalDate, Map<String, ChangeClaims>> claimsByRelease) throws IOException {
        Path extracted = revision.stage("extracted", "xlsx");
        if (extracted == null || !Files.isRegularFile(extracted.resolve("Index.csv"))) {
            return null; // A revision that republished only Genericode has no Index to check.
        }
        IndexSheet index = IndexSheet.read(extracted.resolve("Index.csv")).withDatesFrom(extracted.resolve("Main.csv"));
        Path sheets = revision.stage("normalized", "xlsx");
        Path genericode = revision.stage("normalized", "gc");
        CodeListReleases.Revision before = previous == null ? null : last(previous.revisions());
        Path sheetsBefore = before == null ? null : before.stage("normalized", "xlsx");
        IndexSheet indexBefore = before == null || before.stage("extracted", "xlsx") == null
                || !Files.isRegularFile(before.stage("extracted", "xlsx").resolve("Index.csv")) ? null
                : IndexSheet.read(before.stage("extracted", "xlsx").resolve("Index.csv"));

        var findings = new ArrayList<Finding>();
        checkDate(index, revision.effectiveDate(), findings);
        checkStructure(index, sheets, genericode, findings);

        var tabs = new ArrayList<TabCheck>();
        for (IndexSheet.Entry entry : index.entries()) {
            Path sheet = sheets == null ? null : sheets.resolve(entry.tab() + ".csv");
            if (!ActualChanges.exists(sheet)) {
                continue; // Reported by the structure check.
            }
            CodeListReader.CodeList now = read(sheet, entry.tab(), false);
            Path sheetBefore = sheetsBefore == null ? null : sheetsBefore.resolve(entry.tab() + ".csv");
            CodeListReader.CodeList then = ActualChanges.exists(sheetBefore) ? read(sheetBefore, entry.tab(), false)
                    : ActualChanges.none(entry.tab());
            ActualChanges sheetChanges = before == null ? null : ActualChanges.between(then, now);

            Path gcNow = genericode == null ? null : genericode.resolve(entry.tab() + ".gc");
            Path gcThen = genericodeBaseline == null ? null
                    : genericodeBaseline.genericode().resolve(entry.tab() + ".gc");
            ActualChanges gcChanges = null;
            if (ActualChanges.exists(gcNow) && ActualChanges.exists(gcThen)) {
                gcChanges = ActualChanges.between(read(gcThen, entry.tab(), true), read(gcNow, entry.tab(), true));
            }

            var known = new LinkedHashSet<String>();
            known.addAll(now.rows().keySet());
            known.addAll(then.rows().keySet());
            if (gcChanges != null) {
                known.addAll(gcChanges.before());
                known.addAll(gcChanges.after());
            }
            ChangeClaims claims = ChangeClaims.parse(entry.changes(), entry.remark(), known);
            var tabFindings = new ArrayList<Finding>();
            if (sheetChanges == null) {
                tabFindings.add(new Finding(Severity.INFO, "sheet",
                        "first release in the tree, so its changes cannot be compared", List.of()));
            } else if (!ActualChanges.exists(sheetBefore)) {
                // FISCAL ID first appeared in 2025, split off VAT ID: all its codes are new, whatever the flag says.
                tabFindings.add(new Finding(Severity.INFO, "sheet", "first release with this sheet, "
                        + sheetChanges.after().size() + " codes", List.of()));
                if (!claims.unresolved().isEmpty()) {
                    tabFindings.add(new Finding(Severity.MISMATCH, "index",
                            "the remark names codes the list does not contain, before or after", claims.unresolved()));
                }
            } else {
                checkSheet(claims, sheetChanges, tabFindings);
            }
            ActualChanges listed = null;
            if (ActualChanges.exists(gcNow)) {
                listed = ActualChanges.between(now, read(gcNow, entry.tab(), true));
                checkListed(listed, tabFindings);
                if (gcChanges == null) {
                    tabFindings.add(new Finding(Severity.INFO, "genericode",
                            "first Genericode publication of this list", List.of()));
                } else {
                    ActualChanges sheetOverSpan = sheetChanges;
                    var claimedOverSpan = new ArrayList<ChangeClaims>(List.of(claims));
                    if (span > 1) {
                        Path baselineSheet = last(genericodeBaseline.revisions()).stage("normalized", "xlsx");
                        Path file = baselineSheet == null ? null : baselineSheet.resolve(entry.tab() + ".csv");
                        sheetOverSpan = ActualChanges.between(ActualChanges.exists(file)
                                ? read(file, entry.tab(), false) : ActualChanges.none(entry.tab()), now);
                        claimsByRelease.subMap(genericodeBaseline.effectiveDate(), false, revision.effectiveDate(),
                                false).values().forEach(earlier -> {
                                    if (earlier.containsKey(entry.tab())) {
                                        claimedOverSpan.add(earlier.get(entry.tab()));
                                    }
                                });
                    }
                    checkGenericode(gcChanges, sheetOverSpan, claimedOverSpan, span, genericodeBaseline.directory(),
                            tabFindings);
                }
            }
            tabs.add(new TabCheck(entry.tab(), claims, sheetChanges, gcChanges,
                    gcChanges == null ? "" : genericodeBaseline.directory(), gcChanges == null ? 0 : span,
                    List.copyOf(tabFindings), listed));
        }

        var published = new TreeMap<String, Map<String, List<String>>>();
        merge(published, BusinessTerms.search(revision.stage("extracted", "gc"), ".gc", List.of()));
        merge(published, BusinessTerms.search(extracted, ".csv", List.of("Index.csv", "Main.csv")));
        List<BusinessTerms.Check> terms = businessTerms.check(index, indexBefore, published);
        return new RevisionCheck(revision.name(), revision.effectiveDate(), before == null ? "" : before.name(),
                index, List.copyOf(findings), List.copyOf(tabs), terms);
    }

    private static void checkDate(IndexSheet index, LocalDate directory, List<Finding> findings) {
        if (index.effectiveDate() != null && !index.effectiveDate().equals(directory)) {
            findings.add(new Finding(Severity.MISMATCH, "index", "the Index states the effective date "
                    + index.effectiveDate() + ", the release is filed under " + directory, List.of()));
        } else if (index.effectiveDate() == null && !index.effectiveDateText().isEmpty()) {
            findings.add(new Finding(Severity.INFO, "index",
                    "unreadable effective date \"" + index.effectiveDateText() + "\"", List.of()));
        } else if (index.effectiveDate() == null) {
            findings.add(new Finding(Severity.INFO, "index", index.publicationDateText().isEmpty()
                    ? "the workbook states no effective date"
                    : "the workbook states no effective date, only the publication date "
                            + (index.publicationDate() == null ? index.publicationDateText() : index.publicationDate()),
                    List.of()));
        }
    }

    private void checkStructure(IndexSheet index, Path sheets, Path genericode, List<Finding> findings)
            throws IOException {
        var tabs = new TreeSet<String>();
        index.entries().forEach(entry -> tabs.add(entry.tab()));
        var present = new TreeSet<String>();
        if (sheets != null) {
            try (var files = Files.list(sheets)) {
                files.map(file -> file.getFileName().toString()).filter(name -> name.endsWith(".csv"))
                        .map(name -> name.substring(0, name.length() - 4)).filter(catalog::isCodeList)
                        .forEach(present::add);
            }
        }
        var unlisted = new TreeSet<>(present);
        unlisted.removeAll(tabs);
        if (!unlisted.isEmpty()) {
            findings.add(new Finding(Severity.MISMATCH, "structure", "sheets the Index does not list",
                    List.copyOf(unlisted)));
        }
        var missing = new TreeSet<>(tabs);
        missing.removeAll(present);
        if (!missing.isEmpty()) {
            findings.add(new Finding(Severity.MISMATCH, "structure", "tabs the Index lists without a sheet",
                    List.copyOf(missing)));
        }
        if (genericode != null) {
            var withoutGenericode = new TreeSet<String>();
            var unexpected = new TreeSet<String>();
            for (String tab : tabs) {
                if (!Files.isRegularFile(genericode.resolve(tab + ".gc"))) {
                    (NO_GENERICODE_EXPECTED.contains(tab) ? withoutGenericode : unexpected).add(tab);
                }
            }
            if (!withoutGenericode.isEmpty()) {
                findings.add(new Finding(Severity.INFO, "structure",
                        "tabs defined by EN 16931 itself, never published as Genericode", List.copyOf(withoutGenericode)));
            }
            if (!unexpected.isEmpty()) {
                findings.add(new Finding(Severity.MISMATCH, "structure", "tabs without a Genericode file",
                        List.copyOf(unexpected)));
            }
        }
    }

    /** The Index row against its sheet: the flag, then every code the remark names, then every code it does not. */
    static void checkSheet(ChangeClaims claims, ActualChanges sheet, List<Finding> findings) {
        var added = new TreeSet<String>(GenericodeNormalizer.CODE_ORDER);
        added.addAll(sheet.added());
        var removed = new TreeSet<String>(GenericodeNormalizer.CODE_ORDER);
        removed.addAll(sheet.removed());
        var caseOnly = new TreeSet<String>(GenericodeNormalizer.CODE_ORDER);
        for (String code : sheet.added()) {
            sheet.removed().stream().filter(old -> old.equalsIgnoreCase(code)).forEach(old -> {
                caseOnly.add(code);
                added.remove(code);
                removed.remove(old);
            });
        }
        if (!caseOnly.isEmpty()) {
            findings.add(new Finding(claims.caseChange() ? Severity.INFO : Severity.MISMATCH, "sheet",
                    claims.caseChange() ? caseOnly.size() + " codes changed only their case, as stated"
                            : "codes that changed only their case, not stated", List.copyOf(caseOnly)));
        }
        // ICD 01'00 became 0100 in 2019, which the Index stated as "structure corrected": one code, respelled.
        var respelled = new TreeSet<String>(GenericodeNormalizer.CODE_ORDER);
        var respelledStated = new TreeSet<String>(GenericodeNormalizer.CODE_ORDER);
        for (String code : List.copyOf(added)) {
            List.copyOf(removed).stream().filter(old -> spelling(old).equals(spelling(code))).findFirst().ifPresent(old -> {
                (claims.reworded().contains(code) || claims.reworded().contains(old) ? respelledStated : respelled)
                        .add(code);
                added.remove(code);
                removed.remove(old);
            });
        }
        if (!respelledStated.isEmpty()) {
            findings.add(new Finding(Severity.INFO, "sheet", "spelling corrected, as stated",
                    List.copyOf(respelledStated)));
        }
        report(findings, "sheet", "spelling corrected, not stated", respelled);
        boolean changed = !added.isEmpty() || !removed.isEmpty() || !sheet.renamed().isEmpty() || !caseOnly.isEmpty()
                || !respelled.isEmpty() || !respelledStated.isEmpty();
        switch (claims.flag()) {
            case YES -> {
                if (!changed) {
                    findings.add(new Finding(Severity.MISMATCH, "sheet", sheet.otherColumns().isEmpty()
                            ? "Changes = " + claims.flagText() + ", but no code was added, removed or renamed"
                            : "Changes = " + claims.flagText() + ", but no code was added, removed or renamed; "
                                    + "other columns changed for", List.copyOf(sheet.otherColumns())));
                }
            }
            case NO, FIXED, EMPTY -> {
                if (changed) {
                    String flag = claims.flag() == ChangeClaims.Flag.EMPTY ? "empty" : claims.flagText();
                    if (!added.isEmpty()) {
                        findings.add(new Finding(Severity.MISMATCH, "sheet", "Changes = " + flag + ", but added",
                                List.copyOf(added)));
                    }
                    if (!removed.isEmpty()) {
                        findings.add(new Finding(Severity.MISMATCH, "sheet", "Changes = " + flag + ", but removed",
                                List.copyOf(removed)));
                    }
                    if (!sheet.renamed().isEmpty()) {
                        findings.add(new Finding(Severity.MISMATCH, "sheet", "Changes = " + flag + ", but renamed",
                                List.copyOf(sheet.renamed())));
                    }
                }
                if (!sheet.otherColumns().isEmpty()) {
                    findings.add(new Finding(Severity.INFO, "sheet", "other columns changed for",
                            List.copyOf(sheet.otherColumns())));
                }
            }
            case OTHER -> findings.add(new Finding(Severity.INFO, "index",
                    "unknown Changes value \"" + claims.flagText() + "\"", List.of()));
        }

        report(findings, "sheet", "stated as added, but already listed before",
                claims.added().stream().filter(code -> !added.contains(code) && sheet.before().contains(code)
                        && sheet.after().contains(code)).toList());
        report(findings, "sheet", "stated as added, but not listed",
                claims.added().stream().filter(code -> !sheet.after().contains(code)).toList());
        report(findings, "sheet", "stated as removed, but still listed",
                claims.removed().stream().filter(code -> sheet.after().contains(code)).toList());
        report(findings, "sheet", "stated as removed, but not listed before either",
                claims.removed().stream().filter(code -> !sheet.after().contains(code)
                        && !sheet.before().contains(code)).toList());
        report(findings, "sheet", "stated as renamed, but its name and columns are unchanged",
                claims.reworded().stream().filter(code -> sheet.after().contains(code) && sheet.before().contains(code)
                        && !sheet.renamed().contains(code) && !sheet.otherColumns().contains(code)
                        && !sheet.whitespaceOnly().contains(code)).toList());
        var whitespaceOnly = claims.reworded().stream().filter(sheet.whitespaceOnly()::contains).toList();
        if (!whitespaceOnly.isEmpty()) {
            findings.add(new Finding(Severity.INFO, "sheet", "stated as renamed; only whitespace changed",
                    whitespaceOnly));
        }
        report(findings, "sheet", "stated as deprecated, but not listed",
                claims.deprecated().stream().filter(code -> !sheet.after().contains(code)
                        && !sheet.removed().contains(code)).toList());
        var deprecatedRemoved = claims.deprecated().stream().filter(sheet.removed()::contains).toList();
        if (!deprecatedRemoved.isEmpty()) {
            findings.add(new Finding(Severity.INFO, "sheet", "stated as deprecated, and removed", deprecatedRemoved));
        }
        var deprecatedListed = claims.deprecated().stream().filter(sheet.after()::contains).toList();
        if (!deprecatedListed.isEmpty()) {
            findings.add(new Finding(Severity.INFO, "sheet", "stated as deprecated, still listed", deprecatedListed));
        }
        if (claims.newList() && !sheet.before().isEmpty()) {
            findings.add(new Finding(Severity.MISMATCH, "sheet",
                    "stated as a new list, but the previous release has it", List.of()));
        }
        if (!claims.unresolved().isEmpty()) {
            findings.add(new Finding(Severity.MISMATCH, "index",
                    "the remark names codes the list does not contain, before or after", claims.unresolved()));
        }

        if (claims.flag() != ChangeClaims.Flag.YES) {
            return; // A "No" with changes has already been reported code by code above.
        }
        var unstatedAdded = new ArrayList<>(added.stream().filter(code -> !claims.added().contains(code)).toList());
        if (!unstatedAdded.isEmpty()) {
            int counted = claims.counts().stream().mapToInt(Integer::intValue).sum();
            if (claims.newList() && sheet.before().isEmpty()) {
                findings.add(new Finding(Severity.INFO, "sheet", "a new list with " + unstatedAdded.size() + " codes",
                        List.of()));
            } else if (counted > 0 && counted == unstatedAdded.size()) {
                findings.add(new Finding(Severity.INFO, "sheet",
                        "added as many codes as the remark counts (" + counted + ")", unstatedAdded));
            } else if (counted > 0) {
                findings.add(new Finding(Severity.MISMATCH, "sheet", "the remark counts " + counted
                        + " added codes it does not name; the sheet adds " + unstatedAdded.size(), unstatedAdded));
            } else {
                findings.add(new Finding(Severity.MISMATCH, "sheet", "added, not stated", unstatedAdded));
            }
        }
        report(findings, "sheet", "removed, not stated", removed.stream()
                .filter(code -> !claims.removed().contains(code) && !claims.deprecated().contains(code)).toList());
        report(findings, "sheet", "renamed, not stated",
                sheet.renamed().stream().filter(code -> !claims.reworded().contains(code)).toList());
    }

    /** The codes of the sheet against those of the Genericode file of the same revision. */
    static void checkListed(ActualChanges listed, List<Finding> findings) {
        report(findings, "genericode", "listed in the sheet, not in Genericode", listed.removed());
        report(findings, "genericode", "listed in Genericode, not in the sheet", listed.added());
    }

    /**
     * The Genericode file against the sheet over the same releases, and against what the Index sheets of those
     * releases claimed.
     */
    static void checkGenericode(ActualChanges genericode, ActualChanges sheet, List<ChangeClaims> claims, int span,
            String baseline, List<Finding> findings) {
        String since = span > 1 ? " (since " + baseline + ", " + span + " releases)" : "";
        if (sheet != null) {
            report(findings, "genericode", "Genericode adds, the sheet does not" + since,
                    difference(genericode.added(), sheet.added()));
            report(findings, "genericode", "the sheet adds, Genericode does not" + since,
                    difference(sheet.added(), genericode.added()));
            report(findings, "genericode", "Genericode removes, the sheet does not" + since,
                    difference(genericode.removed(), sheet.removed()));
            report(findings, "genericode", "the sheet removes, Genericode does not" + since,
                    difference(sheet.removed(), genericode.removed()));
            report(findings, "genericode", "Genericode renames, the sheet does not" + since,
                    difference(genericode.renamed(), sheet.renamed()));
            report(findings, "genericode", "the sheet renames, Genericode does not" + since,
                    difference(sheet.renamed(), genericode.renamed()));
        }
        var claimedAdded = new TreeSet<String>(GenericodeNormalizer.CODE_ORDER);
        var claimedRemoved = new TreeSet<String>(GenericodeNormalizer.CODE_ORDER);
        claims.forEach(claim -> {
            claimedAdded.addAll(claim.added());
            claimedRemoved.addAll(claim.removed());
        });
        report(findings, "genericode", "stated as added, but Genericode does not list it" + since,
                claimedAdded.stream().filter(code -> !genericode.after().contains(code)).toList());
        report(findings, "genericode", "stated as removed, but Genericode still lists it" + since,
                claimedRemoved.stream().filter(code -> genericode.after().contains(code)).toList());
    }

    /** A code without its punctuation and case: {@code 01'00} and {@code 0100} are spelled alike. */
    private static String spelling(String code) {
        return code.replaceAll("[^\\p{Alnum}]", "").toUpperCase(java.util.Locale.ROOT);
    }

    private static List<String> difference(Set<String> left, Set<String> right) {
        return left.stream().filter(code -> !right.contains(code)).toList();
    }

    private static void report(List<Finding> findings, String component, String message, Collection<String> codes) {
        if (!codes.isEmpty()) {
            findings.add(new Finding(Severity.MISMATCH, component, message, List.copyOf(codes)));
        }
    }

    static Verdict verdict(List<Finding> findings) {
        if (findings.stream().anyMatch(finding -> finding.severity() == Severity.MISMATCH)) {
            return Verdict.MISMATCH;
        }
        return findings.isEmpty() ? Verdict.OK : Verdict.INFO;
    }

    private CodeListReader.CodeList read(Path file, String tab, boolean genericode) throws IOException {
        var hit = cache.get(file);
        if (hit == null) {
            hit = genericode ? ActualChanges.genericode(file) : ActualChanges.spreadsheet(file, tab);
            cache.put(file, hit);
        }
        return hit;
    }

    private static void merge(Map<String, Map<String, List<String>>> into, Map<String, Map<String, List<String>>> from) {
        from.forEach((tab, files) -> into.computeIfAbsent(tab, key -> new TreeMap<>()).putAll(files));
    }

    private static <T> T last(List<T> list) {
        return list.get(list.size() - 1);
    }
}
