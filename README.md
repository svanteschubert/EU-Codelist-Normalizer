# EN 16931 code lists, effective date by effective date

> **Unofficial showcase.** The authoritative code lists are those the European Commission publishes in its
> [Registry of supporting artefacts to implement EN16931](https://ec.europa.eu/digital-building-blocks/sites/spaces/DIGITAL/pages/467108974/Registry+of+supporting+artefacts+to+implement+EN16931).
> Where this branch differs, the Registry prevails.

Each commit of this branch is one effective date of the EN 16931 code lists, oldest first, holding the last revision
of that date's release, so that `git diff` between two commits shows what changed between them:

- `xlsx/`: every sheet of the "EN16931 code lists values" workbook as CSV, the code lists sorted by code;
- `gc/`: the Genericode files, from 2021, normalized: codes in base-36 order, formatting unified;
- `RELEASE.md`: where the files of the commit come from, and which earlier revisions they replace.

A release that did not republish the Genericode files keeps those of the last one that did, as its `RELEASE.md`
states. Every commit is dated by its effective date and tagged `code-lists-<release>`, for example:

    git diff code-lists-16_2025-11-15 code-lists-17_2026-05-15 -- gc/Currency.gc
    git log --oneline -- xlsx/ICD.csv

Built by `build-history-branch.sh` of [EU-Codelist-Normalizer](https://github.com/svanteschubert/EU-Codelist-Normalizer)
from its `src/test/resources/`. The code lists remain the European Commission's; the repository's license does not
apply to them.
