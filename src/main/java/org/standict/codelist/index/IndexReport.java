package org.standict.codelist.index;

import static org.standict.codelist.validator.ValidatorReport.escape;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.standict.codelist.index.IndexCheck.Finding;
import org.standict.codelist.index.IndexCheck.RevisionCheck;
import org.standict.codelist.index.IndexCheck.TabCheck;
import org.standict.codelist.validator.Successions;
import org.standict.codelist.validator.ValidatorReport;

/**
 * Renders an {@link IndexCheck.Report} as CSV rows and as the declaration blocks of each code-list release in the
 * comparison report.
 *
 * <ul>
 * <li>{@code index-claims.csv}: one row per revision and Index row, with what was stated, what the sheet and the
 * Genericode file actually changed, and every finding.</li>
 * <li>{@code business-terms.csv}: one row per revision and Index row, with its business terms against EN 16931-1:2017
 * and against the previous release.</li>
 * <li>{@code index-releases.csv}: one row per revision, with the dates its Index states and its structural
 * findings.</li>
 * </ul>
 */
public final class IndexReport {
    private final IndexCheck.Report report;
    private final Successions successions;

    public IndexReport(IndexCheck.Report report, Successions successions) {
        this.report = report;
        this.successions = successions;
    }

    public List<List<String>> claimsCsv() {
        var rows = new ArrayList<List<String>>();
        rows.add(List.of("release", "previous release", "tab", "changes", "remark on updates", "stated added",
                "stated removed", "stated renamed", "stated deprecated", "stated counts", "unresolved in remark",
                "sheet added", "sheet removed", "sheet renamed", "sheet other columns changed",
                "genericode compared with", "genericode added", "genericode removed", "genericode renamed",
                "only in sheet", "only in genericode", "names differ in sheet and genericode", "verdict", "findings"));
        for (RevisionCheck revision : report.revisions()) {
            for (TabCheck tab : revision.tabs()) {
                ChangeClaims claims = tab.claims();
                var row = new ArrayList<>(List.of(revision.revision(), revision.previous(), tab.tab(),
                        claims.flagText(), claims.remark(), join(claims.added()), join(claims.removed()),
                        join(claims.reworded()), join(claims.deprecated()),
                        claims.counts().stream().map(String::valueOf).collect(Collectors.joining(" ")),
                        join(claims.unresolved())));
                row.addAll(tab.sheet() == null ? List.of("", "", "", "") : List.of(join(tab.sheet().added()),
                        join(tab.sheet().removed()), join(tab.sheet().renamed()), join(tab.sheet().otherColumns())));
                row.addAll(tab.genericode() == null ? List.of("", "", "", "") : List.of(tab.genericodeBaseline(),
                        join(tab.genericode().added()), join(tab.genericode().removed()),
                        join(tab.genericode().renamed())));
                row.addAll(tab.listed() == null ? List.of("", "", "") : List.of(join(tab.listed().removed()),
                        join(tab.listed().added()), join(tab.listed().renamed())));
                row.add(tab.verdict().name());
                row.add(findings(tab.findings()));
                rows.add(row);
            }
        }
        return rows;
    }

    public List<List<String>> businessTermsCsv() {
        var rows = new ArrayList<List<String>>();
        rows.add(List.of("release", "tab", "index business terms", "EN 16931-1:2017", "only in index",
                "only in EN 16931-1:2017", "added since previous release", "removed since previous release",
                "also listed under", "in genericode or sheet", "EN 16931-1:2017 evidence"));
        for (RevisionCheck revision : report.revisions()) {
            for (BusinessTerms.Check check : revision.terms()) {
                rows.add(List.of(revision.revision(), check.tab(), join(check.index()),
                        check.reference() == null ? "" : join(check.reference().terms()), join(check.onlyInIndex()),
                        join(check.onlyIn2017()), join(check.addedSincePrevious()),
                        join(check.removedSincePrevious()), shared(check.sharedWith()), published(check.published()),
                        check.reference() == null ? "no row in business-terms-2017.csv" : check.reference().evidence()));
            }
        }
        return rows;
    }

    public List<List<String>> releasesCsv() {
        var rows = new ArrayList<List<String>>();
        rows.add(List.of("release", "previous release", "filed under", "index publication date",
                "index effective date", "verdict", "tabs", "tabs mismatching", "findings", "notes"));
        for (RevisionCheck revision : report.revisions()) {
            rows.add(List.of(revision.revision(), revision.previous(), revision.directoryDate().toString(),
                    revision.index().publicationDateText(), revision.index().effectiveDateText(),
                    revision.verdict().name(), String.valueOf(revision.tabs().size()),
                    String.valueOf(mismatching(revision)), findings(revision.findings()),
                    String.join(" | ", revision.index().notes())));
        }
        return rows;
    }

