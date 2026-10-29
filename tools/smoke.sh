#!/usr/bin/env bash
# A throwaway repository with a tag and two commits: the changelog must cover exactly those two.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

dir="$(mktemp -d)"
(
  cd "$dir"
  git init -q -b main
  git config user.email smoke@local
  git config user.name Smoke
  printf 'a\n' > a.txt; git add a.txt; git commit -q -m "first commit"
  git tag v1.0.0
  printf 'b\n' > b.txt; git add b.txt; git commit -q -m "add b"
  printf 'c\n' > c.txt; git add c.txt; git commit -q -m "add c"
)
printf '{"label": "Changelog"}' >"$dir/input.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "run failed: $(cat "$dir/log.txt")"
[ "$(status "$dir")" = "success" ] || fail "not a success: $(cat "$dir/output.json")"
[ "$(output "$dir" commits)" = "2" ] || fail "commit count: $(cat "$dir/output.json")"
[ "$(output "$dir" since)" = "v1.0.0" ] || fail "since: $(cat "$dir/output.json")"
grep -q "add b" "$dir/CHANGELOG.md" || fail "CHANGELOG.md misses a commit"
grep -q "first commit" "$dir/CHANGELOG.md" && fail "the tagged commit must not be in the range"
[ -f "$dir/out/changelog/index.html" ] || fail "no HTML report"

# an explicit range is honoured
dir2="$(mktemp -d)"
(
  cd "$dir2"
  git init -q -b main
  git config user.email smoke@local
  git config user.name Smoke
  printf '1\n' > f; git add f; git commit -q -m "one"
  printf '2\n' >> f; git commit -qam "two"
  printf '3\n' >> f; git commit -qam "three"
)
printf '{"since": "HEAD~2", "label": "Range"}' >"$dir2/input.json"
run "$dir2" >/dev/null 2>&1 || fail "range run failed"
[ "$(output "$dir2" commits)" = "2" ] || fail "HEAD~2..HEAD must be 2 commits: $(cat "$dir2/output.json")"

# not a repository is a clear error
dir3="$(mktemp -d)"
printf '{}' >"$dir3/input.json"
run "$dir3" >/dev/null 2>&1 && fail "a non-repository must fail"
grep -q "not a git repository" "$dir3/output.json" || fail "message not readable"

echo "smoke ok: last tag range, explicit range, non-repository"
