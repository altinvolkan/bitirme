#!/usr/bin/env bash
# sender paketini gomobile ile Android AAR'ına çevirir ve Android projesine koyar.
# Önkoşullar: setup.sh çalışmış olmalı; Android SDK + NDK kurulu (ANDROID_HOME).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
cd "$here"

export PATH="$PATH:$(go env GOPATH)/bin"
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
go get golang.org/x/mobile/bind

mkdir -p ../android/app/libs
gomobile bind \
  -target="${TARGETS:-android/arm64}" \
  -androidapi 30 \
  -o ../android/app/libs/airplay.aar \
  screenmirror/airplay/sender

echo "Üretildi: mirror/android/app/libs/airplay.aar (Android Studio'da Gradle sync yapın)"
