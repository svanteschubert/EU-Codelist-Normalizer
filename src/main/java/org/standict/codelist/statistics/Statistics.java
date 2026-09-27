package org.standict.codelist.statistics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.standict.codelist.statistics.Canonical.Component;
import org.standict.codelist.statistics.Canonical.Role;

/**
 * Answers two questions about the published code lists, from the canonical form of every component.
 *
 * <p><strong>What changed from one effective date to the next</strong>, per component. An implementer preparing for a
 * date needs this, and needs it per component, because the two are published separately and do not always agree.
 *
 * <p><strong>Whether the components agree within one delivery.</strong> The same code list is published as Genericode
 * and as a spreadsheet; where they disagree, an implementation is correct against one and wrong against the other, and
 * nothing in the delivery says which is authoritative. A code list carried by only one component is reported too,
 * rather than quietly skipped.
 *
 * <p>A third component, the validation artefacts, fits without changing any of this: it becomes another
 * {@link Component} whose rows are read into the same canonical form.
 */
public final class Statistics {
    private static final Pattern REVISION = Pattern.compile("^(.*)_revision([0-9]{2,})$");

    /** One code list compared between two deliveries of one component. */
    public record Change(String from, String to, Component component, String codeList, int added, int removed,
            int reworded, int unchanged) {
        public int total() {
            return added + removed + reworded;
        }
    }

    /** One code list compared between two components of one delivery. */
    public record Agreement(String effectiveDate, String codeList, int onlyInGenericode, int onlyInSpreadsheet,
            int namesDiffer, int agree) {
        public int disagreements() {
            return onlyInGenericode + onlyInSpreadsheet + namesDiffer;
        }
    }

    /** A code list published by one component only, for the whole delivery. */
    public record Missing(String effectiveDate, String codeList, Component present) {}

    /** A code list published twice by one component of one delivery, in two different artefacts. */
    public record Duplicated(String effectiveDate, String codeList) {}

    public record Report(List<String> effectiveDates, List<Change> changes, List<Agreement> agreements,
            List<Missing> missing, List<Duplicated> duplicated) {}

    public Report analyse(Path normalizedRoot) throws IOException {
        List<String> deliveries = deliveries(normalizedRoot);
        var perDelivery = new LinkedHashMap<String, Map<Component, Map<String, Map<String, Map<Role, String>>>>>();
        var duplicated = new ArrayList<Duplicated>();
        for (String delivery : deliveries) {
            var duplicates = new TreeSet<String>();
            perDelivery.put(delivery, read(normalizedRoot.resolve(delivery), duplicates));
            duplicates.forEach(list -> duplicated.add(new Duplicated(delivery, list)));
        }
        var changes = new ArrayList<Change>();
        for (int i = 1; i < deliveries.size(); i++) {
            String from = deliveries.get(i - 1);
            String to = deliveries.get(i);
            for (Component component : Component.values()) {
                var before = perDelivery.get(from).getOrDefault(component, Map.of());
                var after = perDelivery.get(to).getOrDefault(component, Map.of());
                for (String codeList : new TreeSet<>(before.keySet())) {
                    if (!after.containsKey(codeList)) {
                        continue; // A list that disappears is a delivery-level change, reported as missing instead.
                    }
                    changes.add(compare(from, to, component, codeList, before.get(codeList), after.get(codeList)));
                }
            }
        }
        var agreements = new ArrayList<Agreement>();
        var missing = new ArrayList<Missing>();
        for (String delivery : deliveries) {
            var components = perDelivery.get(delivery);
            var genericode = components.getOrDefault(Component.GENERICODE, Map.of());
            var spreadsheet = components.getOrDefault(Component.SPREADSHEET, Map.of());
            if (genericode.isEmpty() || spreadsheet.isEmpty()) {
                continue; // Only one component was published at all; there is nothing to reconcile.
            }
            var lists = new TreeSet<String>();
            lists.addAll(genericode.keySet());
            lists.addAll(spreadsheet.keySet());
            for (String codeList : lists) {
                if (!genericode.containsKey(codeList)) {
                    missing.add(new Missing(delivery, codeList, Component.SPREADSHEET));
                } else if (!spreadsheet.containsKey(codeList)) {
                    missing.add(new Missing(delivery, codeList, Component.GENERICODE));
                } else {
                    agreements.add(agreement(delivery, codeList, genericode.get(codeList), spreadsheet.get(codeList)));
                }
            }
        }
        return new Report(deliveries, List.copyOf(changes), List.copyOf(agreements), List.copyOf(missing),
                List.copyOf(duplicated));
    }

    private Change compare(String from, String to, Component component, String codeList,
            Map<String, Map<Role, String>> before, Map<String, Map<Role, String>> after) {
        int added = 0;
        int removed = 0;
        int reworded = 0;
        int unchanged = 0;
        var codes = new TreeSet<String>();
        codes.addAll(before.keySet());
        codes.addAll(after.keySet());
        for (String code : codes) {
            var left = before.get(code);
            var right = after.get(code);
            if (left == null) {
                added++;
            } else if (right == null) {
                removed++;
            } else if (left.equals(right)) {
                unchanged++;
            } else {
                reworded++;
            }
        }
        return new Change(from, to, component, codeList, added, removed, reworded, unchanged);
    }

