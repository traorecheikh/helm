#!/bin/sh
# Local checks for install.sh. shellcheck and Docker are used only if present.
set -u
here=$(cd "$(dirname "$0")" && pwd)
fail=0

if command -v shellcheck >/dev/null 2>&1; then
  if shellcheck -s sh "$here/install.sh" "$here/test.sh"; then echo "shellcheck: ok"; else fail=1; fi
else
  echo "shellcheck: not installed, skipped (brew install shellcheck)"
fi

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  for image in ubuntu:24.04 debian:12; do
    echo "== $image: install --dry-run"
    docker run --rm -v "$here:/i:ro" "$image" sh /i/install.sh --dry-run --yes --domain helm.example.com || fail=1
    echo "== $image: uninstall --dry-run"
    docker run --rm -v "$here:/i:ro" "$image" sh /i/install.sh --dry-run --yes --uninstall || fail=1
  done
else
  echo "docker: daemon not reachable, container dry-runs skipped"
fi

exit "$fail"
