#!/usr/bin/env bash
# One-time Fliq release setup. Run on YOUR computer, inside a clone of the repo, after `gh auth login`.
# Creates the signing key, stores the 4 GitHub secrets, then tags a release.
# Usage: ./scripts/setup-release.sh [tag]      (default tag: v0.3.0)
set -euo pipefail
REPO="Aasimabdul/filq"
TAG="${1:-v0.3.0}"
KS="fliq-release.jks"
ALIAS="fliq"

for c in keytool gh base64 git; do command -v "$c" >/dev/null || { echo "Missing: $c"; exit 1; }; done
gh auth status >/dev/null || { echo "Run: gh auth login"; exit 1; }
[ -e "$KS" ] && { echo "$KS already exists here. Move it away first (never overwrite a key you have released with)."; exit 1; }

read -r -s -p "Choose a keystore password (min 6 chars): " PW; echo
read -r -s -p "Repeat it: " PW2; echo
[ "$PW" = "$PW2" ] && [ ${#PW} -ge 6 ] || { echo "Passwords differ or too short."; exit 1; }
export PW

keytool -genkeypair -keystore "$KS" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass:env PW -keypass:env PW -dname "CN=Fliq, O=Sim"

base64 -w0 "$KS" | gh secret set KEYSTORE_B64 --repo "$REPO"
printf '%s' "$PW" | gh secret set KEYSTORE_PASSWORD --repo "$REPO"
printf '%s' "$ALIAS" | gh secret set KEY_ALIAS --repo "$REPO"
printf '%s' "$PW" | gh secret set KEY_PASSWORD --repo "$REPO"

echo
echo "Secrets saved. BACK UP $KS and the password now (cloud drive + pendrive). If you lose them you can never update the app."
read -r -p "Create and push tag $TAG to start the release build? [y/N] " A
if [ "$A" = "y" ] || [ "$A" = "Y" ]; then
  git tag "$TAG" && git push origin "$TAG"
  echo "Release building: https://github.com/$REPO/actions"
fi
