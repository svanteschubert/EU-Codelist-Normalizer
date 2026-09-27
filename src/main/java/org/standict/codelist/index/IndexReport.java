package org.standict.codelist.index;

import static org.standict.codelist.validator.ValidatorReport.escape;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.standict.codelist.index.IndexCheck.Finding;
import org.standict.codelist.index.IndexCheck.RevisionCheck;
import org.standict.codelist.index.IndexCheck.TabCheck;

/**
 * Renders an {@link IndexCheck.Report} as CSV rows and as the HTML sections of the comparison report.
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

    public IndexReport(IndexCheck.Report report) {
        this.report = report;
    }

    public List<List<String>> claimsCsv() {
        var rows = new ArrayList<List<String>>();
        rows.add(List.of("release", "previous release", "tab", "changes", "remark on updates", "stated added",
                "stated removed", "stated renamed", "stated deprecated", "stated counts", "unresolved in remark",
                "sheet added", "sheet removed", "sheet renamed", "sheet other columns changed",
                "genericode compared with", "genericode added", "genericode removed", "genericode renamed",
                "verdict", "findings"));
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

    /** One row per revision: how many of its Index rows disagree with its sheets, and how its terms compare. */
    public String summaryHtml() {
        var rows = new StringBuilder();
        for (RevisionCheck revision : report.revisions().reversed()) {
            long differing = revision.terms().stream().filter(BusinessTerms.Check::differsFrom2017).count();
            long changed = revision.terms().stream().filter(BusinessTerms.Check::changedSincePrevious).count();
            int mismatching = mismatching(revision);
            rows.append("<tr><th scope=\"row\"><a href=\"#i").append(anchor(revision.revision())).append("\">")
                    .append(escape(revision.revision())).append("</a></th><td class=\"list\">")
                    .append(escape(revision.previous().isEmpty() ? "—" : revision.previous())).append("</td>")
                    .append(count(mismatching, revision.tabs().size(), "rows disagree"))
                    .append(revision.findings().stream().anyMatch(f -> f.severity() == IndexCheck.Severity.MISMATCH)
                            ? "<td class=\"removed\">" + escape(revision.findings().stream()
                                    .filter(f -> f.severity() == IndexCheck.Severity.MISMATCH).map(Finding::text)
                                    .collect(Collectors.joining("; "))) + "</td>"
                            : "<td class=\"zero\">·</td>")
                    .append(count((int) differing, revision.terms().size(), "tabs differ"))
                    .append(changed == 0 ? "<td class=\"zero\">·</td>"
                            : "<td class=\"reworded\">" + changed + " tabs</td>")
                    .append("</tr>\n");
        }
        return rows.toString();
    }

    /**
     * One collapsible block per revision. Each Index row that states a change, or whose list changed, is shown with
     * what the Index claims next to what the spreadsheet and the Genericode file actually changed since the release
     * before, and what does not match. Rows that disagree come first.
     */
    public String claimsHtml() {
        var sections = new StringBuilder();
        boolean latest = true;
        for (RevisionCheck revision : report.revisions().reversed()) {
            int mismatching = mismatching(revision);
            sections.append("<details id=\"i").append(anchor(revision.revision())).append("\"")
                    .append(latest ? " open" : "").append("><summary><strong>").append(escape(revision.revision()))
                    .append("</strong> against ").append(escape(revision.previous().isEmpty() ? "nothing"
                            : revision.previous())).append(" · ")
                    .append(mismatching == 0 ? "<span class=\"agree\">every row agrees</span>"
                            : "<span class=\"disagree\">" + mismatching + " of " + revision.tabs().size()
                                    + " rows disagree</span>")
                    .append("</summary>\n");
            var revisionFindings = revision.findings().stream().map(finding -> "<li class=\""
                    + finding.severity().name().toLowerCase(Locale.ROOT) + "\">" + escape(finding.text()) + "</li>")
                    .collect(Collectors.joining());
            if (!revisionFindings.isEmpty()) {
                sections.append("<ul class=\"findings\">").append(revisionFindings).append("</ul>\n");
            }
            var shown = revision.tabs().stream().filter(IndexReport::worthShowing)
                    .sorted(java.util.Comparator.comparing((TabCheck tab) -> tab.verdict() != IndexCheck.Verdict.MISMATCH))
                    .toList();
            if (shown.isEmpty()) {
                sections.append("<p>No row states a change, and no list changed.</p>\n");
            } else {
                sections.append("<div class=\"scroll\"><table class=\"claims\"><thead><tr>")
                        .append("<th scope=\"col\" rowspan=\"2\">Tab</th>")
                        .append("<th scope=\"col\" class=\"group\" colspan=\"2\">The Index sheet claims</th>")
                        .append("<th scope=\"col\" class=\"group\" colspan=\"2\">Actual changes since ")
                        .append(escape(revision.previous().isEmpty() ? "—" : revision.previous())).append("</th>")
                        .append("<th scope=\"col\" rowspan=\"2\">What does not match</th></tr>")
                        .append("<tr><th scope=\"col\">Changes</th><th scope=\"col\">Remark on updates</th>")
                        .append("<th scope=\"col\">Spreadsheet</th><th scope=\"col\">Genericode</th></tr>")
                        .append("</thead><tbody>\n");
                for (TabCheck tab : shown) {
                    sections.append("<tr class=\"").append(tab.verdict().name().toLowerCase(Locale.ROOT))
                            .append("\"><th scope=\"row\">").append(escape(tab.tab())).append("</th><td class=\"list\">")
                            .append(escape(tab.claims().flagText().isEmpty() ? "—" : tab.claims().flagText()))
                            .append("</td><td class=\"note\">")
                            .append(tab.claims().remark().isEmpty() ? "<span class=\"quiet\">—</span>"
                                    : escape(tab.claims().remark()))
                            .append("</td>").append(actual(tab.sheet(), "", 1, revision.previous().isEmpty()
                                    ? "first release in this comparison" : "no sheet"))
                            .append(actual(tab.genericode(), tab.genericodeBaseline(), tab.genericodeSpan(),
                                    tab.findings().stream().anyMatch(f -> f.message().startsWith("first Genericode"))
                                            ? "first Genericode release of this list" : "no Genericode file"))
                            .append("<td class=\"note\">").append(findingsList(tab)).append("</td></tr>\n");
                }
                sections.append("</tbody></table></div>\n");
            }
            int quiet = revision.tabs().size() - shown.size();
            if (quiet > 0) {
                sections.append("<p class=\"quiet\">").append(quiet).append(quiet == 1 ? " further row states"
                        : " further rows state").append(" no change, and none happened.</p>\n");
            }
            sections.append("</details>\n");
            latest = false;
        }
        return sections.toString();
    }

    /** A row is shown when it claims something, when its list changed, or when anything about it does not match. */
    private static boolean worthShowing(TabCheck tab) {
        return tab.verdict() == IndexCheck.Verdict.MISMATCH || tab.claims().flag() == ChangeClaims.Flag.YES
                || !tab.claims().remark().isEmpty() || (tab.sheet() != null && tab.sheet().changesCodes())
                || (tab.genericode() != null && tab.genericode().changesCodes());
    }

    /** What one component actually changed: added, removed and renamed codes, one kind per line. */
    private static String actual(ActualChanges changes, String baseline, int span, String absent) {
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
        line(lines, "added", "+", changes.added());
        line(lines, "removed", "−", changes.removed());
        line(lines, "reworded", "renamed", changes.renamed());
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

    /** Headline numbers for the contents at the top of the page. */
    public String headline() {
        int rows = report.revisions().stream().mapToInt(revision -> revision.tabs().size()).sum();
        long revisions = report.revisions().stream().filter(revision -> mismatching(revision) > 0).count();
        int disagreeing = report.revisions().stream().mapToInt(IndexReport::mismatching).sum();
        return disagreeing + " of " + rows + " Index rows, in " + revisions + " of " + report.revisions().size()
                + " release revisions, claim changes the spreadsheets and Genericode files do not show, or leave out"
                + " changes they do";
    }

    public String businessTermsHeadline() {
        var differing = report.revisions().isEmpty() ? List.<BusinessTerms.Check>of()
                : report.revisions().get(report.revisions().size() - 1).terms().stream()
                        .filter(BusinessTerms.Check::differsFrom2017).toList();
        return differing.isEmpty() ? "the latest Index names exactly the business terms of EN 16931-1:2017"
                : "in the latest Index, " + differing.size() + " tabs list other business terms than EN 16931-1:2017 ("
                        + differing.stream().map(BusinessTerms.Check::tab).collect(Collectors.joining(", ")) + ")";
    }

    public String datesHeadline() {
        long differing = report.revisions().stream().filter(revision -> revision.index().effectiveDate() != null
                && !revision.index().effectiveDate().equals(revision.directoryDate())).count();
        return differing + " of " + report.revisions().size()
                + " release revisions state another effective date than the one they are filed under";
    }

    /**
     * The latest revision's terms against 2017 in full, then every change of the column from one release to the
     * next, then whether any Genericode file or sheet named a business term at all.
     */
    public String businessTermsHtml() {
        if (report.revisions().isEmpty()) {
            return "<p>No Index sheet was found.</p>";
        }
        RevisionCheck latest = report.revisions().get(report.revisions().size() - 1);
        var html = new StringBuilder("<div class=\"scroll\"><table><caption>")
                .append(escape(latest.revision()))
                .append("</caption><thead><tr><th scope=\"col\">Tab</th><th scope=\"col\">Index</th>")
                .append("<th scope=\"col\">EN 16931-1:2017</th><th scope=\"col\">Only in Index</th>")
                .append("<th scope=\"col\">Only in 2017</th><th scope=\"col\">2017 evidence</th></tr></thead><tbody>\n");
        for (BusinessTerms.Check check : latest.terms()) {
            html.append("<tr><td class=\"list\">").append(escape(check.tab())).append("</td><td class=\"note\">")
                    .append(escape(join(check.index()))).append("</td><td class=\"note\">")
                    .append(check.reference() == null ? "<em>no reference</em>"
                            : escape(join(check.reference().terms())))
                    .append("</td>").append(terms(check.onlyInIndex(), "added"))
                    .append(terms(check.onlyIn2017(), "removed")).append("<td class=\"note quiet\">")
                    .append(check.reference() == null ? "" : escape(check.reference().evidence()))
                    .append("</td></tr>\n");
        }
        html.append("</tbody></table></div>\n");

        var changes = new StringBuilder();
        for (RevisionCheck revision : report.revisions()) {
            for (BusinessTerms.Check check : revision.terms()) {
                if (check.changedSincePrevious() && !revision.previous().isEmpty()) {
                    changes.append("<tr><td class=\"list\">").append(escape(revision.revision()))
                            .append("</td><td class=\"list\">").append(escape(check.tab())).append("</td>")
                            .append(terms(check.addedSincePrevious(), "added"))
                            .append(terms(check.removedSincePrevious(), "removed")).append("</tr>\n");
                }
            }
        }
        html.append("<h3>Changes of the column from one release to the next</h3>\n");
        html.append(changes.isEmpty() ? "<p>The column has not changed since the first release.</p>\n"
                : "<div class=\"scroll\"><table><thead><tr><th scope=\"col\">Release</th><th scope=\"col\">Tab</th>"
                        + "<th scope=\"col\">Added</th><th scope=\"col\">Removed</th></tr></thead><tbody>\n" + changes
                        + "</tbody></table></div>\n");

        var outside = report.revisions().stream().flatMap(revision -> revision.terms().stream()
                .filter(check -> !check.published().isEmpty())
                .map(check -> revision.revision() + " " + check.tab() + ": " + published(check.published())))
                .toList();
        html.append("<h3>Business terms in Genericode files and code list sheets</h3>\n");
        html.append(outside.isEmpty()
                ? "<p>None. No Genericode file and no code list sheet of any release names a business term, so the "
                        + "Index is the only source to check against EN 16931-1:2017.</p>\n"
                : "<ul>" + outside.stream().map(line -> "<li>" + escape(line) + "</li>").collect(Collectors.joining())
                        + "</ul>\n");
        return html.toString();
    }

    public String releasesHtml() {
        var rows = new StringBuilder();
        for (RevisionCheck revision : report.revisions().reversed()) {
            boolean differs = revision.index().effectiveDate() != null
                    && !revision.index().effectiveDate().equals(revision.directoryDate());
            rows.append("<tr><th scope=\"row\">").append(escape(revision.revision())).append("</th><td>")
                    .append(escape(revision.index().publicationDateText().isEmpty() ? "—"
                            : revision.index().publicationDateText()))
                    .append("</td><td class=\"").append(differs ? "removed" : "quiet").append("\">")
                    .append(revision.index().effectiveDate() == null ? "—" : revision.index().effectiveDate())
                    .append("</td><td>").append(revision.directoryDate()).append("</td></tr>\n");
        }
        return rows.toString();
    }

    private static int mismatching(RevisionCheck revision) {
        return (int) revision.tabs().stream().filter(tab -> tab.verdict() == IndexCheck.Verdict.MISMATCH).count();
    }

    private static String count(int value, int of, String what) {
        return value == 0 ? "<td class=\"zero\">all " + of + " agree</td>"
                : "<td class=\"removed\">" + value + " / " + of + " <span class=\"quiet\">" + what + "</span></td>";
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

    private static String anchor(String revision) {
        return revision.replaceAll("[^0-9A-Za-z_-]", "-");
    }
}
