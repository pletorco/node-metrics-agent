#!/usr/bin/env bash
# Prints the CHANGELOG.md section of one release: the text under "## [<version>]" up to the next
# "## [" heading. The release workflow publishes it as the GitHub release notes.
#
# Usage: scripts/release-notes.sh <version>      e.g. scripts/release-notes.sh 0.10.0
# Env:   CHANGELOG  file to read (default: CHANGELOG.md in the repository root).
# Fails when the version has no section or the section is empty, so a release cannot go out
# without notes.
set -euo pipefail

VERSION="${1:?usage: release-notes.sh <version>}"
FILE="${CHANGELOG:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/CHANGELOG.md}"

NOTES="$(awk -v heading="## [${VERSION}]" '
  /^## \[/ {
    if (found) exit
    if (index($0, heading) == 1) found = 1
    next
  }
  found { print }
' "$FILE")"

# Trim the blank lines around the section.
NOTES="$(printf '%s\n' "$NOTES" | sed -e :a -e '/^[[:space:]]*$/{$d;N;ba' -e '}' | sed '/./,$!d')"

if [ -z "$NOTES" ]; then
  echo "release-notes.sh: no CHANGELOG.md section for version ${VERSION}" >&2
  exit 1
fi
printf '%s\n' "$NOTES"
