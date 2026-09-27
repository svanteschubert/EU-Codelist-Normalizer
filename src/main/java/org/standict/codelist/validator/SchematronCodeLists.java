package org.standict.codelist.validator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.standict.codelist.normalize.GenericodeNormalizer;
import org.w3c.dom.Element;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Reads the code lists the validator enforces out of its Schematron code-list pattern.
 *
 * <p>The validator states each list inline, in the XPath test of an assertion, in one of two shapes:
 *
 * <pre>
 * contains(' 71 80 81 82 ', concat(' ', normalize-space(.), ' '))          an enumeration, space separated
 * (@mimeCode = 'application/pdf' or @mimeCode = 'image/png' ...)          a chain of comparisons (BR-CL-24)
 * </pre>
 *
 * <p>An assertion may hold several of them: UBL's {@code BR-CL-01} lists the invoice type codes and the credit note
 * type codes separately, and UBL's {@code BR-CL-10} allows {@code SEPA} in addition to the ICD list for the seller and
 * payee. The codes an assertion accepts are the union of all of them, because that union is what a document may carry
 * and what the published code list has to be compared with.
 *
 * <p>Normalization is limited to what makes two releases comparable: codes are de-duplicated and written in the same
 * base-36 order as the normalized Genericode files. The codes themselves are kept exactly, including their case.
 */
public final class SchematronCodeLists {
    private static final String SCHEMATRON = "http://purl.oclc.org/dsdl/schematron";
    private static final Pattern ENUMERATION = Pattern.compile("contains\\(\\s*'([^']*)'\\s*,\\s*concat\\(");
    private static final Pattern COMPARISON = Pattern.compile("(?:@[\\w:.-]+|\\.)\\s*=\\s*'([^']*)'");

    /**
     * The codes one rule accepts.
     *
     * @param context the XPath context of the Schematron rule, so a reader can see what is being checked
     * @param codes distinct, in base-36 code order
     * @param lines the line of the file on which each code is listed, for linking to it
     */
    public record RuleCodes(String rule, String context, List<String> codes, Map<String, Integer> lines) {
        /** The first line on which the rule lists a code: where a code it lacks would have to be added. */
        public int listLine() {
            return lines.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        }
    }

    /** Every code-list assertion of the file, in the order of their rule identifiers. */
    public List<RuleCodes> read(byte[] schematron, String where) throws IOException {
        Element root;
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void error(SAXParseException e) throws SAXParseException { throw e; }
                @Override public void fatalError(SAXParseException e) throws SAXParseException { throw e; }
            });
            root = builder.parse(new ByteArrayInputStream(schematron)).getDocumentElement();
        } catch (Exception e) {
            throw new IOException("Cannot read Schematron " + where + ": " + e.getMessage(), e);
        }
        var contexts = new LinkedHashMap<String, LinkedHashSet<String>>();
        var codes = new LinkedHashMap<String, LinkedHashSet<String>>();
        var asserts = root.getElementsByTagNameNS(SCHEMATRON, "assert");
        for (int i = 0; i < asserts.getLength(); i++) {
            var assertion = (Element) asserts.item(i);
            var accepted = codesOf(assertion.getAttribute("test"));
            if (accepted.isEmpty()) {
                continue; // Not a code-list assertion.
            }
            String rule = assertion.getAttribute("id").strip();
            if (rule.isEmpty()) {
                throw new IOException("Code-list assertion without an id in " + where + ": "
                        + assertion.getTextContent().strip());
            }
            // A rule stated in two assertions accepts what either accepts; merge rather than keep the last one.
            codes.computeIfAbsent(rule, key -> new LinkedHashSet<>()).addAll(accepted);
            if (assertion.getParentNode() instanceof Element parent && !parent.getAttribute("context").isBlank()) {
                contexts.computeIfAbsent(rule, key -> new LinkedHashSet<>())
                        .add(parent.getAttribute("context").replaceAll("\\s+", " ").strip());
            }
        }
        String text = new String(schematron, java.nio.charset.StandardCharsets.UTF_8);
        var rules = new ArrayList<RuleCodes>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : codes.entrySet()) {
            rules.add(new RuleCodes(entry.getKey(),
                    String.join(" | ", contexts.getOrDefault(entry.getKey(), new LinkedHashSet<>())),
                    entry.getValue().stream().sorted(GenericodeNormalizer.CODE_ORDER).toList(),
                    lines(text, entry.getKey(), entry.getValue())));
        }
        rules.sort(java.util.Comparator.comparing(RuleCodes::rule));
        return List.copyOf(rules);
    }

    private static final Pattern LITERAL = Pattern.compile("'([^']*)'");

    /**
     * The line on which each code of a rule is listed. The parsed document has no line numbers, so the rule's
     * {@code assert} start tags are found in the text, reading attribute values whole because an XPath test may contain
     * a {@code >}, and each code is looked up in the quoted literals of their tests.
     */
    static Map<String, Integer> lines(String text, String rule, java.util.Collection<String> codes) {
        var wanted = new java.util.HashSet<>(codes);
        var lines = new java.util.LinkedHashMap<String, Integer>();
        int[] lineStarts = lineStarts(text);
        Pattern id = Pattern.compile("\\bid\\s*=\\s*[\"']" + Pattern.quote(rule) + "[\"']");
        for (int start = text.indexOf("<assert"); start >= 0; start = text.indexOf("<assert", start + 1)) {
            int end = endOfTag(text, start);
            String tag = text.substring(start, end);
            if (!id.matcher(tag).find()) {
                continue;
            }
            Matcher literal = LITERAL.matcher(tag);
            while (literal.find()) {
                int base = start + literal.start(1);
                String value = literal.group(1);
                // A comparison's literal is one code, which may itself contain a space; an enumeration's is many.
                if (wanted.contains(value.strip())) {
                    lines.putIfAbsent(value.strip(), line(lineStarts, base + value.indexOf(value.strip())));
                }
                Matcher token = TOKEN.matcher(value);
                while (token.find()) {
                    if (wanted.contains(token.group())) {
                        lines.putIfAbsent(token.group(), line(lineStarts, base + token.start()));
                    }
                }
            }
        }
        return java.util.Collections.unmodifiableMap(lines);
    }

    private static final Pattern TOKEN = Pattern.compile("\\S+");

    private static int[] lineStarts(String text) {
        var starts = new java.util.ArrayList<Integer>(List.of(0));
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The 1-based line of {@code offset}. */
    private static int line(int[] lineStarts, int offset) {
        int index = java.util.Arrays.binarySearch(lineStarts, offset);
        return index >= 0 ? index + 1 : -index - 1;
    }

    /** The index just past the {@code >} closing the start tag at {@code start}, skipping quoted attribute values. */
    private static int endOfTag(String text, int start) {
        char quote = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i + 1;
            }
        }
        return text.length();
    }

    /** The codes an XPath test accepts, from its enumerations and its comparisons with a literal. */
    static LinkedHashSet<String> codesOf(String test) {
        var accepted = new LinkedHashSet<String>();
        var enumerations = ENUMERATION.matcher(test);
        while (enumerations.find()) {
            for (String code : enumerations.group(1).strip().split("\\s+")) {
                if (!code.isEmpty()) {
                    accepted.add(code);
                }
            }
        }
        var comparisons = COMPARISON.matcher(test);
        while (comparisons.find()) {
            if (!comparisons.group(1).isBlank()) {
                accepted.add(comparisons.group(1).strip());
            }
        }
        return accepted;
    }
}
