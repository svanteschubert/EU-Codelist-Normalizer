#!/usr/bin/env bash
# Builds the code-history branch: one commit per effective date of the EN 16931 code lists, oldest first, each holding
# the normalized Genericode files and spreadsheet sheets in force from that date at the same paths, so that `git diff`
# between two commits shows what changed between two releases.
#
#   ./build-history-branch.sh                  bring the branch in line with the committed release data
#   ./build-history-branch.sh --install-hook   do so after every commit that changes release data
#   ./build-history-branch.sh --remove-hook    stop doing so
#   ./build-history-branch.sh --branch NAME    use another branch name than code-history
#
# The branch is derived from the release data, so the script works out by itself what to do: it keeps every commit
# that is still right, rebuilds from the first date whose files changed, such as a correction of a release, and adds
# the dates that are new; a date always holds the last revision of its release. Only committed release data is read.
# The commits are made with your Git configuration, signed when commit.gpgsign is set, dated by the effective date and
# tagged code-lists-<release>. Nothing is pushed; the script prints the commands that publish what it changed.
set -euo pipefail
# A Git hook runs this with the committing checkout's GIT_DIR and GIT_INDEX_FILE set, which would point the commands
# for the history's own worktree at that checkout's index.
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_PREFIX
cd -- "$(git -C "$(dirname -- "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"

branch=code-history
hook=
while [ $# -gt 0 ]; do
  case "$1" in
    --branch) branch=${2:?--branch needs a name}; shift 2 ;;
    --install-hook) hook=install; shift ;;
    --remove-hook) hook=remove; shift ;;
    -h|--help) sed -n '2,15p' "$0" | cut -c3-; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

releases=src/test/resources
registry='https://ec.europa.eu/digital-building-blocks/sites/spaces/DIGITAL/pages/467108974/Registry+of+supporting+artefacts+to+implement+EN16931'
downloader='https://github.com/svanteschubert/EU-Codelist-Downloader/blob/master'
marker='# Installed by build-history-branch.sh'

# The hook runs this script after every commit of the branch it was installed from that changes release data.
if [ -n "$hook" ]; then
  file=$(git rev-parse --git-path hooks/post-commit)
  if [ -e "$file" ] && ! grep -qF "$marker" "$file"; then
    echo "$file exists and was not written by this script; merge it by hand." >&2
    exit 1
  fi
  if [ "$hook" = remove ]; then
    rm -f "$file"
    echo "Removed $file."
    exit 0
  fi
  source_branch=$(git rev-parse --abbrev-ref HEAD)
  if [ "$source_branch" = HEAD ] || [ "$source_branch" = "$branch" ]; then
    echo "Install the hook from the branch that holds the release data, not from ${source_branch}." >&2
    exit 1
  fi
  mkdir -p "$(dirname "$file")"
  cat > "$file" <<EOF
#!/bin/sh
$marker: after a commit on $source_branch that changes release data,
# bring $branch in line with it. Remove with: ./build-history-branch.sh --remove-hook
[ -z "\${CODE_HISTORY_BUILDING:-}" ] || exit 0
[ "\$(git rev-parse --abbrev-ref HEAD)" = "$source_branch" ] || exit 0
git diff-tree --root --no-commit-id --name-only -r HEAD -- '$releases/' \\
  | grep -q '^$releases/[^/]*_[0-9-]*/r[0-9]*/normalized/' || exit 0
./build-history-branch.sh --branch '$branch' || echo "code-history: run ./build-history-branch.sh to see why it failed" >&2
EOF
  chmod +x "$file"
  echo "Installed $file: commits on $source_branch that change release data now update $branch."
  exit 0
fi

source_commit=$(git rev-parse HEAD)
if [ -n "$(git status --porcelain -uall -- "$releases" | grep -E "^.. $releases/[^/]*_[0-9-]*/r[0-9]*/" || true)" ]; then
  echo "Note: uncommitted release data is left out; commit it and run again to add it."
fi

# Every committed release, by effective date: 01_2019-03-15 … 17_2026-05-15.
dates=()
while IFS= read -r release; do
  dates+=("$release")
done < <(
  git ls-tree -d --name-only "$source_commit:$releases" | grep -E '^[^/]+_[0-9]{4}-[0-9]{2}-[0-9]{2}$' |
    while IFS= read -r release; do printf '%s %s\n' "${release#*_}" "$release"; done | sort | cut -d' ' -f2)
