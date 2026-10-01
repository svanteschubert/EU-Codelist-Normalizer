package org.standict.codelist.validator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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
 * Writes a {@link ValidatorComparison.Report} as CSV files and two self-contained HTML pages.
 *
 * <ul>
 * <li>{@code summary.csv}: one row per effective date and syntax, counting the rules that disagree and the codes they
 * disagree on.</li>
 * <li>{@code rules.csv}: one row per rule, syntax and date, naming every code that differs, so a defect report can quote
 * the codes rather than a count.</li>
 * <li>{@code index.html}: every effective date, newest first, each opening in place to what the Commission declared
 * (spreadsheet, Genericode and the Index sheet's change notes, compared with each other) and what the validator
 * implemented (compared with both declarations). Nothing is loaded from the network.</li>
 * <li>{@code about.html}: how each comparison works, linked from the blocks of the report.</li>
 * </ul>
 *
 * <p>The CSV files use the project's convention: UTF-8 without BOM, every field quoted, LF line endings. Codes within a
 * field are separated by a single space and listed in base-36 code order.
 */
public final class ValidatorReport {
    private static final String TEMPLATE = "/validator/report.html";
    private static final String ABOUT = "/validator/about.html";
    /** The explanation page, next to the report wherever it is copied. */
    static final String ABOUT_PAGE = "about.html";

    /** One file the report page links to, relative to the page. */
    public record LinkedFile(String path, String description) {}

    /**
     * Every file the page links to, so that a copy of the page with these files is complete wherever it is served. The
     * configuration tables are the resources the comparison was made with, copied next to the results.
     */
    public static final List<LinkedFile> LINKED_FILES = List.of(
            new LinkedFile(ABOUT_PAGE, "how each comparison works, and what each file holds"),
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
                    "the business terms of EN 16931-1:2017 that use each code list"),
            new LinkedFile("configuration/code-successions.csv",
                    "codes that replaced other codes, with the Wikipedia article on each change"));
    /** Enough codes to recognise a pattern in the page; the complete lists are in {@code rules.csv}. */
    private static final int CODES_SHOWN = 24;

    private final Successions successions;
    private final Sources sources;

    public ValidatorReport() throws IOException {
        this(Sources.none());
    }

    /** @param sources where the published codes are written, for the links; {@link Sources#none()} for none */
    public ValidatorReport(Sources sources) throws IOException {
        this(Successions.load(), sources);
    }

    ValidatorReport(Successions successions, Sources sources) {
        this.successions = successions;
        this.sources = sources;
    }

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
        var sections = index == null ? null : new IndexReport(index, successions, sources);
        if (sections != null) {
            writeCsv(directory.resolve("index-claims.csv"), sections.claimsCsv());
            writeCsv(directory.resolve("business-terms.csv"), sections.businessTermsCsv());
            writeCsv(directory.resolve("index-releases.csv"), sections.releasesCsv());
        }
        for (String resource : List.of("validator-releases.csv", "rule-catalog.csv", "business-terms-2017.csv",
                "code-successions.csv")) {
            try (InputStream stream = ValidatorReport.class.getResourceAsStream("/validator/" + resource)) {
                if (stream == null) {
                    throw new IOException("Missing resource /validator/" + resource);
                }
                Files.createDirectories(directory.resolve("configuration"));
                Files.write(directory.resolve("configuration").resolve(resource), stream.readAllBytes());
            }
        }
        Files.writeString(directory.resolve("index.html"), html(report, sections), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(ABOUT_PAGE), about(sections), StandardCharsets.UTF_8);
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
        var dates = report.dates();
        return template(TEMPLATE)
                .replace("{{range}}", dates.isEmpty() ? "no date on which both sides were in force"
                        : dates.size() + " effective dates, " + dates.get(0).effectiveDate() + " to "
                                + dates.get(dates.size() - 1).effectiveDate() + ", "
                                + dates.stream().map(date -> date.validator().tag()).distinct().count()
                                + " validator releases")
                .replace("{{glance}}", glance(report, sections))
                .replace("{{timeline}}", timeline(report, sections))
                .replace("{{downloads}}", files(sections).stream().filter(file -> file.path().endsWith(".csv")
                        && !file.path().contains("/")).map(file -> "<a href=\"" + escape(file.path()) + "\" title=\""
                                + escape(file.description()) + "\">" + escape(file.path()) + "</a>")
                        .collect(Collectors.joining(" · ")));
    }

    private static String about(IndexReport sections) throws IOException {
        return template(ABOUT).replace("{{files}}", files(sections).stream()
                .filter(file -> !file.path().equals(ABOUT_PAGE))
                .map(file -> "<li><a href=\"" + escape(file.path()) + "\"><code>" + escape(file.path())
                        + "</code></a>: " + escape(file.description()) + "</li>")
                .collect(Collectors.joining("\n")));
    }

    private static List<LinkedFile> files(IndexReport sections) {
        return LINKED_FILES.stream().filter(file -> sections != null
                || !file.path().matches("index-.*|business-terms\\.csv")).toList();
    }

    private static String template(String resource) throws IOException {
        try (InputStream stream = ValidatorReport.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Missing resource " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** One rule against everything the code lists declare: it differs when it differs from either component. */
    record Implemented(int compared, int differing, int codes) {
        static Implemented of(List<RuleComparison> rules) {
            int compared = 0;
            int differing = 0;
            int codes = 0;
            for (RuleComparison rule : rules) {
                if (rule.genericode() == null && rule.spreadsheet() == null) {
                    continue;
                }
                compared++;
                var differ = new java.util.HashSet<String>();
                for (Side side : new Side[] {rule.genericode(), rule.spreadsheet()}) {
                    if (side != null) {
                        differ.addAll(side.onlyInValidator());
                        differ.addAll(side.onlyPublished());
                    }
                }
                differing += differ.isEmpty() ? 0 : 1;
                codes += differ.size();
            }
            return new Implemented(compared, differing, codes);
        }
    }

    /**
     * The latest date in four tiles, two for what the Commission declares and one per syntax for what the validator
     * implements, each linking to its block in the timeline.
     */
    private static String glance(ValidatorComparison.Report report, IndexReport sections) {
        if (report.dates().isEmpty()) {
            return "";
        }
        DatePoint latest = report.dates().get(report.dates().size() - 1);
        var tiles = new StringBuilder();
        String declared = "d" + latest.codeLists().effectiveDate();
        var revisions = sections == null ? List.<IndexCheck.RevisionCheck>of()
                : sections.revisionsOn(latest.codeLists().effectiveDate());
        if (!revisions.isEmpty()) {
            var current = revisions.get(revisions.size() - 1);
            if (IndexReport.hasGenericode(current)) {
                int differing = IndexReport.listedDiffering(current).size();
                long compared = current.tabs().stream().filter(tab -> tab.listed() != null).count();
                tiles.append(tile(differing == 0, "#" + declared + "-sheet-genericode", "Declared",
                        "Spreadsheet ⇄ Genericode", differing == 0 ? "same codes"
                                : differing + " of " + compared + " lists differ",
                        differing == 0 ? "all " + compared + " lists published both ways agree"
                                : "lists with other codes in the spreadsheet than in Genericode"));
            }
            int mismatching = IndexReport.mismatching(current);
            tiles.append(tile(mismatching == 0, "#" + declared + "-index", "Declared", "Index change notes",
                    mismatching == 0 ? "all correct" : mismatching + " of " + current.tabs().size() + " rows wrong",
                    mismatching == 0 ? "every stated change happened" : "state other changes than the code lists show"));
        }
        for (Syntax syntax : Syntax.values()) {
            var rules = rulesOf(report, latest, syntax);
            Implemented implemented = Implemented.of(rules);
            if (implemented.compared() == 0) {
                continue;
            }
            tiles.append(tile(implemented.differing() == 0, "#" + firstDiffering(latest, syntax, rules), "Implemented",
                    syntax + " validator", implemented.differing() == 0 ? "all rules agree"
                            : implemented.differing() + " of " + implemented.compared() + " rules differ",
                    implemented.differing() == 0 ? "every rule accepts exactly the declared codes"
                            : implemented.codes() + " codes implemented otherwise than declared"));
        }
        return "<p class=\"glance-title\">At a glance: " + latest.effectiveDate() + " · code lists "
                + escape(latest.codeLists().directory()) + " · validator " + escape(version(latest.validator().tag()))
                + "</p>\n<div class=\"glance\">\n" + tiles + "</div>\n";
    }

    /** A status in the timeline, with the label its column heading gives it on a wide screen. */
    private static String cell(String label, String chip) {
        return "<span class=\"c-chip\"><span class=\"m-label\">" + escape(label) + "</span>" + chip + "</span>";
    }

    private static String tile(boolean ok, String href, String kind, String title, String value, String note) {
        // The part, declared or implemented, colours the tile's label as it colours that part of every date.
        return "<a class=\"tile " + (ok ? "ok" : "bad") + " " + kind.toLowerCase(java.util.Locale.ROOT) + "\" href=\""
                + escape(href) + "\"><span class=\"tile-kind\">"
                + escape(kind) + "</span><span class=\"tile-title\">" + escape(title) + "</span><span class=\"tile-value\">"
                + (ok ? "✓ " : "✗ ") + escape(value) + "</span><span class=\"tile-note\">" + escape(note) + "</span></a>\n";
    }

    /** The anchor of the first component a syntax differs from on a date, or of its Genericode block. */
    private static String firstDiffering(DatePoint date, Syntax syntax, List<RuleComparison> rules) {
        for (String component : List.of("genericode", "spreadsheet")) {
            if (Totals.of(rules, side(component)).disagreeing() > 0) {
                return anchor(date, syntax, component);
            }
        }
        return anchor(date, syntax, "genericode");
    }

    private static Function<RuleComparison, Side> side(String component) {
        return component.equals("genericode") ? RuleComparison::genericode : RuleComparison::spreadsheet;
    }

    /**
     * Every effective date, newest first, as one row that opens in place: what the Commission declared, with its
     * three parts compared with each other, then what the validator implemented, compared with both declarations.
     */
    private String timeline(ValidatorComparison.Report report, IndexReport sections) {
        var html = new StringBuilder();
        html.append("<div class=\"timeline-head\" aria-hidden=\"true\"><span class=\"h-date\">Effective date</span>")
                .append("<span class=\"h-what\">What took effect</span><span class=\"h-declared\">Declared</span>")
                .append("<span class=\"h-implemented\">Implemented</span><span>Spreadsheet ⇄ Genericode</span>")
                .append("<span>Index change notes</span><span>UBL validator</span><span>CII validator</span></div>\n");
        boolean latest = true;
        var releaseDates = new java.util.HashMap<String, LocalDate>();
        for (DatePoint date : report.dates()) {
            releaseDates.putIfAbsent(date.codeLists().directory(), date.effectiveDate());
        }
        for (DatePoint date : report.dates().reversed()) {
            String id = "d" + date.effectiveDate();
            boolean newCodeLists = date.trigger() != ValidatorComparison.Trigger.VALIDATOR;
            boolean newValidator = date.trigger() != ValidatorComparison.Trigger.CODE_LISTS;
            var revisions = sections == null || !newCodeLists ? List.<IndexCheck.RevisionCheck>of()
                    : sections.revisionsOn(date.codeLists().effectiveDate());
            var current = revisions.isEmpty() ? null : revisions.get(revisions.size() - 1);

            html.append("<details class=\"date\" id=\"").append(id).append("\"").append(latest ? " open" : "")
                    .append("><summary><span class=\"c-date\">").append(date.effectiveDate())
                    .append("</span><span class=\"c-what\">")
                    .append(newCodeLists ? "<strong>code lists " + escape(release(date.codeLists().directory()))
                            + "</strong>" : "<span class=\"quiet\">code lists " + escape(release(date.codeLists()
                                    .directory())) + "</span>")
                    .append(" · ").append(newValidator ? "<strong>validator " + escape(version(date.validator().tag()))
                            + "</strong>" : "<span class=\"quiet\">validator " + escape(version(date.validator().tag()))
                                    + "</span>")
                    .append("</span>");
            if (current == null) {
                String unchanged = newCodeLists ? "—" : "unchanged";
                html.append(cell("Sheet ⇄ Genericode", chip("none", unchanged, null, newCodeLists ? "no Index sheet"
                        : "the code lists did not change")))
                        .append(cell("Index notes", chip("none", unchanged, null, null)));
            } else {
                int listed = IndexReport.listedDiffering(current).size();
                int notes = IndexReport.mismatching(current);
                html.append(cell("Sheet ⇄ Genericode", IndexReport.hasGenericode(current)
                        ? chip(listed == 0 ? "ok" : "bad", listed == 0 ? "same codes" : listed + " lists differ",
                                "#" + id + "-sheet-genericode", null)
                        : chip("none", "no Genericode", null, "the spreadsheet was the only code list")))
                        .append(cell("Index notes", chip(notes == 0 ? "ok" : "bad", notes == 0 ? "all correct" : notes
                                + " of " + current.tabs().size() + " wrong", "#" + id + "-index", null)));
            }
            for (Syntax syntax : Syntax.values()) {
                var rules = rulesOf(report, date, syntax);
                Implemented implemented = Implemented.of(rules);
                html.append(cell(syntax.name(), implemented.compared() == 0 ? chip("none", "—", null, null)
                        : chip(implemented.differing() == 0 ? "ok" : "bad", implemented.differing() == 0 ? "all agree"
                                : implemented.differing() + " of " + implemented.compared() + " rules",
                                "#" + firstDiffering(date, syntax, rules), implemented.codes() + " codes differ")));
            }
            html.append("</summary>\n<div class=\"date-body\">\n");

            html.append("<section class=\"part declared\"><h3><span class=\"part-name\">Declared</span> ")
                    .append("<span class=\"part-by\">by the European Commission · ");
            if (current != null) {
                html.append("code lists ").append(escape(date.codeLists().directory()));
                var originals = sources.originals(current.revision());
                if (!originals.isEmpty()) {
                    html.append(" · originals: ").append(originals.stream().map(spot -> linked(
                            escape(spot.title().replaceFirst(" — .*", "")), spot)).collect(Collectors.joining(", ")));
                }
                html.append("</span></h3>\n").append(sections.declarationHtml(id, revisions, latest));
            } else if (newCodeLists) {
                html.append("code lists ").append(escape(date.codeLists().directory())).append("</span></h3>\n")
                        .append("<p class=\"block-none\">No Index sheet to check.</p>\n");
            } else {
                LocalDate since = releaseDates.get(date.codeLists().directory());
                html.append("unchanged</span></h3>\n<p class=\"block-none\">Code lists ")
                        .append(escape(date.codeLists().directory())).append(", in force since <a href=\"#d").append(since)
                        .append("\">").append(since).append("</a>.</p>\n");
            }
            html.append("</section>\n");

            html.append("<section class=\"part implemented\"><h3><span class=\"part-name\">Implemented</span> ")
                    .append("<span class=\"part-by\">by the validator · ")
                    .append(escape(date.validator().tag())).append(newValidator ? ""
                            : ", in force since " + date.validator().effectiveDate())
                    .append("</span></h3>\n").append(implemented(report, date, latest)).append("</section>\n");
            html.append("</div></details>\n");
            latest = false;
        }
        return html.toString();
    }

    /** Per syntax and component, the rules that implement other codes than declared, each in a block. */
    private String implemented(ValidatorComparison.Report report, DatePoint date, boolean open) {
        var html = new StringBuilder();
        boolean opened = false;
        for (Syntax syntax : Syntax.values()) {
            var rules = rulesOf(report, date, syntax);
            for (String component : List.of("genericode", "spreadsheet")) {
                Function<RuleComparison, Side> side = side(component);
                Totals totals = Totals.of(rules, side);
                String title = syntax + " ⇄ " + (component.equals("genericode") ? "Genericode" : "spreadsheet");
                String id = anchor(date, syntax, component);
                if (totals.compared() == 0) {
                    html.append("<p class=\"block-none\" id=\"").append(id).append("\"><span class=\"block-title\">")
                            .append(escape(title)).append("</span> <span class=\"gist\">")
                            .append(escape(date.codeLists().directory())).append(" publishes no ")
                            .append(component.equals("genericode") ? "Genericode files" : "spreadsheet")
                            .append("</span></p>\n");
                    continue;
                }
                int notCompared = (int) rules.stream().filter(rule -> side.apply(rule) == null).count();
                String uncompared = notCompared == 0 ? "" : "; " + notCompared + (notCompared == 1 ? " rule has"
                        : " rules have") + " no counterpart";
                if (totals.disagreeing() == 0) {
                    html.append("<p class=\"block-none\" id=\"").append(id).append("\"><span class=\"block-title\">")
                            .append(escape(title)).append("</span> ").append(chip("ok", "all " + totals.compared()
                                    + " rules agree", null, null))
                            .append("<span class=\"gist\">every rule implements exactly the listed codes")
                            .append(escape(uncompared)).append("</span></p>\n");
                    continue;
                }
                var rows = new StringBuilder();
                for (RuleComparison rule : rules) {
                    appendSide(rows, rule, side.apply(rule));
                }
                String body = "<div class=\"scroll\"><table><thead><tr><th scope=\"col\">Rule</th>"
                        + "<th scope=\"col\">Code list</th><th scope=\"col\">Compared with</th>"
                        + "<th scope=\"col\">Codes that differ</th></tr></thead><tbody>\n" + rows + "</tbody></table></div>\n";
                String gist = (totals.onlyInValidator() + totals.onlyPublished()) + " codes differ: "
                        + totals.onlyInValidator() + " implemented but not published, " + totals.onlyPublished()
                        + " published but not implemented" + uncompared;
                html.append(block(id, open && !opened, title, chip("bad", totals.disagreeing() + " of "
                        + totals.compared() + " rules differ", null, null), escape(gist), "implemented", body));
                opened = true;
            }
            for (RuleComparison rule : rules) {
                if (rule.codeList().isEmpty()) {
                    html.append("<p class=\"block-none\">").append(syntax).append(" ").append(escape(rule.rule()))
                            .append(" (").append(rule.validatorCodes())
                            .append(" codes) is not in the rule catalogue, so it was not compared.</p>\n");
                }
            }
        }
        return html.toString();
    }

    /**
     * A collapsible block: a title, a status chip and the result in one line, the details and a link to how the
     * comparison works once opened.
     *
     * @param gist already escaped HTML
     * @param help the section of the explanation page, or {@code null}
     */
    public static String block(String id, boolean open, String title, String chip, String gist, String help,
            String body) {
        return "<details class=\"block\" id=\"" + escape(id) + "\"" + (open ? " open" : "") + "><summary>"
                + "<span class=\"block-title\">" + escape(title) + "</span> " + chip + "<span class=\"gist\">" + gist
                + "</span></summary>\n<div class=\"block-body\">\n" + body + (help == null ? ""
                        : "<p class=\"more\"><a href=\"" + ABOUT_PAGE + "#" + escape(help)
                                + "\">How this is compared</a></p>\n")
                + "</div></details>\n";
    }

    /**
     * A status: {@code ok}, {@code bad} or {@code none}, linked to {@code href} when set.
     *
     * @param title a tooltip, or {@code null}
     */
    public static String chip(String state, String text, String href, String title) {
        String mark = switch (state) {
            case "ok" -> "✓ ";
            case "bad" -> "✗ ";
            default -> "";
        };
        String attributes = " class=\"chip " + state + "\"" + (title == null ? "" : " title=\"" + escape(title) + "\"");
        return href == null ? "<span" + attributes + ">" + mark + escape(text) + "</span>"
                : "<a" + attributes + " href=\"" + escape(href) + "\">" + mark + escape(text) + "</a>";
    }

    /** A link to the English Wikipedia article on a change, or nothing when there is none. */
    public static String wikipedia(Successions.Pair pair) {
        return pair.url().isEmpty() ? ""
                : "<a class=\"wiki\" href=\"" + escape(pair.url()) + "\" target=\"_blank\" rel=\"noopener\" title=\""
                        + escape("Wikipedia" + (pair.note().isEmpty() ? "" : ": " + pair.note())) + "\">Wikipedia</a>";
    }

    /**
     * Pairs of a removed and an added code as {@code ANG → XCG}, one line each; a split on one line
     * ({@code AN → BQ CW SX}); more than four pairs of a kind without a source, such as codes changing case, on one line.
     */
    public static String successionLines(List<Successions.Pair> pairs) {
        return successionLines(pairs, ValidatorReport::escape, ValidatorReport::escape);
    }

    /**
     * @param oldCode renders a code that left, such as a link to the line that listed it
     * @param newCode renders a code that came
     */
    public static String successionLines(List<Successions.Pair> pairs, Function<String, String> oldCode,
            Function<String, String> newCode) {
        var lines = new StringBuilder();
        var byOld = new java.util.LinkedHashMap<String, List<Successions.Pair>>();
        var many = new java.util.LinkedHashMap<String, List<Successions.Pair>>();
        pairs.stream().collect(Collectors.groupingBy(Successions.Pair::kind, java.util.LinkedHashMap::new,
                Collectors.toList())).forEach((kind, group) -> {
                    if (group.size() > 4 && group.get(0).url().isEmpty()) {
                        many.put(kind, group);
                    } else {
                        group.forEach(pair -> byOld.computeIfAbsent(pair.oldCode(), key -> new ArrayList<>()).add(pair));
                    }
                });
        byOld.forEach((old, group) -> lines.append("<div class=\"reworded\"><code>").append(oldCode.apply(old))
                .append("</code> → <code>").append(group.stream().map(pair -> newCode.apply(pair.newCode()))
                        .collect(Collectors.joining(" "))).append("</code> <span class=\"quiet\">")
                .append(escape(group.get(0).when())).append("</span> ").append(wikipedia(group.get(0))).append("</div>"));
        many.forEach((kind, group) -> lines.append("<div class=\"reworded\">").append(group.size()).append(" codes ")
                .append(escape(kind)).append(": <code>").append(escape(group.stream().limit(6)
                        .map(pair -> pair.oldCode() + " → " + pair.newCode()).collect(Collectors.joining(", "))))
                .append(group.size() > 6 ? " …" : "").append("</code></div>"));
        return lines.toString();
    }

    /** {@code 17} for {@code 17_2026-05-15}. */
    private static String release(String directory) {
        return directory.replaceFirst("_.*", "");
    }

    private static String version(String tag) {
        return tag.replaceFirst("^validation-", "");
    }

    /** {@code d2026-05-15-ubl-genericode}: the block of one date, syntax and component. */
    private static String anchor(DatePoint date, Syntax syntax, String component) {
        return "d" + date.effectiveDate() + "-" + syntax.directory() + "-" + component;
    }

    private void appendSide(StringBuilder rows, RuleComparison rule, Side side) {
        if (side == null || side.agrees()) {
            return;
        }
        rows.append("<tr><td>").append(escape(rule.rule())).append("</td><td class=\"list\">")
                .append(escape(rule.codeList())).append("</td><td class=\"list quiet\" title=\"")
                .append(escape(side.source())).append("\">").append(escape(side.source().replaceFirst(".*/", "")))
                .append("</td>").append(differences(rule, side)).append("</tr>\n");
    }

    /**
     * Every differing code on a line of its own, in code order, with its name and what differs about it. A code the
     * standard renamed stands on one line with its successor ({@code STD → STN}), with a link to the change.
     */
    private String differences(RuleComparison rule, Side side) {
        Function<String, String> names = code -> side.names().containsKey(code) ? side.names().get(code).name() : null;
        // The validator kept the old code, or already has the new one while the published list kept the old.
        var stale = successions.pairs(rule.codeList(), side.onlyInValidator(), side.onlyPublished(), names);
        var staleCodes = Successions.codes(stale);
        var ahead = successions.pairs(rule.codeList(), side.onlyPublished(), side.onlyInValidator(), names).stream()
                .filter(pair -> !staleCodes.contains(pair.oldCode()) && !staleCodes.contains(pair.newCode())).toList();
        var groups = new java.util.LinkedHashMap<String, List<Successions.Pair>>();
        stale.forEach(pair -> groups.computeIfAbsent(pair.oldCode(), key -> new ArrayList<>()).add(pair));
        ahead.forEach(pair -> groups.computeIfAbsent(pair.oldCode(), key -> new ArrayList<>()).add(pair));
        var paired = new java.util.HashSet<String>(staleCodes);
        paired.addAll(Successions.codes(ahead));

        var codes = new java.util.TreeMap<String, Boolean>(
                org.standict.codelist.normalize.GenericodeNormalizer.CODE_ORDER);
        side.onlyInValidator().forEach(code -> codes.put(code, true));
        side.onlyPublished().forEach(code -> codes.put(code, false));
        var lines = new StringBuilder("<td class=\"differences\"><ul>");
        int shown = 0;
        int remaining = codes.size();
        String where = rule.rule() + " in " + rule.syntax().fileName() + " of " + rule.validatorTag() + commit(rule);
        for (var entry : codes.entrySet()) {
            String code = entry.getKey();
            if (paired.contains(code) && !groups.containsKey(code)) {
                continue; // The new code of a pair, on its old code's line.
            }
            if (shown++ == CODES_SHOWN) {
                lines.append("<li class=\"quiet\">… ").append(remaining)
                        .append(" more in <a href=\"rules.csv\">rules.csv</a></li>");
                break;
            }
            boolean implemented = entry.getValue();
            if (groups.containsKey(code)) {
                var group = groups.get(code);
                remaining -= 1 + group.size();
                lines.append(successionLine(rule, side, group, implemented, where, sources));
                continue;
            }
            remaining--;
            var description = side.names().get(code);
            String link = rule.link(code);
            Sources.Spot published = sources.published(side.source(), code);
            String kind = implemented
                    ? linked("implemented", link, code + " is listed by " + where + ", line "
                            + rule.lines().getOrDefault(code, 0)) + ", " + linked("not published", published)
                    : linked("published", published) + ", " + linked("not implemented", link, code
                            + " is missing from the list of " + where + ", which starts on line " + rule.listLine());
            String provenance = description == null || description.from().isEmpty() ? ""
                    : (description.from().compareTo(rule.codeListRelease()) < 0 ? "last listed in "
                            : "first listed in ") + description.from();
            String title = code + (description == null ? ": listed by no EU release of the " + rule.codeList()
                    + " code list in this comparison"
                    : ": " + description.name() + " (" + (provenance.isEmpty() ? side.source() : provenance) + ")");
            lines.append("<li class=\"").append(implemented ? "implemented" : "unimplemented").append("\" title=\"")
                    .append(escape(title)).append("\"><code>").append(escape(code)).append("</code> ");
            if (description != null) {
                lines.append("<span class=\"name\">(").append(escape(shorten(description.name())))
                        .append(provenance.isEmpty() ? "" : ", " + escape(provenance)).append(")</span> ");
            } else if (implemented) {
                lines.append("<span class=\"name quiet\">(listed by no EU release of ").append(escape(rule.codeList()))
                        .append(")</span> ");
            }
            lines.append("<span class=\"kind\">— ").append(kind);
            for (Successions.Pair successor : successions.successorsOf(rule.codeList(), code)) {
                lines.append("; ").append(escape(successor.kind())).append(" <code>").append(escape(successor.newCode()))
                        .append("</code>").append(escape(successor.year().isEmpty() ? "" : " in " + successor.year()))
                        .append(" ").append(wikipedia(successor));
            }
            lines.append("</span></li>");
        }
        return lines.append("</ul></td>").toString();
    }

    /**
     * {@code STD → STN (Dobra) — renamed in 2018 Wikipedia; the validator implements the old code, not the new}: one
     * line for an old code and its successors, linking each to where the validator lists it or would list it.
     */
    private static String successionLine(RuleComparison rule, Side side, List<Successions.Pair> group,
            boolean oldImplemented, String where, Sources sources) {
        Successions.Pair first = group.get(0);
        String old = first.oldCode();
        var successors = group.stream().map(Successions.Pair::newCode).toList();
        var description = successors.size() == 1 ? side.names().get(successors.get(0)) : null;
        // The code the published list has links to where it is published; the other one to the validator's line.
        Function<String, String> render = code -> oldImplemented == code.equals(old) ? escape(code)
                : linked(escape(code), sources.published(side.source(), code));
        String line = "<li class=\"renamed\" title=\"" + escape(old + " → " + String.join(" ", successors) + ": "
                + first.description()) + "\"><code>" + render.apply(old) + "</code> → <code>"
                + successors.stream().map(render).collect(Collectors.joining(" ")) + "</code> ";
        if (description != null) {
            line += "<span class=\"name\">(" + escape(shorten(description.name())) + ")</span> ";
        }
        String oldLink = rule.link(old);
        String newLink = rule.link(successors.get(0));
        String state = oldImplemented
                ? "the validator " + linked("implements", oldLink, old + " is listed by " + where + ", line "
                        + rule.lines().getOrDefault(old, 0)) + " the old code, " + linked("not the new", newLink,
                                String.join(" ", successors) + " is missing from the list of " + where
                                        + ", which starts on line " + rule.listLine())
                : "the validator " + linked("implements", newLink, String.join(" ", successors) + " is listed by "
                        + where + ", line " + rule.lines().getOrDefault(successors.get(0), 0))
                        + " the new code; the published list still has the old";
        return line + "<span class=\"kind\">— " + escape(first.when()) + " " + wikipedia(first) + "; " + state
                + "</span></li>";
    }

    private static String shorten(String name) {
        return name.length() > 70 ? name.substring(0, 69) + "…" : name;
    }

    /** {@code html} as a link to where a {@link Sources.Spot} is written, or plain when there is none. */
    public static String linked(String html, Sources.Spot spot) {
        return spot == null ? html : linked(html, spot.url(), spot.title());
    }

    /** {@code  at commit b6c9e06}: the commit a rule's link points to, or nothing without a link. */
    private static String commit(RuleComparison rule) {
        var matcher = java.util.regex.Pattern.compile("/blob/([0-9a-f]{7})[0-9a-f]{33}/").matcher(
                rule.sourceUrl() == null ? "" : rule.sourceUrl());
        return matcher.find() ? " at commit " + matcher.group(1) : "";
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
