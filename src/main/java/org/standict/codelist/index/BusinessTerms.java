package org.standict.codelist.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import org.standict.codelist.validator.ValidatorCatalog;

/**
 * The business terms of EN 16931-1:2017 that use each code list, and the check of the Index sheet's
 * {@code EN business terms where the code list is used.} column against them.
 *
 * <p>The Index is the only place a delivery names business terms: neither the Genericode files nor the code list
 * sheets carry any. Both are still searched for {@code BT-} references, so that a delivery that starts publishing them
 * is compared as well, instead of being ignored.
 */
public final class BusinessTerms {
    private static final String REFERENCE = "/validator/business-terms-2017.csv";
    private static final java.util.regex.Pattern EXPLICIT_TERM =
            java.util.regex.Pattern.compile("\\bBT-\\d+(?:-\\d+)?\\b");

    /** The 2017 business terms of one tab, and how Table 2 of EN 16931-1:2017 states them. */
    public record Reference(List<String> terms, String evidence) {}

    /**
     * The business terms of one tab in one release.
     *
     * @param reference {@code null} when the 2017 reference has no row for the tab
     * @param published business terms found in the tab's Genericode file or sheet, by file
     */
    public record Check(String tab, List<String> index, Reference reference, List<String> onlyInIndex,
            List<String> onlyIn2017, List<String> addedSincePrevious, List<String> removedSincePrevious,
            Map<String, List<String>> sharedWith, Map<String, List<String>> published) {
        public boolean differsFrom2017() {
            return reference == null || !onlyInIndex.isEmpty() || !onlyIn2017.isEmpty();
        }

        public boolean changedSincePrevious() {
            return !addedSincePrevious.isEmpty() || !removedSincePrevious.isEmpty();
        }
    }

    private final Map<String, Reference> reference;

    private BusinessTerms(Map<String, Reference> reference) {
        this.reference = reference;
    }

    public static BusinessTerms load() throws IOException {
        var reference = new LinkedHashMap<String, Reference>();
        for (List<String> row : ValidatorCatalog.rows(REFERENCE, 3)) {
            if (reference.put(row.get(0), new Reference(IndexSheet.businessTerms(row.get(1)), row.get(2))) != null) {
                throw new IOException("Tab listed twice in " + REFERENCE + ": " + row.get(0));
            }
        }
        return new BusinessTerms(Map.copyOf(reference));
    }

    public Optional<Reference> reference(String tab) {
        return Optional.ofNullable(reference.get(tab));
    }

    /**
     * Checks every row of one Index sheet.
     *
     * @param previous the Index of the previous release, or {@code null} for the first
     * @param published business terms found outside the Index, by tab and then by file
     */
    public List<Check> check(IndexSheet index, IndexSheet previous, Map<String, Map<String, List<String>>> published) {
        var byTerm = new TreeMap<String, List<String>>();
        for (IndexSheet.Entry entry : index.entries()) {
            entry.businessTerms().forEach(term -> byTerm.computeIfAbsent(term, key -> new ArrayList<>()).add(entry.tab()));
        }
        var checks = new ArrayList<Check>();
        for (IndexSheet.Entry entry : index.entries()) {
            Reference known = reference.get(entry.tab());
            List<String> expected = known == null ? List.of() : known.terms();
            List<String> before = previous == null ? entry.businessTerms() : previous.entries().stream()
                    .filter(candidate -> candidate.tab().equals(entry.tab())).findFirst()
                    .map(IndexSheet.Entry::businessTerms).orElse(List.of());
            var shared = new TreeMap<String, List<String>>(IndexSheet.BUSINESS_TERM_ORDER);
            for (String term : entry.businessTerms()) {
                var tabs = byTerm.get(term).stream().filter(tab -> !tab.equals(entry.tab())).toList();
                if (!tabs.isEmpty()) {
                    shared.put(term, tabs);
                }
            }
            checks.add(new Check(entry.tab(), entry.businessTerms(), known, minus(entry.businessTerms(), expected),
                    minus(expected, entry.businessTerms()), minus(entry.businessTerms(), before),
                    minus(before, entry.businessTerms()), shared,
                    published.getOrDefault(entry.tab(), Map.of())));
        }
        return checks;
    }

    /**
     * Searches the files of one component for business-term references, by the tab they belong to. The tab is the
     * file name without its extension, as for {@code Country.gc} and {@code Country.csv}; {@code skip} names files that
     * are not code lists, such as the Index itself.
     */
    public static Map<String, Map<String, List<String>>> search(Path directory, String extension, List<String> skip)
            throws IOException {
        var found = new TreeMap<String, Map<String, List<String>>>();
        if (directory == null || !Files.isDirectory(directory)) {
            return found;
        }
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(extension) || skip.contains(name)) {
                    continue;
                }
                // Only the spelled-out form counts here: "BT" alone is Bhutan in the Country list.
                var matcher = EXPLICIT_TERM.matcher(Files.readString(file, StandardCharsets.UTF_8));
                var explicit = new StringBuilder();
                while (matcher.find()) {
                    explicit.append(matcher.group()).append(' ');
                }
                List<String> terms = IndexSheet.businessTerms(explicit.toString());
                if (!terms.isEmpty()) {
                    found.computeIfAbsent(name.substring(0, name.length() - extension.length()),
                            key -> new TreeMap<>()).put(name, terms);
                }
            }
        }
        return found;
    }

    private static List<String> minus(List<String> left, List<String> right) {
        var difference = new TreeSet<String>(IndexSheet.BUSINESS_TERM_ORDER);
        difference.addAll(left);
        difference.removeAll(right);
        return List.copyOf(difference);
    }
}
