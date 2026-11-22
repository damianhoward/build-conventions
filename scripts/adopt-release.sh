#!/usr/bin/env bash
# Opens a pull request adopting a build-conventions release in every repository that pins it.
#
# A release changes nothing anywhere until each consumer's pin moves, and moving thirteen pins by
# hand is the step that gets left half done. The release workflow runs this once Maven Central
# serves the version. Each pull request is gated by its own repository's required checks and lands
# through the shared auto-merge, which admits the estate app's pull requests on an
# adopt-build-conventions- branch, and a service redeploys on the merge behind its health gate. A
# consumer the release breaks stays red and unmerged; one that breaks only in production fails
# that gate and rolls back. Tagging the release is the decision a person makes.
#
# It is also how a Temurin update reaches the services that carry their own runtime. Raise
# runtime-jdk-floor and release: every image is relinked, from a new commit, on the JDK the shared
# workflows pin.
#
# Usage: scripts/adopt-release.sh <version>. GH_TOKEN, or gh's own login, must be able to push
# branches and open pull requests in the consumers. ADOPT_DRY_RUN=1 prints each change instead.
set -euo pipefail

VERSION="${1:?usage: adopt-release.sh <version>}"
OWNER=damianhoward
CENTRAL=https://repo.maven.apache.org/maven2/com/damianhoward
BRANCH="adopt-build-conventions-$VERSION"
SEMVER='[0-9]+\.[0-9]+\.[0-9]+'
PIN="build-conventions-catalog:$SEMVER|id 'com\.damianhoward\.[a-z-]+' version '$SEMVER'"
GH_TOKEN="${GH_TOKEN:-$(gh auth token)}"
export GH_TOKEN
# The workflow names the app as the author. Run by hand, the commits carry this repository's own
# identity: the consumers are cloned into a temporary directory, outside any per-directory identity
# mapping, where the global identity may be a different account.
AUTHOR_NAME="${ADOPT_AUTHOR_NAME:-$(git -C "$(dirname "$0")" config user.name)}"
AUTHOR_EMAIL="${ADOPT_AUTHOR_EMAIL:-$(git -C "$(dirname "$0")" config user.email)}"

# The publish step finishing is not the version being served, and a consumer resolving it too early
# fails its build -- trading-desk's CodeQL did exactly that on 0.6.2. Consumers resolve each plugin
# by id through its own marker, and Central does not serve a release's files together: on 0.7.0 the
# kotlin-conventions marker answered while runtime-image's still did not, fifteen minutes later,
# and visitor-analytics' deploy failed to resolve it. So every marker is waited for, named from the
# plugin scripts themselves so a new plugin cannot be missed.
served() { curl -fsS -o /dev/null --max-time 20 "$CENTRAL/$1/$VERSION/$2-$VERSION.pom"; }

