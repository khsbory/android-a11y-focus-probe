# Security

Use only on devices and apps you own or have permission to test. The Android accessibility service can read UI content and perform actions. Use synthetic data; avoid credential, payment, and other sensitive screens. Built-in guards are not a security boundary.

Use the loopback server only through a local ADB forward on a trusted workstation with a fresh per-session token. Do not share tokens, log captures, device IDs, APK signing keys, or real user data. Remove the ADB forward and disable the service after use. Generate local signing keys for development. Never redistribute debug keys or use them for production signing.

Report security issues privately to [AccessBridge](mailto:info@accessbridge.co.kr). Include the affected version and a synthetic description; do not attach credentials or live screen/speech captures.
