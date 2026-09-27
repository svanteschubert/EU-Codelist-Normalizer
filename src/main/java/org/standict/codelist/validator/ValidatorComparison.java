package org.standict.codelist.validator;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Compares the codes each validator rule implements with the code list the Commission published for the same date.
 *
 * <p><strong>Which dates.</strong> Something changes for an implementer whenever either side changes: a new code-list
 * release, or a new validator release. The two are not always published for the same day -- validator 1.3.8 applies
 * from 2022-05-15, the code lists from 2022-05-16 -- so every effective date of either side is compared, each time with
 * what was in force on that date on both sides. A date on which only one side changed shows how long a disagreement
 * lasted.
 *
 * <p><strong>Against what.</strong> The code lists are published twice, as Genericode and as a spreadsheet, and the
 * validator is compared with each, because the two do not always agree with each other either. Until 2021 only the
 * spreadsheet was published; a missing component is reported as such, never as agreement.
 */
public final class ValidatorComparison {
    /** What changed on a date: the code lists, the validator, or both. */
    public enum Trigger { CODE_LISTS, VALIDATOR, BOTH }

    /** One effective date and what was in force on it. */
    public record DatePoint(LocalDate effectiveDate, Trigger trigger, ValidatorCatalog.Release validator,
            CodeListReleases.Release codeLists) {}

    /**
     * One rule compared with one published component.
     *
     * @param published how many codes the component publishes for the list
     * @param onlyInValidator codes the validator implements that the component does not publish
     * @param onlyPublished codes the component publishes that the validator rejects
     */
    public record Side(String source, int published, List<String> onlyInValidator, List<String> onlyPublished,
            Map<String, Description> names) {
        public boolean agrees() {
            return onlyInValidator.isEmpty() && onlyPublished.isEmpty();
        }

        public int differences() {
            return onlyInValidator.size() + onlyPublished.size();
        }
    }

    /**
     * The name of a code, as a reader needs it next to the code.
     *
     * @param from empty when the compared release names the code itself; otherwise the release the name was taken
     *     from, because a code the validator still implements may have left the published list long ago
     */
    public record Description(String name, String from) {}

    /**
     * One rule of one syntax on one date.
     *
     * @param codeList empty when the rule is not in the catalogue
     * @param genericode {@code null} when the release publishes no Genericode file for this list
     * @param spreadsheet {@code null} when the release publishes no sheet for this list
     */
    public record RuleComparison(LocalDate effectiveDate, String validatorTag, String codeListRelease, Syntax syntax,
            String rule, String codeList, int validatorCodes, Side genericode, Side spreadsheet) {}

    public record Report(List<DatePoint> dates, List<RuleComparison> rules) {}

    private final Map<String, Optional<CodeListReleases.Published>> published = new HashMap<>();

    /**
     * @param extracted the rules of every validator release, by release tag and syntax
     */
    public Report compare(ValidatorCatalog catalog, Map<String, Map<Syntax, List<SchematronCodeLists.RuleCodes>>> extracted,
            CodeListReleases codeLists) throws IOException {
        var codeListDates = new TreeSet<LocalDate>();
        codeLists.releases().forEach(release -> codeListDates.add(release.effectiveDate()));
        var validatorDates = new TreeSet<LocalDate>();
        catalog.releases().forEach(release -> validatorDates.add(release.effectiveDate()));
        var dates = new TreeSet<LocalDate>(codeListDates);
        dates.addAll(validatorDates);

        var points = new ArrayList<DatePoint>();
        var rules = new ArrayList<RuleComparison>();
        for (LocalDate date : dates) {
            var validator = catalog.inForce(date);
            var release = codeLists.inForce(date);
            if (validator.isEmpty() || release.isEmpty()) {
                continue; // Before the first release of one side there is nothing to compare with.
            }
            Trigger trigger = codeListDates.contains(date)
                    ? validatorDates.contains(date) ? Trigger.BOTH : Trigger.CODE_LISTS
                    : Trigger.VALIDATOR;
            points.add(new DatePoint(date, trigger, validator.get(), release.get()));
            var bySyntax = extracted.get(validator.get().tag());
            if (bySyntax == null) {
                throw new IOException("Validator release " + validator.get().tag() + " was not extracted");
            }
            for (Syntax syntax : Syntax.values()) {
                for (var rule : bySyntax.getOrDefault(syntax, List.of())) {
                    rules.add(compare(date, validator.get(), release.get(), syntax, rule, catalog, codeLists));
                }
            }
        }
        return new Report(List.copyOf(points), List.copyOf(rules));
    }

