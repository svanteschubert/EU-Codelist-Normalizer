package org.standict.codelist.compare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reports what changed in the code lists from one delivery to the next.
 *
 * <p>Two comparisons come out of the same data and answer different questions. Between consecutive effective dates:
 * what did the Commission change in this delivery — which is what an implementer preparing for a date needs. Within one
 * delivery, between the revisions of a corrected artefact: what did the correction actually correct — which is the
 * question nobody can answer today, because the superseded publication normally disappears.
 *
 * <p>A delivery's effective content is the highest revision of each artefact, so that is what the
 * delivery-to-delivery comparison uses.
 */
public final class DeliveryComparison {
    /** {@code <artefact>_revisionNN}, the suffix {@code DeliveryLayout} gives a corrected artefact. */
    private static final Pattern REVISION = Pattern.compile("^(.*)_revision([0-9]{2,})$");

    private final CodeListReader reader = new CodeListReader();
    private final CodeListDiff diff = new CodeListDiff();

    /** One comparison of one code list, named by where both sides came from. */
    public record Comparison(String from, String to, String codeList, CodeListDiff.Result result) {}

    public record Summary(int deliveryPairs, int corrections, int codeLists, int added, int removed, int changed) {}

    /**
     * @param normalizedRoot the {@code normalized/} tree
     * @param comparedRoot receives one CSV per comparison plus {@code summary.csv}
     */
    public Summary compare(Path normalizedRoot, Path comparedRoot) throws IOException {
        List<String> deliveries = deliveries(normalizedRoot);
        var comparisons = new ArrayList<Comparison>();
        int corrections = 0;
        for (String delivery : deliveries) {
            corrections += compareRevisionsWithin(normalizedRoot, delivery, comparisons);
        }
        for (int i = 1; i < deliveries.size(); i++) {
            String from = deliveries.get(i - 1);
            String to = deliveries.get(i);
            Map<String, Path> before = effectiveCodeLists(normalizedRoot, from);
            Map<String, Path> after = effectiveCodeLists(normalizedRoot, to);
            var names = new TreeSet<String>();
            names.addAll(before.keySet());
            names.addAll(after.keySet());
            for (String name : names) {
                if (!before.containsKey(name) || !after.containsKey(name)) {
                    continue; // A code list that appears or disappears is a delivery-level change, not a code change.
                }
                var result = diff.compare(reader.read(before.get(name)), reader.read(after.get(name)));
                if (!result.isEmpty()) {
                    comparisons.add(new Comparison(from, to, name, result));
                }
            }
        }
        write(comparisons, comparedRoot);
        return new Summary(Math.max(0, deliveries.size() - 1), corrections,
                (int) comparisons.stream().map(Comparison::codeList).distinct().count(),
                comparisons.stream().mapToInt(c -> c.result().added()).sum(),
                comparisons.stream().mapToInt(c -> c.result().removed()).sum(),
                comparisons.stream().mapToInt(c -> c.result().changed()).sum());
    }

    /** Compares each corrected artefact against the publication it replaced, inside one delivery. */
    private int compareRevisionsWithin(Path normalizedRoot, String delivery, List<Comparison> comparisons)
            throws IOException {
        var revisions = new TreeMap<String, TreeMap<Integer, Path>>();
        for (Path artefact : artefacts(normalizedRoot.resolve(delivery))) {
            String folder = artefact.getFileName().toString();
            Matcher matcher = REVISION.matcher(folder);
            String base = matcher.matches() ? matcher.group(1) : folder;
            int revision = matcher.matches() ? Integer.parseInt(matcher.group(2)) : 1;
            revisions.computeIfAbsent(base, key -> new TreeMap<>()).put(revision, artefact);
        }
        int corrections = 0;
        for (var artefact : revisions.entrySet()) {
            var byRevision = artefact.getValue();
            if (byRevision.size() < 2) {
                continue;
            }
            var numbers = new ArrayList<>(byRevision.keySet());
            for (int i = 1; i < numbers.size(); i++) {
                Path before = byRevision.get(numbers.get(i - 1));
                Path after = byRevision.get(numbers.get(i));
                corrections++;
                for (String name : new TreeSet<>(codeLists(before).keySet())) {
                    Path right = codeLists(after).get(name);
                    if (right == null) {
                        continue;
                    }
                    var result = diff.compare(reader.read(codeLists(before).get(name)), reader.read(right));
                    if (!result.isEmpty()) {
                        comparisons.add(new Comparison(
                                delivery + "/" + artefact.getKey() + " r" + numbers.get(i - 1),
                                delivery + "/" + artefact.getKey() + " r" + numbers.get(i), name, result));
                    }
                }
            }
        }
        return corrections;
    }