    /** The revisions of the code-list release filed under {@code date}, oldest first. */
    public List<RevisionCheck> revisionsOn(LocalDate date) {
        return report.revisions().stream().filter(revision -> revision.directoryDate().equals(date)).toList();
    }

    /** Whether any Index row of the revision has a Genericode file to compare its sheet with. */
    public static boolean hasGenericode(RevisionCheck revision) {
        return revision.tabs().stream().anyMatch(tab -> tab.listed() != null);
    }

    /** The Index rows whose sheet and Genericode file list different codes. */
    public static List<TabCheck> listedDiffering(RevisionCheck revision) {
        return revision.tabs().stream().filter(tab -> tab.listed() != null
                && (!tab.listed().added().isEmpty() || !tab.listed().removed().isEmpty())).toList();
    }

    /**
     * The declaration of one code-list release, in three blocks that open in place: the spreadsheet against the
     * Genericode files, the Index sheet's change notes against what actually changed, and the Index's business terms
     * against EN 16931-1:2017. Blocks that find something open with the page when {@code open} is set.
     *
     * @param id the prefix of the blocks' anchors, such as {@code d2026-05-15}
     */
    public String declarationHtml(String id, List<RevisionCheck> revisions, boolean open) {
        RevisionCheck current = revisions.get(revisions.size() - 1);
        return sheetAgainstGenericode(id, revisions, current, open) + indexNotes(id, revisions, current, open)
                + businessTerms(id, current, open);
    }

    private String sheetAgainstGenericode(String id, List<RevisionCheck> revisions, RevisionCheck current,
            boolean open) {
        if (!hasGenericode(current)) {
            return "<p class=\"block-none\"><span class=\"block-title\">Spreadsheet ⇄ Genericode</span> "
                    + "<span class=\"gist\">no Genericode published; the spreadsheet is the only code list</span></p>\n";
        }
        int compared = (int) current.tabs().stream().filter(tab -> tab.listed() != null).count();
        int differing = listedDiffering(current).size();
        var renamedNames = current.tabs().stream().filter(tab -> tab.listed() != null
                && !tab.listed().renamed().isEmpty()).toList();
        var body = new StringBuilder();
        var rows = new StringBuilder();
        for (RevisionCheck revision : revisions.reversed()) {
            for (TabCheck tab : listedDiffering(revision)) {
                rows.append("<tr><th scope=\"row\">").append(escape(tab.tab()))
                        .append(revisions.size() > 1 ? " <span class=\"quiet\">" + escape(revision.revision()) + "</span>"
                                : "")
                        .append("</th>").append(terms(List.copyOf(tab.listed().removed()), "removed"))
                        .append(terms(List.copyOf(tab.listed().added()), "added")).append("</tr>\n");
            }
        }
        if (!rows.isEmpty()) {
            body.append("<div class=\"scroll\"><table><thead><tr><th scope=\"col\">Tab</th>")
                    .append("<th scope=\"col\">Only in the spreadsheet</th><th scope=\"col\">Only in Genericode</th>")
                    .append("</tr></thead><tbody>\n").append(rows).append("</tbody></table></div>\n");
        } else {
            body.append("<p>Every code is in both, under the same name.</p>\n");
        }
        if (!renamedNames.isEmpty()) {
            body.append("<p>Names that differ: ").append(renamedNames.stream().map(tab -> escape(tab.tab()) + " <code>"
                    + escape(abbreviate(List.copyOf(tab.listed().renamed()))) + "</code>")
                    .collect(Collectors.joining("; "))).append("</p>\n");
        }
        var without = current.tabs().stream().filter(tab -> tab.listed() == null).map(TabCheck::tab).toList();
        if (!without.isEmpty()) {
            body.append("<p class=\"quiet\">Not published as Genericode: ").append(escape(String.join(", ", without)))
                    .append(".</p>\n");
        }
        String chip = differing == 0 ? ValidatorReport.chip("ok", "same codes", null, null)
                : ValidatorReport.chip("bad", differing + " of " + compared, null, null);
        String gist = differing == 0 ? "all " + compared + " lists published both ways agree"
                : differing + " of " + compared + " lists contain other codes in the spreadsheet than in Genericode";
        return ValidatorReport.block(id + "-sheet-genericode", open && differing > 0, "Spreadsheet ⇄ Genericode", chip,
                gist, "sheet-genericode", body.toString());
    }

