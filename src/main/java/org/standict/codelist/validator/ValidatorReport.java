package org.standict.codelist.validator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.standict.codelist.validator.ValidatorComparison.DatePoint;
import org.standict.codelist.validator.ValidatorComparison.RuleComparison;
import org.standict.codelist.validator.ValidatorComparison.Side;

/**
 * Writes a {@link ValidatorComparison.Report} as two CSV files and one self-contained HTML page.
 *
 * <ul>
 * <li>{@code summary.csv}: one row per effective date and syntax, counting the rules that disagree and the codes they
 * disagree on.</li>
 * <li>{@code rules.csv}: one row per rule, syntax and date, naming every code that differs, so a defect report can quote
 * the codes rather than a count.</li>
 * <li>{@code index.html}: the same, readable in a browser without loading anything from the network.</li>
 * </ul>
 *
 * <p>The CSV files use the project's convention: UTF-8 without BOM, every field quoted, LF line endings. Codes within a
 * field are separated by a single space and listed in base-36 code order.
 */
public final class ValidatorReport {
    private static final String TEMPLATE = "/validator/report.html";
    /** Enough codes to recognise a pattern in the page; the complete lists are in {@code rules.csv}. */
    private static final int CODES_SHOWN = 24;

    public void write(ValidatorComparison.Report report, Path directory) throws IOException {
        Files.createDirectories(directory);
        writeCsv(directory.resolve("summary.csv"), summary(report));
        writeCsv(directory.resolve("rules.csv"), rules(report));
        Files.writeString(directory.resolve("index.html"), html(report), StandardCharsets.UTF_8);
    }

    /** Counts for one date and syntax, over the rules compared with one component. */
    record Totals(int compared, int agreeing, int onlyInValidator, int onlyPublished) {
        static Totals of(List<RuleComparison> rules, Function<RuleComparison, Side> component) {
            int compared = 0;
            int agreeing = 0;
            int onlyInValidator = 0;
            int onlyPublished = 0;
            for (RuleComparison rule : rules) {
                Side side = component.apply(rule);
                if (side == null) {
                    continue;
                }
                compared++;
                agreeing += side.agrees() ? 1 : 0;
                onlyInValidator += side.onlyInValidator().size();
                onlyPublished += side.onlyPublished().size();
            }
            return new Totals(compared, agreeing, onlyInValidator, onlyPublished);
        }

        int disagreeing() {
            return compared - agreeing;
        }
    }

    private static List<RuleComparison> rulesOf(ValidatorComparison.Report report, DatePoint date, Syntax syntax) {
        return report.rules().stream()
                .filter(rule -> rule.effectiveDate().equals(date.effectiveDate()) && rule.syntax() == syntax)
                .toList();
    }

    private List<List<String>> summary(ValidatorComparison.Report report) {
        var rows = new ArrayList<List<String>>();
        rows.add(List.of("effective date", "changed", "validator release", "code-list release", "syntax", "rules",
                "unmapped rules", "genericode rules compared", "genericode rules agreeing",
                "genericode codes only in validator", "genericode codes only in code list",
                "spreadsheet rules compared", "spreadsheet rules agreeing", "spreadsheet codes only in validator",
                "spreadsheet codes only in code list"));
        for (DatePoint date : report.dates()) {
            for (Syntax syntax : Syntax.values()) {
                var rules = rulesOf(report, date, syntax);
                Totals genericode = Totals.of(rules, RuleComparison::genericode);
                Totals spreadsheet = Totals.of(rules, RuleComparison::spreadsheet);
                rows.add(List.of(date.effectiveDate().toString(), trigger(date.trigger()), date.validator().tag(),
                        date.codeLists().directory(), syntax.name(), String.valueOf(rules.size()),
                        String.valueOf(rules.stream().filter(rule -> rule.codeList().isEmpty()).count()),
                        String.valueOf(genericode.compared()), String.valueOf(genericode.agreeing()),
                        String.valueOf(genericode.onlyInValidator()), String.valueOf(genericode.onlyPublished()),
                        String.valueOf(spreadsheet.compared()), String.valueOf(spreadsheet.agreeing()),
                        String.valueOf(spreadsheet.onlyInValidator()), String.valueOf(spreadsheet.onlyPublished())));
            }
        }
        return rows;
    }

