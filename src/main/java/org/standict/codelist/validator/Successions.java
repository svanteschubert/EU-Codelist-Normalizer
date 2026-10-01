package org.standict.codelist.validator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Tells a removed code and an added code apart from two unrelated changes when the one replaced the other: the São Tomé
 * dobra STD that ISO 4217 renamed STN, the Netherlands Antilles AN that split into BQ, CW and SX, or the ICD code
 * {@code 01'00} that a later release spelled {@code 0100}.
 *
 * <p>The renames of the standards behind the code lists come from {@code code-successions.csv}, because the old code is
 * often in no EU release and therefore has no name to match. Codes that are equal but for punctuation and case, and
 * codes that carry the same name, are paired without it.
 */
public final class Successions {
    private static final String RESOURCE = "/validator/code-successions.csv";

    /**
     * One code replacing another.
     *
     * @param kind {@code renamed}, {@code replaced by}, {@code split into}, {@code spelling corrected} or
     *     {@code renamed (same name)}
     * @param year the year it took effect at the source, or empty when the pair was recognised rather than listed
     * @param url the English Wikipedia article on the change, or empty
     */
    public record Pair(String oldCode, String newCode, String kind, String year, String url, String note) {
        /** {@code renamed in 2018 (São Tomé and Príncipe dobra, redenominated)}. */
        public String description() {
            return kind + (kind.endsWith(" by") || kind.endsWith(" into") ? " " + newCode : "")
                    + (year.isEmpty() ? "" : " in " + year) + (note.isEmpty() ? "" : " (" + note + ")");
        }

        /** {@code replaced in 2026}: the change, for a line that already shows {@code BGN → EUR}. */
        public String when() {
            return kind.replaceFirst(" (by|into)$", "") + (year.isEmpty() ? "" : " in " + year);
        }
    }

    private final Map<String, List<Pair>> byList;

    Successions(Map<String, List<Pair>> byList) {
        this.byList = byList;
    }

    public static Successions load() throws IOException {
        var byList = new HashMap<String, List<Pair>>();
        for (List<String> row : ValidatorCatalog.rows(RESOURCE, 7)) {
            byList.computeIfAbsent(row.get(0), key -> new ArrayList<>())
                    .add(new Pair(row.get(1), row.get(2), row.get(3), row.get(4), row.get(5), row.get(6)));
        }
        return new Successions(byList);
    }

    /** The listed successors of {@code code}, such as EUR for HRK; empty when it has none. */
    public List<Pair> successorsOf(String codeList, String code) {
        return byList.getOrDefault(codeList, List.of()).stream().filter(pair -> pair.oldCode().equals(code)).toList();
    }

    /**
     * Pairs codes that left a list with codes that came into it, in the order of {@code gone}. A code that came is paired
     * at most once; a code that left more than once only when it was split.
     *
     * @param names the name of a code, or {@code null} when unknown
     */
    public List<Pair> pairs(String codeList, Collection<String> gone, Collection<String> come,
            Function<String, String> names) {
        var pairs = new ArrayList<Pair>();
        var taken = new HashSet<String>();
        var paired = new HashSet<String>();
        for (String old : gone) {
            for (Pair listed : successorsOf(codeList, old)) {
                if (come.contains(listed.newCode()) && taken.add(listed.newCode())) {
                    pairs.add(listed);
                    paired.add(old);
                }
            }
        }
        for (String old : gone) {
            if (paired.contains(old)) {
                continue;
            }
            for (String now : come) {
                if (!taken.contains(now) && !old.equals(now) && spelling(old).equals(spelling(now))) {
                    pairs.add(new Pair(old, now, "spelling corrected", "", "", ""));
                    taken.add(now);
                    paired.add(old);
                    break;
                }
            }
        }
        var byName = new HashMap<String, List<String>>();
        come.stream().filter(code -> !taken.contains(code)).forEach(code -> name(names, code)
                .ifPresent(name -> byName.computeIfAbsent(name, key -> new ArrayList<>()).add(code)));
        var goneByName = new HashMap<String, List<String>>();
        gone.stream().filter(code -> !paired.contains(code)).forEach(code -> name(names, code)
                .ifPresent(name -> goneByName.computeIfAbsent(name, key -> new ArrayList<>()).add(code)));
        for (String old : gone) {
            var name = paired.contains(old) ? java.util.Optional.<String>empty() : name(names, old);
            if (name.isEmpty()) {
                continue;
            }
            // Only one code on each side may carry the name: "Name not known" pairs nothing.
            List<String> candidates = byName.getOrDefault(name.get(), List.of());
            if (candidates.size() == 1 && goneByName.get(name.get()).size() == 1) {
                pairs.add(new Pair(old, candidates.get(0), "renamed (same name)", "", "", ""));
            }
        }
        return pairs;
    }

    /** The codes of {@code pairs} that left or came, so that a caller lists only the rest code by code. */
    public static Set<String> codes(List<Pair> pairs) {
        var codes = new HashSet<String>();
        pairs.forEach(pair -> {
            codes.add(pair.oldCode());
            codes.add(pair.newCode());
        });
        return codes;
    }

    private static String spelling(String code) {
        return code.replaceAll("[^\\p{Alnum}]", "").toUpperCase(Locale.ROOT);
    }

    private static java.util.Optional<String> name(Function<String, String> names, String code) {
        String name = names.apply(code);
        return name == null || name.isBlank() ? java.util.Optional.empty()
                : java.util.Optional.of(name.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT));
    }
}
