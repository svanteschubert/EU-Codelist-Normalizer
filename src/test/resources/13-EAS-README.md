# Release 13: EAS normalization test files

A **test fixture** is a fixed example used by an automated test. These two files
let the test check that the normalizer continues to produce the expected result
after code changes.

- [`13-EAS-input.gc`](13-EAS-input.gc) supplies the original input.
- [`13-EAS-expected.gc`](13-EAS-expected.gc) defines the expected normalized result.

Both contain the same 93 Electronic Address Scheme (EAS) entries from EN16931
bundle release **13**, effective **2024-05-15**. The number 13 identifies the
EN16931 bundle release; EAS has its own independent version numbering.

## What normalization changes

The expected file sorts complete rows by the base-36 value of their code. For
example, `AN` has a decimal value of 383, so it moves before `0106`, whose
base-36 value is 1302 in decimal. Leading zeros remain part of the stored code.

The code values, descriptions, annotations, identification metadata and column
definitions are preserved. No entries are added or removed. XML serialization
also changes attribute order and uses LF line endings in the expected file,
where the input uses CRLF for XML markup.

## What the test checks

`GenericodeNormalizerTest.reproducesTheEarlierEasExample`:

1. Reads the input file and runs the normalizer.
2. Compares the complete parsed XML data with the expected file, including row
   order. Comparing parsed data tolerates equivalent XML formatting.
3. Normalizes that result again and checks that its bytes remain identical.

The fixtures preserve the earlier EAS example described in the project's
[README](../../../README.md). Their source provenance is recorded with the
[release 13 extracted files](13_2024-05-15/r01/extracted/gc/source.json).

These files are fixed test references kept separately from generated release
outputs. Update them only after reviewing an intentional change to the expected
normalization behavior. Renaming them with the `13-` prefix did not change their
contents.