    /** Compares by role, because the two components label the same column differently. */
    private Agreement agreement(String delivery, String codeList, Map<String, Map<Role, String>> genericode,
            Map<String, Map<Role, String>> spreadsheet) {
        int onlyGenericode = 0;
        int onlySpreadsheet = 0;
        int namesDiffer = 0;
        int agree = 0;
        var codes = new TreeSet<String>();
        codes.addAll(genericode.keySet());
        codes.addAll(spreadsheet.keySet());
        for (String code : codes) {
            var left = genericode.get(code);
            var right = spreadsheet.get(code);
            if (right == null) {
                onlyGenericode++;
            } else if (left == null) {
                onlySpreadsheet++;
            } else if (!normalizeName(left.get(Role.NAME)).equals(normalizeName(right.get(Role.NAME)))) {
                namesDiffer++;
            } else {
                agree++;
            }
        }
        return new Agreement(delivery, codeList, onlyGenericode, onlySpreadsheet, namesDiffer, agree);
    }

    /**
     * Collapses whitespace before comparing a name across components. The spreadsheet and the Genericode file wrap and
     * indent their text differently, and a report of line-break differences would bury the ones that matter.
     */
    private static String normalizeName(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    /** Reads every component of one delivery into {@code component -> code list -> code -> role -> value}. */
    private Map<Component, Map<String, Map<String, Map<Role, String>>>> read(Path delivery,
            java.util.Collection<String> duplicates) throws IOException {
        var result = new EnumMap<Component, Map<String, Map<String, Map<Role, String>>>>(Component.class);
        Canonical.Catalog catalog = Canonical.catalog();
        for (Path artefact : highestRevisions(delivery)) {
            try (var paths = Files.walk(artefact)) {
                for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                    String name = file.getFileName().toString();
                    String lower = name.toLowerCase(Locale.ROOT);
                    List<Canonical.Row> rows;
                    String codeList;
                    if (lower.endsWith(".gc")) {
                        codeList = name.substring(0, name.length() - 3);
                        rows = Canonical.readGenericode(file, codeList);
                    } else if (lower.endsWith(".csv")) {
                        String sheet = name.substring(0, name.length() - 4);
                        if (!catalog.isCodeList(sheet)) {
                            continue;
                        }
                        codeList = catalog.codeListOf(sheet);
                        rows = Canonical.readSpreadsheet(file, codeList);
                    } else {
                        continue;
                    }
                    if (rows.isEmpty()) {
                        continue;
                    }
                    var lists = result.computeIfAbsent(rows.get(0).component(), key -> new TreeMap<>());
                    if (lists.containsKey(codeList)) {
                        // Two artefacts of one component publish the same list -- the EN16931 workbook has an EAS
                        // sheet and the standalone EAS workbook holds the same list. Keep the first and record the
                        // second: concatenating their values would make every code differ from the other component.
                        duplicates.add(codeList);
                        continue;
                    }
                    var byCode = lists.computeIfAbsent(codeList, key -> new TreeMap<String, Map<Role, String>>());
                    for (Canonical.Row row : rows) {
                        byCode.computeIfAbsent(row.code(), key -> new EnumMap<>(Role.class))
                                .merge(row.role(), row.value(), (first, second) -> first + " | " + second);
                    }
                }
            }
        }
        return result;
    }

    /** A delivery's effective content is the highest revision of each artefact. */
    private List<Path> highestRevisions(Path delivery) throws IOException {
        if (!Files.isDirectory(delivery)) {
            return List.of();
        }
        var highest = new TreeMap<String, Map.Entry<Integer, Path>>();
        try (var entries = Files.list(delivery)) {
            for (Path artefact : entries.filter(Files::isDirectory).sorted().toList()) {
                Matcher matcher = REVISION.matcher(artefact.getFileName().toString());
                String base = matcher.matches() ? matcher.group(1) : artefact.getFileName().toString();
                int revision = matcher.matches() ? Integer.parseInt(matcher.group(2)) : 1;
                var current = highest.get(base);
                if (current == null || revision > current.getKey()) {
                    highest.put(base, Map.entry(revision, artefact));
                }
            }
        }
        return highest.values().stream().map(Map.Entry::getValue).toList();
    }

    private List<String> deliveries(Path normalizedRoot) throws IOException {
        if (!Files.isDirectory(normalizedRoot)) {
            throw new IOException("No normalized tree at " + normalizedRoot);
        }
        try (var entries = Files.list(normalizedRoot)) {
            return entries.filter(Files::isDirectory).map(path -> path.getFileName().toString())
                    .sorted(Comparator.naturalOrder()).toList();
        }
    }
}
