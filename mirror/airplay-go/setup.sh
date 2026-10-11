#!/usr/bin/env bash
# doubletake'in AirPlay protokol paketini (LGPL-3.0) sabit bir commit'ten çekip
# _overlay/ içindeki kendi dosyalarımızla birleştirir ve derler.
#
# Kendi cihazlarınız üzerinde kişisel kullanım içindir. Upstream kodu repoya
# işlenmez (.gitignore), her makinede bu betikle yeniden üretilir.
set -euo pipefail

UPSTREAM=https://github.com/omarroth/doubletake.git
COMMIT=5e2f1cbe3e1070bcefec8e549c7baf884727bc00   # 2026-10-04, incelenen sürüm

here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

git clone -q "$UPSTREAM" "$tmp/up"
git -C "$tmp/up" checkout -q "$COMMIT"

mkdir -p "$here/internal/airplay"
find "$tmp/up/internal/airplay" -maxdepth 1 -name '*.go' ! -name '*_test.go' \
  -exec cp {} "$here/internal/airplay/" \;
cp "$tmp/up/LICENSE" "$tmp/up/COPYING.GPL" "$tmp/up/go.sum" "$here/"
sed 's#^module .*#module screenmirror/airplay#' "$tmp/up/go.mod" > "$here/go.mod"

# Kendi dosyalarımız
cp -R "$here/_overlay/." "$here/"

cd "$here"
go mod tidy
go build ./...
go vet ./sender/... ./cmd/...
echo "Hazır. Linux'ta deneme için: bkz. cmd/h264send/main.go. Android için: ./build-aar.sh"
