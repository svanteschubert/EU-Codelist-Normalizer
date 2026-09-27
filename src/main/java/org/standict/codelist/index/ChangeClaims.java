package org.standict.codelist.index;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.standict.codelist.normalize.GenericodeNormalizer;

/**
 * What one row of the Index sheet claims changed: its {@code Changes} flag and its free-text {@code Remark on updates}.
 *
 * <p>The remarks are written by hand and read like it. The same statement comes in many shapes:
 *
 * <pre>
 * Added 0198,0199,0200,9901,AN,AQ,AS,AU,EM removed 9902,9904,9905    verbs before their codes
 * 0201 and 0202 added, 0100 and 0101 structure corrected             verbs after their codes
 * "AN" is split up into CW, SX and BQ                                 one code removed, three added
 * Adding 0221 to 230                                                   a range, the second code without its zero
 * Updated to Rec20r16+Rec21r11, adding 49 codes                        a count instead of codes
 * TR name changed to Türkiye                                           a changed name
 * </pre>
 *
 * <p>A word is taken for a code only when the list itself has that code, before or after the change, so that neither
 * {@code Sierra Leone} nor {@code Türkiye} is read as one. Matching ignores case and restores leading zeros the remark
 * dropped. A word that looks like a code but matches none -- {@code 2017} written for {@code 0217}, or
 * {@code VATEX-135-1} -- is kept as unresolved rather than dropped, because it is exactly what a reader must check.
 */
