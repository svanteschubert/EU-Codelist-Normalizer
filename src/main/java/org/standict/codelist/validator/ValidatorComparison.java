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
 * Compares the codes each validator rule accepts with the code list the Commission published for the same date.
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
     * @param onlyInValidator codes the validator accepts that the component does not publish
     * @param onlyPublished codes the component publishes that the validator rejects
     */
    public record Side(String source, int published, List<String> onlyInValidator, List<String> onlyPublished) {
        public boolean agrees() {
            return onlyInValidator.isEmpty() && onlyPublished.isEmpty();
        }

        public int differences() {
            return onlyInValidator.size() + onlyPublished.size();
        }
    }

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
        var genericode = cached("gc\u0000" + release.directory() + "\u0000" + codeList,
                () -> codeLists.genericode(release, codeList));
        var spreadsheet = cached("xlsx\u0000" + release.directory() + "\u0000" + codeList + "\u0000" + column,
                () -> codeLists.spreadsheet(release, codeList, column));
        return new RuleComparison(date, validator.tag(), release.directory(), syntax, rule.rule(), codeList,
                rule.codes().size(), genericode.map(side -> side(rule.codes(), side)).orElse(null),
                spreadsheet.map(side -> side(rule.codes(), side)).orElse(null));
    }

    /** Both lists are in code order already, and the differences keep that order. */
    private static Side side(List<String> validator, CodeListReleases.Published published) {
        var accepted = new LinkedHashSet<>(validator);
        var listed = new LinkedHashSet<>(published.codes());
        return new Side(published.source(), listed.size(),
                validator.stream().filter(code -> !listed.contains(code)).toList(),
                published.codes().stream().filter(code -> !accepted.contains(code)).toList());
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