    /** Effective dates present in the tree, in chronological order. */
    private List<String> deliveries(Path normalizedRoot) throws IOException {
        if (!Files.isDirectory(normalizedRoot)) {
            throw new IOException("No normalized tree at " + normalizedRoot);
        }
        try (var entries = Files.list(normalizedRoot)) {
            return entries.filter(Files::isDirectory).map(path -> path.getFileName().toString())
                    .sorted(Comparator.naturalOrder()).toList();
        }
    }

    private List<Path> artefacts(Path delivery) throws IOException {
        if (!Files.isDirectory(delivery)) {
            return List.of();
        }
        try (var entries = Files.list(delivery)) {
            return entries.filter(Files::isDirectory).sorted().toList();
        }
    }

    /** The code lists of one delivery, taking the highest revision of each artefact as its effective content. */
    private Map<String, Path> effectiveCodeLists(Path normalizedRoot, String delivery) throws IOException {
        var highest = new LinkedHashMap<String, Integer>();
        var result = new LinkedHashMap<String, Path>();
        for (Path artefact : artefacts(normalizedRoot.resolve(delivery))) {
            Matcher matcher = REVISION.matcher(artefact.getFileName().toString());
            int revision = matcher.matches() ? Integer.parseInt(matcher.group(2)) : 1;
            for (var codeList : codeLists(artefact).entrySet()) {
                if (revision >= highest.getOrDefault(codeList.getKey(), 0)) {
                    highest.put(codeList.getKey(), revision);
                    result.put(codeList.getKey(), codeList.getValue());
                }
            }
        }
        return result;
    }

    private Map<String, Path> codeLists(Path artefact) throws IOException {
        var result = new LinkedHashMap<String, Path>();
        if (!Files.isDirectory(artefact)) {
            return result;
        }
        try (var paths = Files.walk(artefact)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gc")) {
                    result.put(path.getFileName().toString(), path);
                }
            }
        }
        return result;
    }

    private void write(List<Comparison> comparisons, Path comparedRoot) throws IOException {
        Files.createDirectories(comparedRoot);
        var summary = new StringBuilder("\"from\",\"to\",\"code list\",\"added\",\"removed\",\"changed\",\"unchanged\"\n");
        for (Comparison comparison : comparisons) {
            var rows = new StringBuilder("\"change\",\"code\",\"column\",\"before\",\"after\"\n");
            for (CodeListDiff.Entry entry : comparison.result().entries()) {
                rows.append(csv(entry.change().name().toLowerCase(Locale.ROOT), entry.code(), entry.column(),
                        entry.before(), entry.after()));
            }
            Path report = comparedRoot.resolve(slug(comparison.from()) + "__" + slug(comparison.to()))
                    .resolve(comparison.codeList().replaceFirst("(?i)\\.gc$", "") + ".csv");
            Files.createDirectories(report.getParent());
            Files.writeString(report, rows.toString(), StandardCharsets.UTF_8);
            summary.append(csv(comparison.from(), comparison.to(), comparison.codeList(),
                    Integer.toString(comparison.result().added()), Integer.toString(comparison.result().removed()),
                    Integer.toString(comparison.result().changed()),
                    Integer.toString(comparison.result().unchanged())));
        }
        Files.writeString(comparedRoot.resolve("summary.csv"), summary.toString(), StandardCharsets.UTF_8);
    }

    private static String slug(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]+", "-");
    }

    private static String csv(String... values) {
        var row = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                row.append(',');
            }
            row.append('"').append(values[i].replace("\"", "\"\"")).append('"');
        }
        return row.append('\n').toString();
    }
}
