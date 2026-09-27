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
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--help", "-h" -> {
                        System.out.println("""
                                Usage: ./run-normalize.sh [--deliveries|--compare] [--downloader PATH]
                                                        [--output PATH]
                                  --deliveries       Write one delivery per effective date instead of per release
                                  --compare          Report what changed between the deliveries written by
                                                     --deliveries, into compared/
                                  --downloader PATH  Downloader repository (default: ../EU-Codelist-Downloader)
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
                    case "--downloader", "--output" -> {
                        String option = args[i];
                        if (++i == args.length || args[i].startsWith("--")) {
                            throw new IllegalArgumentException("Missing value for " + option);
                        }
                        if (option.equals("--downloader")) downloader = Path.of(args[i]);
                        else output = Path.of(args[i]);
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
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