PLUGIN_IDS=()
for script in "$(dirname "$0")"/../plugins/src/main/groovy/*.gradle; do
  [ -e "$script" ] && PLUGIN_IDS+=("$(basename "$script" .gradle)")
done

all_served() {
  served build-conventions-plugins build-conventions-plugins &&
    served build-conventions-catalog build-conventions-catalog || return 1
  local id
  for id in "${PLUGIN_IDS[@]}"; do
    served "${id#com.damianhoward.}/$id.gradle.plugin" "$id.gradle.plugin" || return 1
  done
}

await_central() {
  local attempts=90 attempt # 30 minutes at 20 s
  [ "${#PLUGIN_IDS[@]}" -gt 0 ] || { echo "::error::no plugin scripts found to wait for"; exit 1; }
  for attempt in $(seq "$attempts"); do
    if all_served; then
      echo "Maven Central serves $VERSION: both artifacts and ${#PLUGIN_IDS[@]} plugin markers"
      return
    fi
    [ "$attempt" -eq "$attempts" ] || sleep 20
  done
  echo "::error::Maven Central did not serve all of $VERSION within 30 minutes; no consumer was changed"
  exit 1
}

newest() { printf '%s\n' "$@" | sort -V | tail -1; }

# Every step is chained with && rather than left to `set -e`, which stops applying inside a
# function called as an if condition: a push that failed must never be followed by a pull request.
propose() {
  local dir="$1" repo="$2" from="$3"
  shift 3
  (cd "$dir" && sed -i -E \
    -e "s/(build-conventions-catalog:)$SEMVER/\1$VERSION/g" \
    -e "s/(id 'com\.damianhoward\.[a-z-]+' version ')$SEMVER'/\1$VERSION'/g" \
    "$@") || return 1
  if [ "${ADOPT_DRY_RUN:-}" = 1 ]; then
    echo "$repo: would move $from to $VERSION"
    git -C "$dir" --no-pager diff -U0 | grep -E '^[-+][^-+]' | sed 's/^/    /'
    return 0
  fi
  git -C "$dir" switch -q -c "$BRANCH" &&
    git -C "$dir" -c user.name="$AUTHOR_NAME" -c user.email="$AUTHOR_EMAIL" commit -q -am "Adopt build-conventions $VERSION" &&
    git -C "$dir" push -q origin "$BRANCH" &&
    gh pr create --repo "$OWNER/$repo" --head "$BRANCH" --title "Adopt build-conventions $VERSION" --body-file - <<EOF
## What

Moves this repository from build-conventions $from to $VERSION: the convention plugins and the shared catalog together.

## Why

build-conventions $VERSION is released and served by Maven Central: https://github.com/$OWNER/build-conventions/releases/tag/$VERSION. A release changes nothing until each consumer adopts it. Where this repository deploys on merge, merging this redeploys it, and a service carrying its own runtime relinks it from the JDK the shared workflows pin.

## Testing

This repository's required checks build and test it on $VERSION; it merges only if they pass, and a service redeploys behind its health gate, which rolls back a release that does not become ready.
EOF
}

[ "${ADOPT_DRY_RUN:-}" = 1 ] || await_central

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failed=()
opened=()

for repo in $(gh repo list "$OWNER" --no-archived --limit 200 --json name --jq '.[].name' | sort); do
  case "$repo" in build-conventions | .github) continue ;; esac
  dir="$work/$repo"
  if ! git clone -q --depth 1 "https://x-access-token:${GH_TOKEN}@github.com/$OWNER/$repo.git" "$dir" 2>/dev/null; then
    echo "::warning::$repo could not be cloned"
    failed+=("$repo")
    continue
  fi

  mapfile -t files < <(cd "$dir" && git ls-files '*.gradle' | while read -r file; do
    if grep -qE "$PIN" "$file"; then echo "$file"; fi
  done)
  [ "${#files[@]}" -gt 0 ] || continue

  mapfile -t pinned < <(cd "$dir" && grep -ohE "$PIN" "${files[@]}" | grep -oE "$SEMVER" | sort -uV)
  if [ "${pinned[*]}" = "$VERSION" ]; then
    echo "$repo: already on $VERSION"
    continue
  fi
  if [ "$(newest "${pinned[@]}" "$VERSION")" != "$VERSION" ]; then
    echo "$repo: pins ${pinned[*]}, newer than $VERSION; left alone"
    continue
  fi
  if gh pr list --repo "$OWNER/$repo" --head "$BRANCH" --state open --json number --jq '.[0].number' | grep -q .; then
    echo "$repo: an adoption pull request for $VERSION is already open"
    continue
  fi

  if propose "$dir" "$repo" "${pinned[*]}" "${files[@]}"; then
    opened+=("$repo")
  else
    failed+=("$repo")
  fi
done

echo "proposed: ${opened[*]:-none}"
if [ "${#failed[@]}" -gt 0 ]; then
  echo "::error::adoption could not be proposed in: ${failed[*]}"
  exit 1
fi
