package org.standict.codelist.statistics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.standict.codelist.statistics.Canonical.Component;

/**
 * Renders a {@link Statistics.Report} as one self-contained HTML page.
 *
 * <p>Self-contained on purpose: the page is opened from a file, mailed to a colleague and attached to a defect report,
 * so it carries its own style and loads nothing from the network. The template lives in the resources next to the
 * spreadsheet catalogue, so the wording and layout can be changed without touching the analysis.
 */
public final class HtmlStatisticsReport {
    private static final String TEMPLATE = "/statistics/report.html";

    public void write(Statistics.Report report, Path destination) throws IOException {
        String template;
        try (InputStream stream = HtmlStatisticsReport.class.getResourceAsStream(TEMPLATE)) {
            if (stream == null) {
                throw new IOException("Missing resource " + TEMPLATE);
            }
            template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        Files.createDirectories(destination.getParent() == null ? Path.of(".") : destination.getParent());
        Files.writeString(destination, template
                .replace("{{deliveries}}", String.valueOf(report.effectiveDates().size()))
                .replace("{{first}}", report.effectiveDates().isEmpty() ? "-" : report.effectiveDates().get(0))
                .replace("{{last}}", report.effectiveDates().isEmpty() ? "-"
                        : report.effectiveDates().get(report.effectiveDates().size() - 1))
                .replace("{{changesByDate}}", changesByDate(report))
                .replace("{{changeDetail}}", changeDetail(report))
                .replace("{{agreement}}", agreement(report))
                .replace("{{missing}}", missing(report)), StandardCharsets.UTF_8);
    }

    /** One row per delivery step, one column pair per component, so the two can be read against each other. */
    private String changesByDate(Statistics.Report report) {
        var steps = new LinkedHashMap<String, Map<Component, int[]>>();
        for (Statistics.Change change : report.changes()) {
            steps.computeIfAbsent(change.from() + " → " + change.to(), key -> new java.util.EnumMap<>(Component.class))
                    .computeIfAbsent(change.component(), key -> new int[4]);
            int[] totals = steps.get(change.from() + " → " + change.to()).get(change.component());
            totals[0] += change.added();
            totals[1] += change.removed();
            totals[2] += change.reworded();
            totals[3] += change.total() > 0 ? 1 : 0;
        }
        var rows = new StringBuilder();
        for (var step : steps.entrySet()) {
            rows.append("<tr><th scope=\"row\">").append(escape(step.getKey())).append("</th>");
            for (Component component : List.of(Component.GENERICODE, Component.SPREADSHEET)) {
                int[] totals = step.getValue().get(component);
                if (totals == null) {
                    rows.append("<td class=\"absent\" colspan=\"4\">not published</td>");
                } else {
                    rows.append(cell(totals[0], "added")).append(cell(totals[1], "removed"))
                            .append(cell(totals[2], "reworded")).append(cell(totals[3], "lists"));
                }
            }
            rows.append("</tr>\n");
        }
        return rows.isEmpty() ? "<tr><td colspan=\"9\">No change between any two deliveries.</td></tr>" : rows.toString();
    }

    private String changeDetail(Statistics.Report report) {
        var rows = new StringBuilder();
        report.changes().stream().filter(change -> change.total() > 0)
                .sorted(Comparator.comparing(Statistics.Change::to).thenComparing(Statistics.Change::codeList)
                        .thenComparing(change -> change.component().name()))
                .forEach(change -> rows.append("<tr><td>").append(escape(change.from())).append("</td><td>")
                        .append(escape(change.to())).append("</td><td>").append(component(change.component()))
                        .append("</td><td class=\"list\">").append(escape(change.codeList())).append("</td>")
                        .append(cell(change.added(), "added")).append(cell(change.removed(), "removed"))
                        .append(cell(change.reworded(), "reworded"))
                        .append("<td class=\"quiet\">").append(change.unchanged()).append("</td></tr>\n"));
        return rows.isEmpty() ? "<tr><td colspan=\"8\">Nothing changed.</td></tr>" : rows.toString();
    }

    private String agreement(Statistics.Report report) {
        var rows = new StringBuilder();
        report.agreements().stream().filter(agreement -> agreement.disagreements() > 0)
                .sorted(Comparator.comparing(Statistics.Agreement::effectiveDate)
                        .thenComparing(Statistics.Agreement::codeList))
                .forEach(agreement -> rows.append("<tr><td>").append(escape(agreement.effectiveDate()))
                        .append("</td><td class=\"list\">").append(escape(agreement.codeList())).append("</td>")
                        .append(cell(agreement.onlyInGenericode(), "removed"))
                        .append(cell(agreement.onlyInSpreadsheet(), "added"))
                        .append(cell(agreement.namesDiffer(), "reworded"))
                        .append("<td class=\"quiet\">").append(agreement.agree()).append("</td></tr>\n"));
        return rows.isEmpty()
                ? "<tr><td colspan=\"6\">The two components agree on every code of every delivery.</td></tr>"
                : rows.toString();
    }

    private String missing(Statistics.Report report) {
        if (report.missing().isEmpty()) {
            return "<tr><td colspan=\"3\">Every code list is published by both components.</td></tr>";
        }
        var byList = report.missing().stream().collect(Collectors.groupingBy(
                missing -> missing.codeList() + "\u0000" + missing.present().name(),
                java.util.TreeMap::new, Collectors.toList()));
        var rows = new StringBuilder();
        byList.forEach((key, entries) -> {
            String[] parts = key.split("\u0000");
            rows.append("<tr><td class=\"list\">").append(escape(parts[0])).append("</td><td>")
                    .append(component(Component.valueOf(parts[1]))).append("</td><td class=\"quiet\">")
                    .append(entries.size()).append(" of ").append(report.effectiveDates().size())
                    .append(" deliveries</td></tr>\n");
        });
        return rows.toString();
    }

    private static String cell(int value, String kind) {
        return value == 0 ? "<td class=\"zero\">·</td>"
                : "<td class=\"" + kind + "\">" + value + "</td>";
    }

    private static String component(Component component) {
        return "<span class=\"component " + component.name().toLowerCase(Locale.ROOT) + "\">"
                + switch (component) {
                    case GENERICODE -> "Genericode";
                    case SPREADSHEET -> "spreadsheet";
                    case VALIDATOR -> "validator";
                } + "</span>";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
