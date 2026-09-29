#!/usr/bin/env bash
set -euo pipefail
D="${RUNNER_TEMP:-/tmp}/rosalina-studio-tls"
mkdir -p "$D" unified/src/androidTest/assets
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -keyout "$D/ca.key" -out "$D/ca.pem" -subj '/CN=Ephemeral Rosalina CI CA' -addext 'basicConstraints=critical,CA:TRUE' >/dev/null 2>&1
for port in 8443 8444; do
  openssl req -new -newkey rsa:2048 -nodes -keyout "$D/$port.key" -out "$D/$port.csr" -subj '/CN=studio-fixture.local' >/dev/null 2>&1
  if [ "$port" = 8443 ]; then printf 'subjectAltName=IP:10.0.2.2\nextendedKeyUsage=serverAuth\n' > "$D/ext"; else printf 'subjectAltName=DNS:wrong-host.local\nextendedKeyUsage=serverAuth\n' > "$D/ext"; fi
  openssl x509 -req -in "$D/$port.csr" -CA "$D/ca.pem" -CAkey "$D/ca.key" -CAcreateserial -out "$D/$port.pem" -days 2 -extfile "$D/ext" >/dev/null 2>&1
  python3 tools/studio/tls_fixture.py --cert "$D/$port.pem" --key "$D/$port.key" --port "$port" > "$D/$port.log" 2>&1 &
  echo "$!" >> "$D/pids"
done
cp "$D/ca.pem" unified/src/androidTest/assets/studio-fixture-ca.pem
# Only the ephemeral public CA enters the test APK; no private keys or trusted CA in the release APK.
