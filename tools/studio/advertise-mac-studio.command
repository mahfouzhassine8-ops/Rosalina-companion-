#!/bin/bash
# Optional foreground discovery advertisement; never contains a PIN or API key.
set -euo pipefail
if [ "$(uname -s)" != Darwin ]; then echo 'Run this helper on your Mac.' >&2; exit 1; fi
if [ "$#" -ne 1 ]; then echo 'Usage: bash advertise-mac-studio.command https://your-private-studio-host' >&2; exit 1; fi
python3 - "$1" <<'PY'
import sys,urllib.parse
u=urllib.parse.urlsplit(sys.argv[1])
if u.scheme!='https' or not u.hostname or u.username or u.password or u.query or u.fragment or u.path not in ('','/') or len(sys.argv[1])>240:
    raise SystemExit('Use the private HTTPS service root, with no credentials or extra path.')
PY
exec /usr/bin/dns-sd -R 'Rosalina Studio Voice' _rosalina-studio._tcp local 443 "url=${1%/}"