    private String indexNotes(String id, List<RevisionCheck> revisions, RevisionCheck current, boolean open) {
        int mismatching = mismatching(current);
        var body = new StringBuilder();
        for (RevisionCheck revision : revisions.reversed()) {
            if (revisions.size() > 1) {
                body.append("<h4>").append(escape(revision.revision())).append(revision == current
                        ? " <span class=\"quiet\">current</span>"
                        : " <span class=\"quiet\">replaced by " + escape(current.revision()) + ", " + mismatching(revision)
                                + " rows wrong</span>").append("</h4>\n");
            }
            body.append(revisionNotes(revision));
        }
        String chip = mismatching == 0 ? ValidatorReport.chip("ok", "all " + current.tabs().size() + " rows", null, null)
                : ValidatorReport.chip("bad", mismatching + " of " + current.tabs().size() + " rows", null, null);
        String gist = mismatching == 0 ? "every stated change happened, and every change was stated"
                : "rows whose stated change is not what the spreadsheet and Genericode show";
        boolean dates = current.findings().stream().anyMatch(f -> f.severity() == IndexCheck.Severity.MISMATCH);
        if (dates) {
            gist += "; " + current.findings().stream().filter(f -> f.severity() == IndexCheck.Severity.MISMATCH)
                    .map(Finding::text).collect(Collectors.joining("; "));
        }
        return ValidatorReport.block(id + "-index", open && (mismatching > 0 || dates), "Index change notes ⇄ actual changes",
                chip, gist, "index-notes", body.toString());
    }

    /**
     * One revision's Index rows that state a change, or whose list changed, with what the Index claims next to what
     * the spreadsheet and the Genericode file actually changed, and what does not match. Rows that disagree come first.
     */
    private String revisionNotes(RevisionCheck revision) {
        var html = new StringBuilder();
        html.append("<p class=\"quiet\">").append(revision.previous().isEmpty()
                ? "The first release in this comparison: there is nothing to compare its changes with."
                : "Compared with " + escape(revision.previous()) + ".").append("</p>\n");
        // The tabs never published as Genericode are named in the Spreadsheet ⇄ Genericode block.
        var revisionFindings = revision.findings().stream()
                .filter(finding -> !finding.message().startsWith("tabs defined by EN 16931 itself")).map(finding -> "<li class=\""
                + finding.severity().name().toLowerCase(Locale.ROOT) + "\">" + escape(finding.text()) + "</li>")
                .collect(Collectors.joining());
        if (!revisionFindings.isEmpty()) {
            html.append("<ul class=\"findings\">").append(revisionFindings).append("</ul>\n");
        }
        var shown = revision.tabs().stream().filter(IndexReport::worthShowing)
                .sorted(java.util.Comparator.comparing((TabCheck tab) -> tab.verdict() != IndexCheck.Verdict.MISMATCH))
                .toList();
        if (!shown.isEmpty()) {
            html.append("<div class=\"scroll\"><table class=\"claims\"><thead><tr>")
                    .append("<th scope=\"col\" rowspan=\"2\">Tab</th>")
                    .append("<th scope=\"col\" class=\"group\" colspan=\"2\">The Index sheet states</th>")
                    .append("<th scope=\"col\" class=\"group\" colspan=\"2\">What actually changed</th>")
                    .append("<th scope=\"col\" rowspan=\"2\">What does not match</th></tr>")
                    .append("<tr><th scope=\"col\">Changes</th><th scope=\"col\">Remark on updates</th>")
                    .append("<th scope=\"col\">Spreadsheet</th><th scope=\"col\">Genericode</th></tr>")
                    .append("</thead><tbody>\n");
            for (TabCheck tab : shown) {
                html.append("<tr class=\"").append(tab.verdict().name().toLowerCase(Locale.ROOT))
                        .append("\"><th scope=\"row\">").append(escape(tab.tab())).append("</th><td class=\"list\">")
                        .append(escape(tab.claims().flagText().isEmpty() ? "—" : tab.claims().flagText()))
                        .append("</td><td class=\"note\">")
                        .append(tab.claims().remark().isEmpty() ? "<span class=\"quiet\">—</span>"
                                : escape(tab.claims().remark()))
                        .append("</td>").append(actual(tab.tab(), tab.sheet(), "", 1, revision.previous().isEmpty()
                                ? "first release in this comparison" : "no sheet"))
                        .append(actual(tab.tab(), tab.genericode(), tab.genericodeBaseline(), tab.genericodeSpan(),
                                tab.findings().stream().anyMatch(f -> f.message().startsWith("first Genericode"))
                                        ? "first Genericode release of this list" : "no Genericode file"))
                        .append("<td class=\"note\">").append(findingsList(tab)).append("</td></tr>\n");
            }
            html.append("</tbody></table></div>\n");
        }
        int quiet = revision.tabs().size() - shown.size();
        if (quiet > 0) {
            html.append("<p class=\"quiet\">").append(shown.isEmpty() ? "All " + quiet + " rows state" : quiet
                    + (quiet == 1 ? " further row states" : " further rows state")).append(" no change, and none happened.</p>\n");
        }
        return html.toString();
    }