    private RuleComparison compare(LocalDate date, ValidatorCatalog.Release validator,
            CodeListReleases.Release release, Syntax syntax, SchematronCodeLists.RuleCodes rule,
            ValidatorCatalog catalog, CodeListReleases codeLists) throws IOException {
        var mapping = catalog.mapping(syntax, rule.rule());
        if (mapping.isEmpty()) {
            return new RuleComparison(date, validator.tag(), release.directory(), syntax, rule.rule(), "",
                    rule.codes().size(), null, null);
        }
        String codeList = mapping.get().codeList();
        String column = mapping.get().spreadsheetColumn();
        var genericode = genericode(codeLists, release, codeList);
        var spreadsheet = spreadsheet(codeLists, release, codeList, column);
        return new RuleComparison(date, validator.tag(), release.directory(), syntax, rule.rule(), codeList,
                rule.codes().size(),
                genericode.isEmpty() ? null : side(rule.codes(), genericode.get(), codeLists, release, codeList, column, true),
                spreadsheet.isEmpty() ? null
                        : side(rule.codes(), spreadsheet.get(), codeLists, release, codeList, column, false));
    }

    private Optional<CodeListReleases.Published> genericode(CodeListReleases codeLists,
            CodeListReleases.Release release, String codeList) throws IOException {
        return cached("gc\u0000" + release.directory() + "\u0000" + codeList,
                () -> codeLists.genericode(release, codeList));
    }

    private Optional<CodeListReleases.Published> spreadsheet(CodeListReleases codeLists,
            CodeListReleases.Release release, String codeList, String column) throws IOException {
        return cached("xlsx\u0000" + release.directory() + "\u0000" + codeList + "\u0000" + column,
                () -> codeLists.spreadsheet(release, codeList, column));
    }

    /**
     * The name of a code, from the compared release where it lists the code, else from the nearest release that did:
     * the latest earlier one first, then the earliest later one. Each release is asked for its compared component
     * first. {@code null} when no release ever named the code, as for the {@code SEPA} scheme UBL's BR-CL-10 allows.
     */
    private Description describe(CodeListReleases codeLists, CodeListReleases.Release compared, String codeList,
            String column, boolean genericodeFirst, String code) throws IOException {
        var order = new java.util.ArrayList<CodeListReleases.Release>();
        codeLists.releases().stream().filter(r -> !r.effectiveDate().isAfter(compared.effectiveDate()))
                .forEach(r -> order.add(0, r));
        codeLists.releases().stream().filter(r -> r.effectiveDate().isAfter(compared.effectiveDate()))
                .forEach(order::add);
        for (CodeListReleases.Release release : order) {
            for (boolean fromGenericode : genericodeFirst ? new boolean[] {true, false} : new boolean[] {false, true}) {
                var published = fromGenericode ? genericode(codeLists, release, codeList)
                        : spreadsheet(codeLists, release, codeList, column);
                if (published.isPresent() && published.get().names().containsKey(code)) {
                    return new Description(published.get().names().get(code),
                            release == compared ? "" : release.directory());
                }
            }
        }
        return null;
    }

    /** Both lists are in code order already, and the differences keep that order. */
    private Side side(List<String> validator, CodeListReleases.Published published, CodeListReleases codeLists,
            CodeListReleases.Release release, String codeList, String column, boolean genericode) throws IOException {
        var implemented = new LinkedHashSet<>(validator);
        var listed = new LinkedHashSet<>(published.codes());
        var onlyInValidator = validator.stream().filter(code -> !listed.contains(code)).toList();
        var onlyPublished = published.codes().stream().filter(code -> !implemented.contains(code)).toList();
        var names = new java.util.TreeMap<String, Description>();
        for (String code : onlyInValidator) {
            Description description = describe(codeLists, release, codeList, column, genericode, code);
            if (description != null) {
                names.put(code, description);
            }
        }
        for (String code : onlyPublished) {
            String name = published.names().get(code);
            Description description = name != null ? new Description(name, "")
                    : describe(codeLists, release, codeList, column, genericode, code);
            if (description != null) {
                names.put(code, description);
            }
        }
        var ordered = new java.util.TreeMap<String, Description>(org.standict.codelist.normalize.GenericodeNormalizer.CODE_ORDER);
        ordered.putAll(names);
        return new Side(published.source(), listed.size(), onlyInValidator, onlyPublished,
                java.util.Collections.unmodifiableMap(ordered));
    }

    private interface Read {
        Optional<CodeListReleases.Published> read() throws IOException;
    }

    /** The same file is compared on many dates and by several rules; it is read once. */
    private Optional<CodeListReleases.Published> cached(String key, Read read) throws IOException {
        var hit = published.get(key);
        if (hit == null) {
            hit = read.read();
            published.put(key, hit);
        }
        return hit;
    }
}
