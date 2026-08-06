#!/usr/bin/env bash
# Creates a personal release-signing keystore and keystore.properties. Both are gitignored —
# back them up yourself if you want release builds to keep the same signing identity across
# reinstalls (Android treats a differently-signed update as a different app).
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f keystore.properties ]; then
    echo "keystore.properties already exists — remove it first if you want to regenerate." >&2
    exit 1
fi

KEYSTORE_PATH="release.jks"
ALIAS="veil"

read -rsp "New keystore password: " STORE_PASSWORD
echo
read -rsp "Key password (leave blank to reuse the keystore password): " KEY_PASSWORD
echo
KEY_PASSWORD="${KEY_PASSWORD:-$STORE_PASSWORD}"

keytool -genkeypair \
    -keystore "$KEYSTORE_PATH" \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -storepass "$STORE_PASSWORD" \
    -keypass "$KEY_PASSWORD" \
    -dname "CN=Veil, OU=Personal, O=Personal, L=Unknown, ST=Unknown, C=US"

cat > keystore.properties <<EOF
storeFile=$KEYSTORE_PATH
storePassword=$STORE_PASSWORD
keyAlias=$ALIAS
keyPassword=$KEY_PASSWORD
EOF

echo "Wrote $KEYSTORE_PATH and keystore.properties."