    private String businessTerms(String id, RevisionCheck current, boolean open) {
        var differing = current.terms().stream().filter(BusinessTerms.Check::differsFrom2017).toList();
        var changed = current.previous().isEmpty() ? List.<BusinessTerms.Check>of()
                : current.terms().stream().filter(BusinessTerms.Check::changedSincePrevious).toList();
        var body = new StringBuilder();
        var shown = current.terms().stream().filter(check -> differing.contains(check) || changed.contains(check))
                .toList();
        if (!shown.isEmpty()) {
            body.append("<div class=\"scroll\"><table><thead><tr><th scope=\"col\">Tab</th><th scope=\"col\">Index</th>")
                    .append("<th scope=\"col\">EN 16931-1:2017</th><th scope=\"col\">Only in the Index</th>")
                    .append("<th scope=\"col\">Only in 2017</th><th scope=\"col\">Changed since ")
                    .append(escape(current.previous().isEmpty() ? "—" : current.previous()))
                    .append("</th></tr></thead><tbody>\n");
            for (BusinessTerms.Check check : shown) {
                body.append("<tr><th scope=\"row\">").append(escape(check.tab())).append("</th><td class=\"note\">")
                        .append(escape(join(check.index()))).append("</td><td class=\"note\"")
                        .append(check.reference() == null ? "><em>no reference</em>"
                                : " title=\"" + escape(check.reference().evidence()) + "\">"
                                        + escape(join(check.reference().terms())))
                        .append("</td>").append(terms(check.onlyInIndex(), "added"))
                        .append(terms(check.onlyIn2017(), "removed")).append("<td class=\"note actual\">")
                        .append(check.changedSincePrevious() && !current.previous().isEmpty()
                                ? (check.addedSincePrevious().isEmpty() ? "" : "<div class=\"added\">+ <code>"
                                        + escape(join(check.addedSincePrevious())) + "</code></div>")
                                        + (check.removedSincePrevious().isEmpty() ? "" : "<div class=\"removed\">− <code>"
                                                + escape(join(check.removedSincePrevious())) + "</code></div>")
                                : "<span class=\"quiet\">unchanged</span>")
                        .append("</td></tr>\n");
            }
            body.append("</tbody></table></div>\n");
        } else {
            body.append("<p>Every row names exactly the business terms of EN 16931-1:2017.</p>\n");
        }
        var outside = current.terms().stream().filter(check -> !check.published().isEmpty())
                .map(check -> check.tab() + ": " + published(check.published())).toList();
        if (!outside.isEmpty()) {
            body.append("<p>Business terms named in the Genericode files or sheets: ")
                    .append(escape(String.join("; ", outside))).append("</p>\n");
        }
        String chip = differing.isEmpty() ? ValidatorReport.chip("ok", "as 2017", null, null)
                : ValidatorReport.chip("bad", differing.size() + " of " + current.terms().size() + " tabs", null, null);
        String gist = (differing.isEmpty() ? "every Index row names the business terms of EN 16931-1:2017"
                : "Index rows naming other business terms than EN 16931-1:2017")
                + (changed.isEmpty() ? "" : "; " + changed.size() + " changed since the previous release");
        return ValidatorReport.block(id + "-terms", false, "Business terms ⇄ EN 16931-1:2017", chip, gist,
                "business-terms", body.toString());
    }

    /** A row is shown when it claims something, when its list changed, or when anything about it does not match. */
    private static boolean worthShowing(TabCheck tab) {
        return tab.verdict() == IndexCheck.Verdict.MISMATCH || tab.claims().flag() == ChangeClaims.Flag.YES
                || !tab.claims().remark().isEmpty() || (tab.sheet() != null && tab.sheet().changesCodes())
                || (tab.genericode() != null && tab.genericode().changesCodes());
    }

