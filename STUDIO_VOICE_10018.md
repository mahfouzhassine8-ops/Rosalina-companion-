# Rosalina 10018 — optional Studio Voice / Local Mac Accelerator

## Scope and acceptance

This is an additive speech-adapter candidate, not completion or acceptance of the entire 10018 brief. The protected 10015 source is unchanged. The existing 10018 UI, avatar, phone engines, RAM guard and model packs are reused. The unresolved Chatterbox Android timeout and premium avatar-artwork/physical Fold acceptance remain separate open items.

Studio Voice is off by default. Enabling it authorizes only the current reply clause plus the explicitly selected voice/style parameters to reach the configured private service. No microphone recording, user prompt, conversation history, system prompt or model files are uploaded by this adapter. The Mac must use a local VoiceStudio engine; the phone cannot prove that a remotely configured server has not selected a cloud engine or retained its inputs.

Studio preference is separate from enabling auditions. With Studio preferred, a failed connection uses phone speech and never silently escalates to the separately configured online provider. An already-played clause is not replayed from its beginning after a mid-stream failure; the text stays visible and subsequent clauses use the phone. This avoids duplicate speech but is not sample-accurate mid-clause recovery.

## What is implemented

Settings supports a manual HTTPS root, engine and voice IDs, an origin-bound encrypted API-key/PIN vault, explicit consent, connection testing without speech, controlled identical-phrase audition, and a separate prefer-for-replies switch. User-started discovery can find the optional companion advertisement; discovering an address never enables or authenticates it automatically.

The adapter requests 24 kHz signed 16-bit little-endian mono PCM and plays bounded chunks through AudioTrack as they arrive. Playback-head evidence drives the existing Performance State and PCM-based mouth estimator. This is measured audio articulation, not phoneme-accurate lip sync or proof of what the listener heard. Unsupported response formats and redirects are rejected. Stop closes playback and the client connection. Whether the Mac engine itself immediately stops inference after disconnect depends on that engine/server implementation.

HTTPS uses Android's normal trusted-certificate and hostname verification. The actual connected peer must be RFC1918, IPv6 ULA, or a 100.64.0.0/10 private-overlay address. Public, loopback, link-local and multicast destinations are rejected. No insecure trust-all switch is supplied. Credentials are separate from online-provider credentials and authenticated with the exact configured origin. Diagnostics do not include keys, PINs, server bodies or reply text.

Timeouts are bounded: 2-second connect, 2.5-second network read gap, 6-second first-audio deadline, 90-second overall transfer, at most 60 seconds of PCM and one network worker. A failed Studio speech attempt gets a 60-second cooldown. A slow/cold Mac engine may therefore fall back; warm and audition the chosen engine before preferring it.

## Mac connection setup

The integration does not install or configure software on your Mac automatically. The supplied helper is Python source, not a notarized macOS application, and requires Python 3.10 or newer.

1. Install and open VoiceStudio separately using its official macOS instructions. Select an installed **local** speech engine and voice. First verify that they speak acceptably on the Mac. Keep its backend bound to loopback; the documented default port is 3900.
2. In this repository's `tools/studio` directory, run `python3 studio_gateway.py`. It binds only `127.0.0.1:3902`, forwards to `127.0.0.1:3900`, and permits only speech and voice-list routes. It does not expose VoiceStudio settings, model management, uploads or transcription. Use `--upstream-port` only when the actual local backend uses another port.
3. In another terminal, run `python3 studio_gateway.py --show-key` once to reveal your private gateway pairing key. Do not publish it or paste it into logs. It persists in `~/.config/rosalina-studio/gateway-key` with mode 600. If VoiceStudio itself requires its own API key/PIN, set `OMNIVOICE_API_KEY` and `OMNIVOICE_SHARE_PIN` only in the gateway's local environment. Those administration-capable credentials never go to the phone.
4. Expose **only the gateway**, not VoiceStudio's full backend, through a private HTTPS reverse proxy. One option is Tailscale on both devices with `tailscale serve 3902` on the Mac. Complete its HTTPS consent flow and use the displayed `https://...ts.net` address. Serve is private to the tailnet; do not use Funnel or router port forwarding. Restrict tailnet access to your own authorized devices. A LAN-only trusted HTTPS deployment is also supported; raw HTTP or an untrusted self-signed certificate is intentionally rejected.
5. In Rosalina Settings, open **Studio Voice**, enter that HTTPS root and the gateway pairing key. Leave the PIN empty when using the gateway. Keep `tts-1` and `default` only when those aliases select the intended active engine/voice; otherwise enter its actual IDs. Check consent, save and test the connection, and audition the identical phrases. Only then enable the separate preference to use Studio for replies.

The optional `advertise-mac-studio.command` accepts the already-configured HTTPS root and advertises `_rosalina-studio._tcp` on the local network while it remains open. Run it with `bash advertise-mac-studio.command 'https://your-configured-host'`. The phone's Find button scans for eight seconds. The advertisement is only an address hint; API authentication and TLS validation are still required. Native VoiceStudio advertisement support is not assumed. Manual entry works without discovery and across a private overlay.

## Style handling

Default style mode sends no instructions. `OmniVoice design tags` sends a small documented tag vocabulary rather than unsupported prose. `Free-text instructions` is an explicit opt-in for engines that really support it. The shared Performance State supplies pace, volume and bounded delivery instructions, but the audible effect depends on the selected engine. No claim is made that whisper, sigh, laugh or every emotion is supported or convincing. Cloning/design is configured in VoiceStudio using authorized material; this addition does not automatically create or clone a voice.

## Testing boundaries

The local unit suite covers policy, addresses, payload limits, odd-byte PCM, no replay, redacted errors, timeouts and transport cancellation. The Android fixture tests use synthetic PCM and an ephemeral test-only certificate to exercise AudioTrack, encrypted storage, streamed TLS and hostname rejection. Fixture certificate material is only in the instrumentation APK; no test trust configuration exists in the release application.

A focused Studio workflow does not clear the inherited failed Chatterbox acceptance test. The original complete-companion workflow is retained. Actual VoiceStudio interoperability on the user's Mac, perceived voice quality, latency under load, Mac sleep/disconnect, Bluetooth/calls, folding and sustained thermal behavior still require physical-device testing. No new primary local phone voice is promoted by this addition.

## Sources checked 2026-09-29

- VoiceStudio API contract: https://github.com/debpalash/VoiceStudio/blob/main/docs/agentic-voice.md
- Authentication and PIN gates: https://github.com/debpalash/VoiceStudio/blob/main/docs/api-auth.md
- macOS installation: https://github.com/debpalash/VoiceStudio/blob/main/docs/install/macos.md
- Private HTTPS Serve: https://tailscale.com/docs/features/tailscale-serve

These independent adapter/helper files use the documented API; no VoiceStudio engine code, model, voice reference or proprietary provider asset is embedded here. Third-party software and models keep their own licenses and terms.