if [ ${#dates[@]} -eq 0 ]; then
  echo "No committed releases in $releases; run ./run-normalize.sh and commit its output first." >&2
  exit 1
fi

# The revisions of a release, lowest first: r01 r02.
revisions_of() {
  git ls-tree -d --name-only "$source_commit:$releases/$1" | grep -E '^r[0-9]+$' |
    while IFS= read -r number; do printf '%05d %s\n' "$((10#${number#r}))" "$number"; done | sort | cut -d' ' -f2
}

# Where each date's files come from, per format: the last revision of its release that carries the format, or, for a
# release without it, the revision the date before took it from, so that a diff spans the gap instead of deleting and
# re-adding every file. Genericode starts in 2021, so the earliest dates have none.
xlsx_from=()
gc_from=()
from_xlsx=
from_gc=
for release in "${dates[@]}"; do
  for number in $(revisions_of "$release"); do
    if git cat-file -e "$source_commit:$releases/$release/$number/normalized/xlsx" 2>/dev/null; then
      from_xlsx=$release/$number
    fi
    if git cat-file -e "$source_commit:$releases/$release/$number/normalized/gc" 2>/dev/null; then
      from_gc=$release/$number
    fi
  done
  xlsx_from+=("$from_xlsx")
  gc_from+=("$from_gc")
done

# The files a directory of a commit holds, as Git lists them, and those it must hold for a date.
listing() {
  git ls-tree "$1:$2" 2>/dev/null || true
}
expected() {
  if [ -n "$1" ]; then
    git ls-tree "$source_commit:$releases/$1/normalized/$2" | awk -F'\t' '$2 != "source.json"'
  fi
}

# The commits the branch has, with the date and the revisions each records.
commits=()
recorded_release=()
recorded_xlsx=()
recorded_gc=()
old_tip=
if git show-ref --verify --quiet "refs/heads/$branch"; then
  if git worktree list --porcelain | grep -qx "branch refs/heads/$branch"; then
    echo "Branch $branch is checked out; switch to another branch first." >&2
    exit 1
  fi
  old_tip=$(git rev-parse "refs/heads/$branch")
  ours=
  while IFS='|' read -r commit release xlsx gc revision; do
    commits+=("$commit")
    recorded_release+=("$release")
    recorded_xlsx+=("$xlsx")
    recorded_gc+=("$gc")
    if [ -n "$release$revision" ]; then ours=1; fi
  done < <(git log --reverse --format='%H|%(trailers:key=Code-List-Release,valueonly,separator=)|%(trailers:key=Spreadsheet-From,valueonly,separator=)|%(trailers:key=Genericode-From,valueonly,separator=)|%(trailers:key=Code-List-Revision,valueonly,separator=)' "$branch")
  if [ -z "$ours" ]; then
    echo "Branch $branch exists but was not built by this script; choose another name with --branch." >&2
    exit 1
  fi
fi

# Keep every commit that is still right: the same date, from the same revisions, with the same files. Nothing is ever
# added twice; what follows the first commit that is no longer right is built anew.
keep=0
while [ $keep -lt ${#commits[@]} ] && [ $keep -lt ${#dates[@]} ]; do
  commit=${commits[$keep]}
  if [ "${recorded_release[$keep]}" != "${dates[$keep]}" ] || [ "${recorded_xlsx[$keep]}" != "${xlsx_from[$keep]}" ] ||
      [ "${recorded_gc[$keep]}" != "${gc_from[$keep]}" ] ||
      [ "$(listing "$commit" xlsx)" != "$(expected "${xlsx_from[$keep]}" xlsx)" ] ||
      [ "$(listing "$commit" gc)" != "$(expected "${gc_from[$keep]}" gc)" ]; then
    break
  fi
  keep=$((keep + 1))
done
if [ $keep -eq ${#commits[@]} ] && [ $keep -eq ${#dates[@]} ]; then
  echo "Branch $branch is up to date: ${#dates[@]} effective dates, the latest ${dates[${#dates[@]} - 1]}."
  exit 0
fi
rewritten=
if [ $keep -lt ${#commits[@]} ]; then rewritten=1; fi

# A worktree and a branch of its own, so that the history is built without touching this checkout, and the branch
# is moved only once everything is built.
work=$(mktemp -d "${TMPDIR:-/tmp}/code-history.XXXXXX")
worktree=$work/tree
building="code-history-building-$$"
cleanup() {
  git worktree remove --force "$worktree" >/dev/null 2>&1 || true
  git worktree prune
  git branch -D -q "$building" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT
if [ $keep -gt 0 ]; then
  git worktree add --quiet -b "$building" "$worktree" "${commits[$keep - 1]}"
else
  git worktree add --quiet --orphan -b "$building" "$worktree"
fi
export CODE_HISTORY_BUILDING=1  # Tells the hook that these commits are this script's own.

# A field of a source.json, as the normalizer writes it: one "key" : "value" per line.
field() {
  sed -n "s/^  \"$2\" : \"\(.*\)\",\{0,1\}$/\1/p" "$1" | head -1
}

# The manifest of a revision's format, written to a file.
manifest() {
  git show "$source_commit:$releases/$1/normalized/$2/source.json" > "$work/source.json"
  echo "$work/source.json"
}

# Replaces a directory of the worktree with a revision's files of that format, or removes it.
fill() {
  rm -rf "${worktree:?}/$1"
  if [ -z "$2" ]; then return 0; fi
  rm -rf "$work/extract"
  mkdir -p "$work/extract" "$worktree/$1"
  git archive "$source_commit" "$releases/$2/normalized/$1" | tar -x -C "$work/extract"
  for file in "$work/extract/$releases/$2/normalized/$1"/*; do
    if [ "$(basename "$file")" != source.json ]; then cp "$file" "$worktree/$1/"; fi
  done
}

# One row of RELEASE.md and one line of the commit message for a format of the date.
describe() {
  local directory=$1 label=$2 from=$3 release=$4 file path
  if [ -z "$from" ]; then return 0; fi
  if [ "${from%%/*}" != "$release" ]; then
    echo "| \`$directory/\` | not published with this release; the files of $from remain | | |" >> "$worktree/RELEASE.md"
    echo "$label: not published with this release; the files of $from remain" >> "$message"
    return 0
  fi
  file=$(manifest "$from" "$directory")
  path=$(field "$file" source_path | sed 's/ /%20/g')
  printf '| `%s/` | [%s](%s) ([copy](%s/%s)) | %s | `%s` |\n' "$directory" "$(field "$file" source_filename)" \
    "$(field "$file" source_url)" "$downloader" "$path" "${from##*/}" "$(field "$file" source_sha256)" \
    >> "$worktree/RELEASE.md"
  printf '%s: %s (%s, sha256 %s)\n' "$label" "$(field "$file" source_filename)" "${from##*/}" \
    "$(field "$file" source_sha256)" >> "$message"
}

message=$work/message
i=$keep
while [ $i -lt ${#dates[@]} ]; do
  release=${dates[$i]}
  date=${release#*_}
  fill xlsx "${xlsx_from[$i]}"
  fill gc "${gc_from[$i]}"

  cat > "$worktree/README.md" <<EOF
# EN 16931 code lists, effective date by effective date

> **Unofficial showcase.** The authoritative code lists are those the European Commission publishes in its
> [Registry of supporting artefacts to implement EN16931]($registry).
> Where this branch differs, the Registry prevails.

Each commit of this branch is one effective date of the EN 16931 code lists, oldest first, holding the last revision
of that date's release, so that \`git diff\` between two commits shows what changed between them:

- \`xlsx/\`: every sheet of the "EN16931 code lists values" workbook as CSV, the code lists sorted by code;
- \`gc/\`: the Genericode files, from 2021, normalized: codes in base-36 order, formatting unified;
- \`RELEASE.md\`: where the files of the commit come from, and which earlier revisions they replace.

A release that did not republish the Genericode files keeps those of the last one that did, as its \`RELEASE.md\`
states. Every commit is dated by its effective date and tagged \`code-lists-<release>\`, for example:

    git diff code-lists-16_2025-11-15 code-lists-17_2026-05-15 -- gc/Currency.gc
    git log --oneline -- xlsx/ICD.csv

Built by \`build-history-branch.sh\` of [EU-Codelist-Normalizer](https://github.com/svanteschubert/EU-Codelist-Normalizer)
from its \`src/test/resources/\`. The code lists remain the European Commission's; the repository's license does not
apply to them.
EOF

  {
    echo "# $release"
    echo
    echo "The EN 16931 code lists in force from $date: release ${release%%_*}, normalized, in its last revision."
    echo
    echo "| Directory | Source | Revision | SHA-256 |"
    echo "| --- | --- | --- | --- |"
  } > "$worktree/RELEASE.md"
  {
    echo "$release: EN 16931 code lists effective $date"
    echo
  } > "$message"
  describe xlsx Spreadsheet "${xlsx_from[$i]}" "$release"
  describe gc Genericode "${gc_from[$i]}" "$release"

  # The revisions this date's files replace: published, then corrected within the same release.
  replaced=
  for number in $(revisions_of "$release"); do
    if [ "$release/$number" = "${xlsx_from[$i]}" ] || [ "$release/$number" = "${gc_from[$i]}" ]; then continue; fi
    sources=
    for format in xlsx gc; do
      if git cat-file -e "$source_commit:$releases/$release/$number/normalized/$format" 2>/dev/null; then
        file=$(manifest "$release/$number" "$format")
        sources="$sources${sources:+, }$(field "$file" source_filename) (sha256 $(field "$file" source_sha256))"
      fi
    done
    replaced="$replaced$number: $sources"$'\n'
  done
  if [ -n "$replaced" ]; then
    {
      echo
      echo "Earlier revisions of this release, replaced by the files above:"
      echo
      printf '%s' "$replaced" | sed 's/^/- /'
    } >> "$worktree/RELEASE.md"
    printf '%s' "$replaced" | sed 's/^/Replaced revision /' >> "$message"
  fi
  {
    echo
    echo "Code-List-Release: $release"
    echo "Effective-Date: $date"
    echo "Spreadsheet-From: ${xlsx_from[$i]}"
    if [ -n "${gc_from[$i]}" ]; then echo "Genericode-From: ${gc_from[$i]}"; fi
    echo "Source-Commit: $source_commit"
  } >> "$message"

  git -C "$worktree" add --all
  # Noon UTC, so that the effective date shows as the same day in every time zone.
  GIT_AUTHOR_DATE="${date}T12:00:00Z" git -C "$worktree" commit --quiet --allow-empty -F "$message"
  echo "$(git -C "$worktree" log -1 --format=%h) $release $(git -C "$worktree" show --stat --format= HEAD | tail -1)"
  i=$((i + 1))
done

# Move the branch only now that everything is built, and point every date's tag at its commit.
new_tip=$(git -C "$worktree" rev-parse HEAD)
if [ -n "$old_tip" ]; then
  git update-ref "refs/heads/$branch" "$new_tip" "$old_tip"
else
  git update-ref "refs/heads/$branch" "$new_tip" ""
fi
history=()
while IFS= read -r commit; do history+=("$commit"); done < <(git rev-list --reverse "$new_tip")
created=()
moved=()
removed=()
i=$keep
while [ $i -lt ${#dates[@]} ]; do
  tag="code-lists-${dates[$i]}"
  if ! git show-ref --verify --quiet "refs/tags/$tag"; then
    git tag "$tag" "${history[$i]}"
    created+=("$tag")
  elif [ "$(git rev-parse "$tag^{commit}")" != "${history[$i]}" ]; then
    git tag -f "$tag" "${history[$i]}" >/dev/null
    moved+=("$tag")
  fi
  i=$((i + 1))
done
# Tags of commits the branch no longer holds, such as those of an earlier build, go with them.
if [ -n "$rewritten" ]; then
  while IFS= read -r tag; do
    if [ -n "$tag" ] && ! git merge-base --is-ancestor "$(git rev-parse "$tag^{commit}")" "$new_tip"; then
      git tag -d "$tag" >/dev/null
      removed+=("$tag")
    fi
  done < <(git tag --list 'code-lists-*' --merged "$old_tip")
fi

# The tags as refs to push, nothing for none.
refs() {
  for tag in "$@"; do printf ' refs/tags/%s' "$tag"; done
}

echo "Branch $branch: ${#dates[@]} effective dates, the latest ${dates[${#dates[@]} - 1]}."
if [ -n "$rewritten" ]; then
  if [ $keep -lt ${#dates[@]} ]; then
    echo "Rebuilt from ${dates[$keep]}, which replaces published commits. Publish with:"
  else
    echo "Dropped the commits of dates no longer in the release data, which were published. Publish with:"
  fi
  echo "  git push --force-with-lease origin $branch"
  if [ ${#removed[@]} -gt 0 ]; then echo "  git push origin --delete ${removed[*]}"; fi
  if [ $((${#created[@]} + ${#moved[@]})) -gt 0 ]; then
    echo "  git push --force origin$(refs ${created[@]+"${created[@]}"} ${moved[@]+"${moved[@]}"})"
  fi
else
  echo "Publish with:"
  echo "  git push origin $branch$(refs ${created[@]+"${created[@]}"} ${moved[@]+"${moved[@]}"})"
fi