    /**
     * What one component actually changed: renamed codes as {@code ANG → XCG}, then added, removed and renamed codes,
     * one kind per line.
     */
    private String actual(String tab, ActualChanges changes, String baseline, int span, String absent) {
        if (changes == null) {
            return "<td class=\"absent\">" + escape(absent) + "</td>";
        }
        var lines = new StringBuilder("<td class=\"note actual\">");
        if (!baseline.isEmpty() && span > 1) {
            lines.append("<div class=\"quiet\">since ").append(escape(baseline)).append(", ").append(span)
                    .append(" releases</div>");
        }
        if (!changes.changesCodes()) {
            lines.append("<span class=\"quiet\">no code added, removed or renamed</span>");
        }
        var pairs = successions.pairs(tab, changes.removed(), changes.added(), code -> null);
        lines.append(ValidatorReport.successionLines(pairs));
        var paired = Successions.codes(pairs);
        line(lines, "added", "+", changes.added().stream().filter(code -> !paired.contains(code)).toList());
        var removed = changes.removed().stream().filter(code -> !paired.contains(code)).toList();
        line(lines, "removed", "−", removed.stream().filter(code -> successions.successorsOf(tab, code).isEmpty())
                .toList());
        for (String code : removed) {
            for (Successions.Pair successor : successions.successorsOf(tab, code)) {
                lines.append("<div class=\"removed\">− <code>").append(escape(code)).append("</code> → <code>")
                        .append(escape(successor.newCode())).append("</code> <span class=\"quiet\">")
                        .append(escape(successor.when()))
                        .append(changes.after().contains(successor.newCode()) ? ", " + escape(successor.newCode())
                                + " already listed" : "").append("</span> ").append(ValidatorReport.wikipedia(successor)).append("</div>");
            }
        }
        line(lines, "reworded", "name changed", changes.renamed());
        if (!changes.otherColumns().isEmpty()) {
            lines.append("<div class=\"quiet\">other columns changed for ").append(changes.otherColumns().size())
                    .append(changes.otherColumns().size() == 1 ? " code" : " codes").append("</div>");
        }
        return lines.append("</td>").toString();
    }

    private static void line(StringBuilder lines, String kind, String label, Collection<String> codes) {
        if (!codes.isEmpty()) {
            lines.append("<div class=\"").append(kind).append("\">").append(label).append(" <code>")
                    .append(escape(abbreviate(List.copyOf(codes)))).append("</code></div>");
        }
    }

    private static String findingsList(TabCheck tab) {
        var shown = tab.findings().stream().filter(finding -> finding.severity() == IndexCheck.Severity.MISMATCH
                || !finding.message().startsWith("first ")).toList();
        if (shown.isEmpty()) {
            return "<span class=\"agree\">matches</span>";
        }
        var list = new StringBuilder("<ul>");
        for (Finding finding : shown) {
            list.append("<li class=\"").append(finding.severity().name().toLowerCase(Locale.ROOT))
                    .append("\"><span class=\"component ").append(finding.component()).append("\">")
                    .append(finding.component()).append("</span> ").append(escape(finding.message()));
            if (!finding.codes().isEmpty()) {
                list.append(": <code>").append(escape(abbreviate(finding.codes()))).append("</code>");
            }
            list.append("</li>");
        }
        return list.append("</ul>").toString();
    }

    public static int mismatching(RevisionCheck revision) {
        return (int) revision.tabs().stream().filter(tab -> tab.verdict() == IndexCheck.Verdict.MISMATCH).count();
    }

    private static String terms(List<String> terms, String kind) {
        return terms.isEmpty() ? "<td class=\"zero\">·</td>"
                : "<td class=\"codes " + kind + "\"><code>" + escape(join(terms)) + "</code></td>";
    }

    private static String findings(List<Finding> findings) {
        return findings.stream().map(finding -> finding.severity().name() + " " + finding.component() + ": "
                + finding.text()).collect(Collectors.joining("; "));
    }

    private static String shared(Map<String, List<String>> shared) {
        return shared.entrySet().stream().map(entry -> entry.getKey() + " (" + String.join(", ", entry.getValue())
                + ")").collect(Collectors.joining(" "));
    }

    private static String published(Map<String, List<String>> published) {
        return published.entrySet().stream().map(entry -> entry.getKey() + ": " + String.join(" ", entry.getValue()))
                .collect(Collectors.joining("; "));
    }

    private static String join(Collection<String> values) {
        return String.join(" ", values);
    }

    private static String abbreviate(List<String> codes) {
        return codes.size() <= 30 ? String.join(" ", codes)
                : String.join(" ", codes.subList(0, 30)) + " … " + (codes.size() - 30) + " more";
    }
}
