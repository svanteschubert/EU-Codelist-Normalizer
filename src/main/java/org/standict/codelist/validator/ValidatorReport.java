package org.standict.codelist.validator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.standict.codelist.index.IndexCheck;
import org.standict.codelist.index.IndexReport;
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

    /** One file the report page links to, relative to the page. */
    public record LinkedFile(String path, String description) {}

    /**
     * Every file the page links to, so that a copy of the page with these files is complete wherever it is served. The
     * configuration tables are the resources the comparison was made with, copied next to the results.
     */
    public static final List<LinkedFile> LINKED_FILES = List.of(
            new LinkedFile("summary.csv", "per effective date and syntax: rules compared, rules agreeing, codes that differ"),
            new LinkedFile("rules.csv", "per effective date, syntax and rule: every differing code, with its name"),
            new LinkedFile("index-claims.csv",
                    "per release revision and Index row: stated against actual changes in sheet and Genericode"),
            new LinkedFile("business-terms.csv",
                    "per release revision and Index row: business terms against EN 16931-1:2017 and the previous release"),
            new LinkedFile("index-releases.csv", "per release revision: the dates its Index states, structural findings"),
            new LinkedFile("configuration/validator-releases.csv", "the date each validator release applies from"),
            new LinkedFile("configuration/rule-catalog.csv", "which code list each BR-CL rule enforces"),
            new LinkedFile("configuration/business-terms-2017.csv",
                    "the business terms of EN 16931-1:2017 that use each code list"));
    /** Enough codes to recognise a pattern in the page; the complete lists are in {@code rules.csv}. */
    private static final int CODES_SHOWN = 24;

    public void write(ValidatorComparison.Report report, Path directory) throws IOException {
        write(report, null, directory);
    }

    /**
     * @param index the check of the Index sheets, or {@code null} to leave its sections out
     */
    public void write(ValidatorComparison.Report report, IndexCheck.Report index, Path directory) throws IOException {
        Files.createDirectories(directory);
        writeCsv(directory.resolve("summary.csv"), summary(report));
        writeCsv(directory.resolve("rules.csv"), rules(report));
        var sections = index == null ? null : new IndexReport(index);
        if (sections != null) {
            writeCsv(directory.resolve("index-claims.csv"), sections.claimsCsv());
            writeCsv(directory.resolve("business-terms.csv"), sections.businessTermsCsv());
            writeCsv(directory.resolve("index-releases.csv"), sections.releasesCsv());
        }
        for (String resource : List.of("validator-releases.csv", "rule-catalog.csv", "business-terms-2017.csv")) {
            try (InputStream stream = ValidatorReport.class.getResourceAsStream("/validator/" + resource)) {
                if (stream == null) {
                    throw new IOException("Missing resource /validator/" + resource);
                }
                Files.createDirectories(directory.resolve("configuration"));
                Files.write(directory.resolve("configuration").resolve(resource), stream.readAllBytes());
            }
        }
        Files.writeString(directory.resolve("index.html"), html(report, sections), StandardCharsets.UTF_8);
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
                "genericode codes implemented, not published", "genericode codes published, not implemented",
                "spreadsheet rules compared", "spreadsheet rules agreeing", "spreadsheet codes implemented, not published",
                "spreadsheet codes published, not implemented"));
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
                "validator codes", "genericode source", "genericode codes", "implemented, not in genericode",
                "in genericode, not implemented", "genericode names", "spreadsheet source", "spreadsheet codes",
                "implemented, not in spreadsheet", "in spreadsheet, not implemented", "spreadsheet names",
                "validator source"));
        for (RuleComparison rule : report.rules()) {
            var row = new ArrayList<>(List.of(rule.effectiveDate().toString(), rule.validatorTag(),
                    rule.codeListRelease(), rule.syntax().name(), rule.rule(), rule.codeList(),
                    String.valueOf(rule.validatorCodes())));
            for (Side side : new Side[] {rule.genericode(), rule.spreadsheet()}) {
                if (side == null) {
                    row.addAll(List.of("", "", "", "", ""));
                } else {
                    row.addAll(List.of(side.source(), String.valueOf(side.published()),
                            String.join(" ", side.onlyInValidator()), String.join(" ", side.onlyPublished()),
                            side.names().entrySet().stream().map(entry -> entry.getKey() + ": "
                                    + entry.getValue().name() + (entry.getValue().from().isEmpty() ? ""
                                            : " [" + entry.getValue().from() + "]"))
                                    .collect(Collectors.joining("; "))));
                }
            }
            row.add(rule.link("") == null ? "" : rule.link(""));
            rows.add(row);
        }
        return rows;
    }

    private String html(ValidatorComparison.Report report, IndexReport sections) throws IOException {
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
                .replace("{{details}}", details(report))
                .replace("{{contents}}", contents(report, sections))
                .replace("{{files}}", LINKED_FILES.stream().filter(file -> sections != null
                        || !file.path().matches("index-.*|business-terms\\.csv"))
                        .map(file -> "<li><a href=\"" + escape(file.path()) + "\"><code>" + escape(file.path())
                                + "</code></a>: " + escape(file.description()) + "</li>")
                        .collect(Collectors.joining("\n")))
                .replace("{{indexSummary}}", sections == null ? "" : sections.summaryHtml())
                .replace("{{indexClaims}}", sections == null ? "" : sections.claimsHtml())
                .replace("{{businessTerms}}", sections == null ? "" : sections.businessTermsHtml())
                .replace("{{indexDates}}", sections == null ? "" : sections.releasesHtml());
    }

    /** The page's sections, each with the number a reader looks for first. */
    private static String contents(ValidatorComparison.Report report, IndexReport sections) {
        var items = new ArrayList<String>();
        String validator = "the codes each BR-CL rule implements, against the published code lists";
        if (!report.dates().isEmpty()) {
            DatePoint latest = report.dates().get(report.dates().size() - 1);
            var parts = new ArrayList<String>();
            for (Syntax syntax : Syntax.values()) {
                Totals totals = Totals.of(rulesOf(report, latest, syntax), RuleComparison::genericode);
                if (totals.compared() > 0) {
                    parts.add(syntax + " " + totals.disagreeing() + " of " + totals.compared());
                }
            }
            validator += "; on " + latest.effectiveDate() + " (validator "
                    + latest.validator().tag().replaceFirst("^validation-", "") + ") "
                    + String.join(", ", parts) + " rules differ from Genericode";
        }
        items.add(item("validator", "Validator against the code lists", validator));
        if (sections != null) {
            items.add(item("index-claims", "Index sheet against the code lists", sections.headline()));
            items.add(item("business-terms", "Business terms against EN 16931-1:2017", sections.businessTermsHeadline()));
            items.add(item("index-dates", "Dates stated on the Index sheet", sections.datesHeadline()));
        }
        items.add(item("codes", "Validator: codes that differ", "every differing code, with its name, per date"));
        items.add(item("files", "Files in this report", "the CSV files and configuration tables behind this page"));
        return String.join("\n", items);
    }

    private static String item(String anchor, String title, String headline) {
        return "    <li><a href=\"#" + anchor + "\">" + escape(title) + "</a> <span>— " + escape(headline) + "</span></li>";
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
                rows.append(totals(date, syntax, "genericode", rules, RuleComparison::genericode))
                        .append(totals(date, syntax, "spreadsheet", rules, RuleComparison::spreadsheet));
            }
            rows.append("</tr>\n");
        }
        return rows.isEmpty() ? "<tr><td colspan=\"8\">No date on which both sides were in force.</td></tr>"
                : rows.toString();
    }

    /**
     * {@code 7 of 22 rules, 11 codes differ}: how many of the rules compared with one component implement other codes
     * than it lists, and how many codes differ in total, a code counting once for every rule it differs in. The cell
     * links to those rules' codes, and its tooltip names the rules.
     */
    private static String totals(DatePoint date, Syntax syntax, String component, List<RuleComparison> rules,
            Function<RuleComparison, Side> side) {
        Totals totals = Totals.of(rules, side);
        String published = component.equals("genericode") ? "the Genericode files" : "the spreadsheet";
        if (totals.compared() == 0) {
            return "<td class=\"absent\" title=\"" + escape(date.codeLists().directory() + " publishes no "
                    + (component.equals("genericode") ? "Genericode files" : "spreadsheet")) + "\">not published</td>";
        }
        int notCompared = (int) rules.stream().filter(rule -> side.apply(rule) == null).count();
        String uncompared = notCompared == 0 ? "" : " " + notCompared + " further " + (notCompared == 1 ? "rule has"
                : "rules have") + " no counterpart in " + published + ".";
        if (totals.disagreeing() == 0) {
            return "<td class=\"zero\" title=\"" + escape("All " + totals.compared() + " " + syntax
                    + " rules implement exactly the codes " + published + " list." + uncompared) + "\">all "
                    + totals.compared() + " agree</td>";
        }
        String differing = rules.stream().filter(rule -> side.apply(rule) != null && !side.apply(rule).agrees())
                .map(rule -> rule.rule() + " (" + rule.codeList() + ")").collect(Collectors.joining(", "));
        String title = totals.disagreeing() + " of " + totals.compared() + " " + syntax + " rules implement other codes than "
                + published + " list: " + differing + ". " + (totals.onlyInValidator() + totals.onlyPublished())
                + " codes differ: " + totals.onlyInValidator() + " implemented but not published, "
                + totals.onlyPublished() + " published but not implemented, each counted once per rule." + uncompared + " Click for the codes.";
        return "<td class=\"removed\"><a href=\"#" + anchor(date, syntax, component) + "\" title=\"" + escape(title)
                + "\">" + totals.disagreeing() + " of " + totals.compared() + " rules<br><span class=\"quiet\">"
                + (totals.onlyInValidator() + totals.onlyPublished()) + " codes differ</span></a></td>";
    }

    /** {@code d2026-05-15-ubl-genericode}: the detail rows of one date, syntax and component. */
    private static String anchor(DatePoint date, Syntax syntax, String component) {
        return "d" + date.effectiveDate() + "-" + syntax.directory() + "-" + component;
    }

    private String details(ValidatorComparison.Report report) {
        var sections = new StringBuilder();
        boolean latest = true;
        for (DatePoint date : report.dates().reversed()) {
            var rows = new StringBuilder();
            for (Syntax syntax : Syntax.values()) {
                var rules = rulesOf(report, date, syntax);
                for (String component : List.of("genericode", "spreadsheet")) {
                    Function<RuleComparison, Side> side = component.equals("genericode") ? RuleComparison::genericode
                            : RuleComparison::spreadsheet;
                    Totals totals = Totals.of(rules, side);
                    if (totals.disagreeing() == 0) {
                        continue;
                    }
                    rows.append("<tr class=\"group\" id=\"").append(anchor(date, syntax, component))
                            .append("\"><th colspan=\"4\" scope=\"rowgroup\">").append(syntax)
                            .append(" compared with ").append(component.equals("genericode") ? "the Genericode files"
                                    : "the spreadsheet").append(" of ").append(escape(date.codeLists().directory()))
                            .append(": ").append(totals.disagreeing()).append(" of ").append(totals.compared())
                            .append(" rules differ, ").append(totals.onlyInValidator() + totals.onlyPublished())
                            .append(" codes (").append(totals.onlyInValidator()).append(" implemented but not published, ")
                            .append(totals.onlyPublished()).append(" published but not implemented)</th></tr>\n");
                    for (RuleComparison rule : rules) {
                        appendSide(rows, rule, side.apply(rule));
                    }
                }
                for (RuleComparison rule : rules) {
                    if (rule.codeList().isEmpty()) {
                        rows.append("<tr><td>").append(syntax).append(" ").append(escape(rule.rule()))
                                .append("</td><td class=\"absent\" colspan=\"3\">not in the rule catalogue, ")
                                .append(rule.validatorCodes()).append(" codes</td></tr>\n");
                    }
                }
            }
            sections.append("<details id=\"d").append(date.effectiveDate()).append("\"").append(latest ? " open" : "")
                    .append("><summary><strong>").append(date.effectiveDate()).append("</strong> · validator ")
                    .append(escape(date.validator().tag())).append(" (from ").append(date.validator().effectiveDate())
                    .append(") · code lists ").append(escape(date.codeLists().directory())).append("</summary>\n");
            if (rows.isEmpty()) {
                sections.append("<p>Every rule implements exactly the codes the published code lists contain.</p>\n");
            } else {
                sections.append("<div class=\"scroll\"><table><thead><tr><th scope=\"col\">Rule</th>")
                        .append("<th scope=\"col\">Code list</th><th scope=\"col\">Source</th>")
                        .append("<th scope=\"col\">Differences</th></tr></thead><tbody>\n")
                        .append(rows).append("</tbody></table></div>\n");
            }
            sections.append("</details>\n");
            latest = false;
        }
        return sections.toString();
    }

    private static void appendSide(StringBuilder rows, RuleComparison rule, Side side) {
        if (side == null || side.agrees()) {
            return;
        }
        rows.append("<tr><td>").append(escape(rule.rule())).append("</td><td class=\"list\">")
                .append(escape(rule.codeList())).append("</td><td class=\"list quiet\">").append(escape(side.source()))
                .append("</td>").append(differences(rule, side)).append("</tr>\n");
    }

    /**
     * Every differing code on a line of its own, in code order, with its name and what differs about it, so that a
     * code the validator still implements stands next to the code that replaced it (STD beside STN).
     */
    private static String differences(RuleComparison rule, Side side) {
        var codes = new java.util.TreeMap<String, Boolean>(
                org.standict.codelist.normalize.GenericodeNormalizer.CODE_ORDER);
        side.onlyInValidator().forEach(code -> codes.put(code, true));
        side.onlyPublished().forEach(code -> codes.put(code, false));
        var lines = new StringBuilder("<td class=\"differences\"><ul>");
        int shown = 0;
        for (var entry : codes.entrySet()) {
            if (shown++ == CODES_SHOWN) {
                lines.append("<li class=\"quiet\">… ").append(codes.size() - CODES_SHOWN)
                        .append(" more in <a href=\"rules.csv\">rules.csv</a></li>");
                break;
            }
            String code = entry.getKey();
            boolean implemented = entry.getValue();
            var description = side.names().get(code);
            String link = rule.link(code);
            String where = rule.rule() + " in " + rule.syntax().fileName() + " of " + rule.validatorTag();
            String kind = implemented
                    ? linked("implemented", link, code + " is listed by " + where + ", line "
                            + rule.lines().getOrDefault(code, 0)) + ", not published"
                    : "published, " + linked("not implemented", link, code + " is missing from the list of " + where
                            + ", which starts on line " + rule.listLine());
            String provenance = description == null || description.from().isEmpty() ? ""
                    : (description.from().compareTo(rule.codeListRelease()) < 0 ? "last listed in "
                            : "first listed in ") + description.from();
            String title = code + (description == null ? ": listed by no EU release of the " + rule.codeList()
                    + " code list in this comparison"
                    : ": " + description.name() + " (" + (provenance.isEmpty() ? side.source() : provenance) + ")");
            lines.append("<li class=\"").append(implemented ? "implemented" : "unimplemented").append("\" title=\"")
                    .append(escape(title)).append("\"><code>").append(escape(code)).append("</code> ");
            if (description != null) {
                String name = description.name().length() > 70 ? description.name().substring(0, 69) + "…"
                        : description.name();
                lines.append("<span class=\"name\">(").append(escape(name))
                        .append(provenance.isEmpty() ? "" : ", " + escape(provenance)).append(")</span> ");
            } else if (implemented) {
                lines.append("<span class=\"name quiet\">(listed by no EU release of ").append(escape(rule.codeList()))
                        .append(")</span> ");
            }
            lines.append("<span class=\"kind\">— ").append(kind).append("</span></li>");
        }
        return lines.append("</ul></td>").toString();
    }

    /** {@code text} as a link to {@code url} in a new tab, or plain when there is no link. */
    private static String linked(String text, String url, String title) {
        return url == null ? text
                : "<a class=\"source\" href=\"" + escape(url) + "\" target=\"_blank\" rel=\"noopener\" title=\""
                        + escape(title) + "\">" + text + "</a>";
    }

    private static String trigger(ValidatorComparison.Trigger trigger) {
        return switch (trigger) {
            case CODE_LISTS -> "code lists";
            case VALIDATOR -> "validator";
            case BOTH -> "both";
        };
    }

    /** Writes quoted UTF-8 CSV with LF line endings, the project's convention. */
    public static void writeCsv(Path destination, List<List<String>> rows) throws IOException {
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

    public static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
