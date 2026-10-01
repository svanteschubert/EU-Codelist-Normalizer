package org.standict.codelist.normalize;

import java.nio.file.Path;

public final class NormalizerMain {
    private NormalizerMain() {}

    public static void main(String[] args) {
        try {
            Path downloader = Path.of("../EU-Codelist-Downloader");
            Path output = Path.of("src/test/resources");
            boolean deliveries = false;
            boolean compare = false;
            boolean statistics = false;
            boolean validator = false;
            Path validatorRepository = Path.of("../eInvoicing-EN16931");
            var reportFolders = new java.util.ArrayList<Path>();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--help", "-h" -> {
                        System.out.println("""
                                Usage: ./run-normalize.sh [--deliveries|--compare|--statistics|--validator]
                                                        [--downloader PATH] [--validator-repo PATH] [--output PATH]
                                                        [--report-folder PATH]
                                  --deliveries       Write one delivery per effective date instead of per release
                                  --compare          Report what changed between the deliveries written by
                                                     --deliveries, into compared/ as CSV
                                  --statistics       Render the same analysis, plus the agreement between the
                                                     Genericode and spreadsheet components, as statistics/index.html
                                  --validator        Extract the UBL and CII code lists of every validator release,
                                                     normalize them and compare them, per effective date, with the
                                                     Genericode files and spreadsheets of the releases in --output;
                                                     also checks every release's Index sheet: its stated changes
                                                     against its sheets and Genericode files, and its business
                                                     terms against EN 16931-1:2017; writes validator/ with
                                                     summary.csv, rules.csv, index-claims.csv, business-terms.csv,
                                                     index-releases.csv and index.html, and publishes the
                                                     report with every file it links to into each
                                                     --report-folder. Findings link to their line in the
                                                     extracted files of --output, and of the validator at
                                                     the release's commit, on GitHub
                                  --downloader PATH  Downloader repository (default: ../EU-Codelist-Downloader)
                                  --report-folder PATH
                                                     Self-contained copy of the --validator report to share;
                                                     repeat it for several identical copies (default:
                                                     docs/en16931-code-list-comparison)
                                  --validator-repo PATH
                                                     eInvoicing-EN16931 checkout, read through its release tags
                                                     (default: ../eInvoicing-EN16931)
                                  --output PATH      Releases (default: src/test/resources)

                                Default: reads the downloader registry, Genericode ZIPs and EN16931 XLSX
                                workbooks, and writes <version_date>/rNN/extracted/{gc,xlsx}/ in original
                                order and <version_date>/rNN/normalized/{gc,xlsx}/ sorted by base-36 code
                                value. Numeric release versions and revision numbers use at least two digits.
                                Completely empty spreadsheet rows are omitted from both CSV stages.

                                With --deliveries: groups every downloaded artefact of every category into
                                the delivery of its effective date, keeping each revision, and writes the
                                contents of the artefacts as downloaded/<effective-date>/<artefact>/ and
                                normalized/<effective-date>/<artefact>/. A correction republished under the
                                same name becomes <artefact>_revisionNN. Both roots are generated in full and
                                carry a delivery-index.json naming the snapshot they came from; a root
                                without one is refused rather than replaced.
                                """);
                        return;
                    }
                    case "--deliveries" -> deliveries = true;
                    case "--compare" -> compare = true;
                    case "--statistics" -> statistics = true;
                    case "--validator" -> validator = true;
                    case "--downloader", "--output", "--validator-repo", "--report-folder" -> {
                        String option = args[i];
                        if (++i == args.length || args[i].startsWith("--")) {
                            throw new IllegalArgumentException("Missing value for " + option);
                        }
                        if (option.equals("--downloader")) downloader = Path.of(args[i]);
                        else if (option.equals("--validator-repo")) validatorRepository = Path.of(args[i]);
                        else if (option.equals("--report-folder")) reportFolders.add(Path.of(args[i]));
                        else output = Path.of(args[i]);
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }
            if (validator) {
                // The report goes to this repository's GitHub Pages folder; the downloader is only read, for the
                // originals the report links to.
                if (reportFolders.isEmpty()) {
                    reportFolders.add(Path.of("docs/en16931-code-list-comparison"));
                }
                var publication = new org.standict.codelist.validator.ValidatorPipeline.Publication(downloader,
                        reportFolders);
                var result = new org.standict.codelist.validator.ValidatorPipeline()
                        .run(validatorRepository, output, publication);
                var latest = result.report().rules().stream()
                        .filter(rule -> !result.report().dates().isEmpty() && rule.effectiveDate().equals(
                                result.report().dates().get(result.report().dates().size() - 1).effectiveDate()))
                        .toList();
                System.out.printf("Extracted %d code-list rules from %d validator releases (UBL and CII), "
                        + "compared on %d effective dates%n", result.rules(), result.releases(),
                        result.report().dates().size());
                System.out.printf("Latest date: %d of %d rules disagree with Genericode, %d with the spreadsheet%n",
                        latest.stream().filter(r -> r.genericode() != null && !r.genericode().agrees()).count(),
                        latest.size(),
                        latest.stream().filter(r -> r.spreadsheet() != null && !r.spreadsheet().agrees()).count());
                var revisions = result.index().revisions();
                System.out.printf("Index sheets of %d release revisions: %d of %d rows disagree with their sheets "
                        + "or Genericode files; %d rows list other business terms than EN 16931-1:2017%n",
                        revisions.size(), revisions.stream().flatMap(r -> r.tabs().stream())
                                .filter(t -> t.verdict() == org.standict.codelist.index.IndexCheck.Verdict.MISMATCH)
                                .count(),
                        revisions.stream().mapToInt(r -> r.tabs().size()).sum(),
                        revisions.stream().flatMap(r -> r.terms().stream())
                                .filter(org.standict.codelist.index.BusinessTerms.Check::differsFrom2017).count());
                System.out.printf("Report: %s%n", result.directory().resolve("index.html"));
                result.pages().forEach(page -> System.out.printf("Report folder to share: %s%n", page));
                return;
            }
            if (statistics) {
                Path normalized = output.resolve("normalized");
                Path html = output.resolve("statistics/index.html");
                var report = new org.standict.codelist.statistics.Statistics().analyse(normalized);
                new org.standict.codelist.statistics.HtmlStatisticsReport().write(report, html);
                System.out.printf("%d deliveries, %d code-list changes, %d disagreements between components, "
                        + "%d one-sided code lists%n", report.effectiveDates().size(),
                        report.changes().stream().filter(c -> c.total() > 0).count(),
                        report.agreements().stream().filter(a -> a.disagreements() > 0).count(),
                        report.missing().size());
                System.out.printf("Report: %s%n", html.toAbsolutePath().normalize());
                return;
            }
            if (compare) {
                Path normalized = output.resolve("normalized");
                Path compared = output.resolve("compared");
                var report = new org.standict.codelist.compare.DeliveryComparison().compare(normalized, compared);
                System.out.printf("Compared %d consecutive deliveries and %d correction(s): "
                        + "%d code lists changed, %d codes added, %d removed, %d reworded%n",
                        report.deliveryPairs(), report.corrections(), report.codeLists(), report.added(),
                        report.removed(), report.changed());
                System.out.printf("Reports in %s%n", compared.toAbsolutePath().normalize());
                return;
            }
            if (deliveries) {
                var delivered = new org.standict.codelist.delivery.DeliveryPipeline().run(downloader, output);
                System.out.printf("Wrote %d deliveries holding %d artefacts (%d files, %d normalized) into %s%n",
                        delivered.summary().deliveries(), delivered.summary().artefacts(), delivered.summary().files(),
                        delivered.summary().normalized(), output.toAbsolutePath().normalize());
                System.out.printf("Downloader snapshot: %s%n", delivered.sourcesDigest());
                return;
            }
            var result = new NormalizationPipeline().run(downloader, output);
            System.out.printf("Normalized %d code lists from %d archives (%d rows) into %s%n",
                    result.files(), result.archives(), result.rows(), output.toAbsolutePath().normalize());
            System.out.printf("Extracted and normalized %d sheets from %d workbooks as CSV%n",
                    result.sheets(), result.workbooks());
        } catch (Exception e) {
            System.err.println("Normalization failed: " + e.getMessage());
            System.exit(1);
        }
    }
}