    private List<List<String>> rules(ValidatorComparison.Report report) {
        var rows = new ArrayList<List<String>>();
        rows.add(List.of("effective date", "validator release", "code-list release", "syntax", "rule", "code list",
                "validator codes", "genericode source", "genericode codes", "only in validator (genericode)",
                "only in genericode", "spreadsheet source", "spreadsheet codes", "only in validator (spreadsheet)",
                "only in spreadsheet"));
        for (RuleComparison rule : report.rules()) {
            var row = new ArrayList<>(List.of(rule.effectiveDate().toString(), rule.validatorTag(),
                    rule.codeListRelease(), rule.syntax().name(), rule.rule(), rule.codeList(),
                    String.valueOf(rule.validatorCodes())));
            for (Side side : new Side[] {rule.genericode(), rule.spreadsheet()}) {
                if (side == null) {
                    row.addAll(List.of("", "", "", ""));
                } else {
                    row.addAll(List.of(side.source(), String.valueOf(side.published()),
                            String.join(" ", side.onlyInValidator()), String.join(" ", side.onlyPublished())));
                }
            }
            rows.add(row);
        }
        return rows;
    }

    private String html(ValidatorComparison.Report report) throws IOException {
        String template;
        try (InputStream stream = ValidatorReport.class.getResourceAsStream(TEMPLATE)) {
            if (stream == null) {
                throw new IOException("Missing resource " + TEMPLATE);
            }
            template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var dates = report.dates();
        return template
                .replace("{{dates}}", String.valueOf(dates.size()))
                .replace("{{first}}", dates.isEmpty() ? "-" : dates.get(0).effectiveDate().toString())
                .replace("{{last}}", dates.isEmpty() ? "-" : dates.get(dates.size() - 1).effectiveDate().toString())
                .replace("{{validators}}", String.valueOf(dates.stream().map(date -> date.validator().tag())
                        .distinct().count()))
                .replace("{{summary}}", summaryRows(report))
                .replace("{{details}}", details(report));
    }

    private String summaryRows(ValidatorComparison.Report report) {
        var rows = new StringBuilder();
        for (DatePoint date : report.dates().reversed()) {
            rows.append("<tr><th scope=\"row\"><a href=\"#d").append(date.effectiveDate()).append("\">")
                    .append(date.effectiveDate()).append("</a></th><td class=\"list\">")
                    .append(escape(trigger(date.trigger()))).append("</td><td class=\"list\">")
                    .append(escape(date.validator().tag().replaceFirst("^validation-", ""))).append("</td><td class=\"list\">")
                    .append(escape(date.codeLists().directory())).append("</td>");
            for (Syntax syntax : Syntax.values()) {
                var rules = rulesOf(report, date, syntax);
                rows.append(totals(Totals.of(rules, RuleComparison::genericode)))
                        .append(totals(Totals.of(rules, RuleComparison::spreadsheet)));
            }
            rows.append("</tr>\n");
        }
        return rows.isEmpty() ? "<tr><td colspan=\"8\">No date on which both sides were in force.</td></tr>"
                : rows.toString();
    }

    /** {@code 3 / 24 · 7}: disagreeing rules of compared rules, and the codes they disagree on. */
    private static String totals(Totals totals) {
        if (totals.compared() == 0) {
            return "<td class=\"absent\">not published</td>";
        }
        if (totals.disagreeing() == 0) {
            return "<td class=\"zero\">all " + totals.compared() + " agree</td>";
        }
        return "<td class=\"removed\">" + totals.disagreeing() + " / " + totals.compared() + " <span class=\"quiet\">· "
                + (totals.onlyInValidator() + totals.onlyPublished()) + " codes</span></td>";
    }

    private String details(ValidatorComparison.Report report) {
        var sections = new StringBuilder();
        boolean latest = true;
        for (DatePoint date : report.dates().reversed()) {
            var rows = new StringBuilder();
            for (Syntax syntax : Syntax.values()) {
                for (RuleComparison rule : rulesOf(report, date, syntax)) {
                    if (rule.codeList().isEmpty()) {
                        rows.append("<tr><td>").append(syntax).append("</td><td>").append(escape(rule.rule()))
                                .append("</td><td class=\"absent\" colspan=\"4\">not in the rule catalogue, ")
                                .append(rule.validatorCodes()).append(" codes</td></tr>\n");
                        continue;
                    }
                    appendSide(rows, syntax, rule, "genericode", rule.genericode());
                    appendSide(rows, syntax, rule, "spreadsheet", rule.spreadsheet());
                }
            }
            sections.append("<details id=\"d").append(date.effectiveDate()).append("\"").append(latest ? " open" : "")
                    .append("><summary><strong>").append(date.effectiveDate()).append("</strong> · validator ")
                    .append(escape(date.validator().tag())).append(" (from ").append(date.validator().effectiveDate())
                    .append(") · code lists ").append(escape(date.codeLists().directory())).append("</summary>\n");
            if (rows.isEmpty()) {
                sections.append("<p>Every rule accepts exactly the codes the published code lists contain.</p>\n");
            } else {
                sections.append("<div class=\"scroll\"><table><thead><tr><th scope=\"col\">Syntax</th>")
                        .append("<th scope=\"col\">Rule</th><th scope=\"col\">Code list</th>")
                        .append("<th scope=\"col\">Compared with</th><th scope=\"col\">Accepted, not published</th>")
                        .append("<th scope=\"col\">Published, not accepted</th></tr></thead><tbody>\n")
                        .append(rows).append("</tbody></table></div>\n");
            }
            sections.append("</details>\n");
            latest = false;
        }
        return sections.toString();
    }

    private static void appendSide(StringBuilder rows, Syntax syntax, RuleComparison rule, String component, Side side) {
        if (side == null || side.agrees()) {
            return;
        }
        rows.append("<tr><td>").append(syntax).append("</td><td>").append(escape(rule.rule()))
                .append("</td><td class=\"list\">").append(escape(rule.codeList()))
                .append("</td><td><span class=\"component ").append(component).append("\" title=\"")
                .append(escape(side.source())).append("\">").append(component).append("</span></td>")
                .append(codes(side.onlyInValidator(), "added")).append(codes(side.onlyPublished(), "removed"))
                .append("</tr>\n");
    }

    private static String codes(List<String> codes, String kind) {
        if (codes.isEmpty()) {
            return "<td class=\"zero\">·</td>";
        }
        String shown = String.join(" ", codes.subList(0, Math.min(CODES_SHOWN, codes.size())));
        String more = codes.size() > CODES_SHOWN ? " <span class=\"quiet\">… " + (codes.size() - CODES_SHOWN)
                + " more</span>" : "";
        return "<td class=\"codes " + kind + "\"><span class=\"count\">" + codes.size() + "</span> <code>"
                + escape(shown) + "</code>" + more + "</td>";
    }

    private static String trigger(ValidatorComparison.Trigger trigger) {
        return switch (trigger) {
            case CODE_LISTS -> "code lists";
            case VALIDATOR -> "validator";
            case BOTH -> "both";
        };
    }

    private static void writeCsv(Path destination, List<List<String>> rows) throws IOException {
        var text = new StringBuilder();
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    text.append(',');
                }
                text.append('"').append(row.get(i).replace("\"", "\"\"")).append('"');
            }
            text.append('\n');
        }
        Files.writeString(destination, text, StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