public record ChangeClaims(Flag flag, String flagText, String remark, Set<String> added, Set<String> removed,
        Set<String> reworded, Set<String> deprecated, boolean newList, boolean caseChange, List<Integer> counts,
        List<String> unresolved) {

    /** The {@code Changes} column: {@code Yes}, {@code No} (also {@code NO}), {@code Fixed}, or empty. */
    public enum Flag { YES, NO, FIXED, EMPTY, OTHER }

    private enum Verb { ADD, REMOVE, DEPRECATE, REWORD, SPLIT }

    private static final Pattern PARENTHESIS = Pattern.compile("\\(([^)]*)\\)");
    private static final Pattern SENTENCE = Pattern.compile("\\.(?=\\s|$)|;");
    private static final Pattern WORD = Pattern.compile("[\\p{L}0-9][\\p{L}0-9'\\-/]*|,");
    private static final Pattern COUNT = Pattern.compile("(?i)\\b(\\d+)\\s+(?:[\\p{L}-]+\\s+){0,2}codes\\b");
    private static final Pattern CODE_LIKE = Pattern.compile("[0-9A-Z][0-9A-Z'\\-/]*|(?i)vatex-[0-9a-z'\\-]+");
    /** Upper-case words the remarks use that are never codes of the list they describe. */
    private static final Set<String> NOT_CODES = Set.of("VAT", "ID", "EU", "EN", "UN", "NA", "CEF", "EAS", "ISO",
            "UNTDID", "UNSPSC", "FISCAL", "CAT", "NO", "NB");

    public static ChangeClaims parse(String flagText, String remark, Collection<String> knownCodes) {
        var codes = new Codes(knownCodes);
        var added = new LinkedHashSet<String>();
        var removed = new LinkedHashSet<String>();
        var reworded = new LinkedHashSet<String>();
        var deprecated = new LinkedHashSet<String>();
        var unresolved = new LinkedHashSet<String>();
        var counts = new ArrayList<Integer>();
        String lower = remark.toLowerCase(Locale.ROOT);
        boolean newList = lower.matches("(?s).*\\bnew list\\b.*") && !lower.contains("into new list");
        boolean caseChange = lower.matches("(?s).*\\b(lower|upper)\\s*case\\b.*");

        String text = PARENTHESIS.matcher(remark.replace('"', ' ').replace('“', ' ').replace('”', ' '))
                .replaceAll(" ");
        Matcher count = COUNT.matcher(text);
        var countPositions = new LinkedHashSet<Integer>();
        while (count.find()) {
            counts.add(Integer.parseInt(count.group(1)));
            countPositions.add(count.start(1));
        }
        int offset = 0;
        for (String sentence : SENTENCE.split(text, -1)) {
            int start = text.indexOf(sentence, offset);
            offset = start + sentence.length();
            parseSentence(sentence, start, countPositions, codes, added, removed, reworded, deprecated, unresolved);
        }
        return new ChangeClaims(flag(flagText), flagText.strip(), remark.strip(), ordered(added), ordered(removed),
                ordered(reworded), ordered(deprecated), newList, caseChange, List.copyOf(counts),
                List.copyOf(unresolved));
    }

    private static Flag flag(String text) {
        return switch (text.strip().toUpperCase(Locale.ROOT)) {
            case "YES" -> Flag.YES;
            case "NO" -> Flag.NO;
            case "FIXED" -> Flag.FIXED;
            case "" -> Flag.EMPTY;
            default -> Flag.OTHER;
        };
    }

    /** One token of a sentence: a verb, a code, a range marker, a comma, or a word that matters to neither. */
    private record Token(Verb verb, List<String> codes, String unresolved, boolean to, boolean comma) {
        Token(Verb verb, List<String> codes, String unresolved, boolean to) {
            this(verb, codes, unresolved, to, false);
        }
    }

    private static void parseSentence(String sentence, int start, Set<Integer> countPositions, Codes codes,
            Set<String> added, Set<String> removed, Set<String> reworded, Set<String> deprecated,
            Set<String> unresolved) {
        var words = new ArrayList<String>();
        var positions = new ArrayList<Integer>();
        Matcher word = WORD.matcher(sentence);
        while (word.find()) {
            words.add(word.group());
            positions.add(start + word.start());
        }
        var tokens = new ArrayList<Token>();
        for (int i = 0; i < words.size(); i++) {
            String current = words.get(i);
            if (current.equals(",")) {
                tokens.add(new Token(null, List.of(), null, false, true));
                continue;
            }
            String lower = current.toLowerCase(Locale.ROOT);
            String next = i + 1 < words.size() ? words.get(i + 1).toLowerCase(Locale.ROOT) : "";
            String afterNext = i + 2 < words.size() ? words.get(i + 2).toLowerCase(Locale.ROOT) : "";
            if (lower.equals("split") && next.equals("up") && afterNext.equals("into")) {
                tokens.add(new Token(Verb.SPLIT, List.of(), null, false));
                i += 2;
                continue;
            }
            if ((lower.equals("name") || lower.equals("names") || lower.equals("structure"))
                    && isRewordVerb(next)) {
                tokens.add(new Token(Verb.REWORD, List.of(), null, false));
                i++;
                continue;
            }
            Verb verb = verb(lower);
            if (verb == Verb.ADD && namesFollow(words, i)) {
                verb = Verb.REWORD; // "Added Identifier scheme name for code 0194" adds a name, not a code.
            }
            if (verb != null) {
                if (lower.equals("new") && next.equals("list")) {
                    i++;
                    continue; // "New list" is recorded as such; it adds no single code.
                }
                tokens.add(new Token(verb, List.of(), null, false));
                continue;
            }
            if (lower.equals("to") || lower.equals("-")) {
                tokens.add(new Token(null, List.of(), null, true));
                continue;
            }
            if (countPositions.contains(positions.get(i))) {
                continue; // "49" in "adding 49 codes" is a count, not a code.
            }
            String code = codes.resolve(current);
            if (code != null) {
                tokens.add(new Token(null, List.of(code), null, false));
            } else if (CODE_LIKE.matcher(current).matches() && !NOT_CODES.contains(current)
                    && (current.length() <= 12 || current.toLowerCase(Locale.ROOT).startsWith("vatex"))) {
                tokens.add(new Token(null, List.of(), current, false));
            }
        }
        expandRanges(tokens, codes);
        assign(tokens, added, removed, reworded, deprecated, unresolved);
    }

    /** {@code 0221 to 230}: every code of the list between the two, both included. */
    private static void expandRanges(List<Token> tokens, Codes codes) {
        for (int i = 1; i + 1 < tokens.size(); i++) {
            Token from = tokens.get(i - 1);
            Token to = tokens.get(i + 1);
            if (tokens.get(i).to() && from.codes().size() == 1 && to.codes().size() == 1) {
                var range = codes.between(from.codes().get(0), to.codes().get(0));
                if (!range.isEmpty()) {
                    tokens.set(i - 1, new Token(null, range, null, false));
                    tokens.remove(i + 1);
                    tokens.remove(i);
                    i--;
                }
            }
        }
    }

    /**
     * Gives every code to a verb, clause by clause, a clause ending at a comma.
     *
     * <ul>
     * <li>Codes after a verb belong to it: "Added 0209", "name changed for 0096".</li>
     * <li>Codes before a verb belong to it when nothing follows it: "0201 and 0202 added", "TR name changed to
     * Türkiye".</li>
     * <li>When codes both precede and follow a verb, the preceding ones continue the clause before: in
     * "Added …,AU,EM removed 9902" EM is added, not removed.</li>
     * <li>A clause without a verb continues the clause before it ("Added 0154, 0158"), or, at the start of a
     * sentence, waits for the next verb ("codes 0198, 0199 and 0200 added").</li>
     * </ul>
     *
     * A sentence that splits a code ("AN is split up into CW, SX and BQ") removes what precedes the split and adds what
     * follows it.
     */
    private static void assign(List<Token> tokens, Set<String> added, Set<String> removed, Set<String> reworded,
            Set<String> deprecated, Set<String> unresolved) {
        var claims = new LinkedHashMap<Verb, Set<String>>();
        claims.put(Verb.ADD, added);
        claims.put(Verb.REMOVE, removed);
        claims.put(Verb.REWORD, reworded);
        claims.put(Verb.DEPRECATE, deprecated);
        tokens.stream().filter(token -> token.unresolved() != null).forEach(token -> unresolved.add(token.unresolved()));
        int split = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).verb() == Verb.SPLIT) {
                split = i;
            }
        }
        if (split >= 0) {
            for (int i = 0; i < tokens.size(); i++) {
                (i < split ? removed : added).addAll(tokens.get(i).codes());
            }
            return;
        }
        var clauses = new ArrayList<List<Token>>();
        clauses.add(new ArrayList<>());
        for (Token token : tokens) {
            if (token.comma()) {
                clauses.add(new ArrayList<>());
            } else {
                clauses.get(clauses.size() - 1).add(token);
            }
        }
        Verb previous = null;
        var pending = new ArrayList<String>();
        for (List<Token> clause : clauses) {
            int first = -1;
            for (int i = 0; i < clause.size() && first < 0; i++) {
                if (clause.get(i).verb() != null) {
                    first = i;
                }
            }
            if (first < 0) {
                Collection<String> target = previous == null ? pending : claims.get(previous);
                for (Token token : clause) {
                    target.addAll(token.codes());
                }
                continue;
            }
            var before = new ArrayList<String>();
            clause.subList(0, first).forEach(token -> before.addAll(token.codes()));
            Verb current = clause.get(first).verb();
            boolean codesAfter = false;
            for (Token token : clause.subList(first + 1, clause.size())) {
                if (token.verb() != null) {
                    current = token.verb();
                } else if (!token.codes().isEmpty()) {
                    claims.get(current).addAll(token.codes());
                    codesAfter = true;
                } else if (token.unresolved() != null) {
                    codesAfter = true; // "removed 9902" still follows its verb when 9902 is gone from every list.
                }
            }
            Verb owner = codesAfter && previous != null ? previous : clause.get(first).verb();
            claims.get(owner).addAll(before);
            claims.get(clause.get(first).verb()).addAll(pending);
            pending.clear();
            previous = current;
        }
        unresolved.addAll(pending); // Codes no verb speaks of: named, but not what happened to them.
    }

    private static Verb verb(String word) {
        return switch (word) {
            case "added", "adding", "add", "adds", "new" -> Verb.ADD;
            case "removed", "removing", "remove", "removes", "deleted", "deleting" -> Verb.REMOVE;
            case "deprecated", "deprecating", "deprecate" -> Verb.DEPRECATE;
            case "updated", "corrected", "changed", "edited", "renamed" -> Verb.REWORD;
            default -> null;
        };
    }

    private static boolean isRewordVerb(String word) {
        return verb(word) == Verb.REWORD || word.equals("added");
    }

    private static boolean namesFollow(List<String> words, int verb) {
        for (int i = verb + 1; i < Math.min(words.size(), verb + 4); i++) {
            if (words.get(i).equalsIgnoreCase("name") || words.get(i).equalsIgnoreCase("names")) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> ordered(Set<String> codes) {
        var sorted = new TreeSet<String>(GenericodeNormalizer.CODE_ORDER);
        sorted.addAll(codes);
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    /** The codes of the list, found the way a remark writes them. */
    private static final class Codes {
        private final Map<String, String> byUpperCase = new LinkedHashMap<>();

        Codes(Collection<String> known) {
            known.forEach(code -> byUpperCase.putIfAbsent(code.toUpperCase(Locale.ROOT), code));
        }

        String resolve(String word) {
            if (byUpperCase.containsValue(word)) {
                return word;
            }
            String upper = word.toUpperCase(Locale.ROOT);
            // Case is ignored only for words written like codes: "is" must not become Iceland's IS, but
            // "vatex-eu-132" is how the 2019 remarks write VATEX-EU-132.
            boolean codeLike = word.equals(upper) || word.toLowerCase(Locale.ROOT).startsWith("vatex");
            if (codeLike && byUpperCase.containsKey(upper)) {
                return byUpperCase.get(upper);
            }
            if (upper.matches("\\d{1,5}")) {
                // "Adding 217, 221" for 0217 and 0221: the remark dropped the leading zeros the list has.
                for (int width = upper.length() + 1; width <= 6; width++) {
                    String padded = "0".repeat(width - upper.length()) + upper;
                    if (byUpperCase.containsKey(padded)) {
                        return byUpperCase.get(padded);
                    }
                }
            }
            return null;
        }

        /** The listed numeric codes from {@code from} to {@code to}, or nothing when either is not numeric. */
        List<String> between(String from, String to) {
            if (!from.matches("\\d+") || !to.matches("\\d+")) {
                return List.of();
            }
            BigInteger low = new BigInteger(from);
            BigInteger high = new BigInteger(to);
            if (low.compareTo(high) > 0) {
                return List.of();
            }
            return byUpperCase.values().stream().filter(code -> code.matches("\\d+"))
                    .filter(code -> new BigInteger(code).compareTo(low) >= 0
                            && new BigInteger(code).compareTo(high) <= 0)
                    .sorted(GenericodeNormalizer.CODE_ORDER).toList();
        }
    }
}
