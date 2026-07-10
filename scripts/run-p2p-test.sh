#!/usr/bin/env bash
# Launches a second, isolated instance of EVE Night Trade Tools for exercising the P2P Market
# as both sides of a trade at once, alongside your normal instance (`./gradlew run`).
#
# This instance gets its own database and Nostr identity under .p2p-test-data/ (never touches
# your real character/wallet data) and is auto-seeded with a "P2P Test Trader" character so you
# can post/accept orders immediately — no ESI login needed. Its window title reads
# "EVE Night Trade Tools — P2P TEST" so it's easy to tell apart from the real one.
#
# Both instances talk to the same public Nostr relays as the real app, so orders posted here
# are visible to your main instance (and everyone else's) — treat them as real, not sandboxed.
set -euo pipefail
cd "$(dirname "$0")/.."

export EVENTT_DATA_DIR="$(pwd)/.p2p-test-data"
mkdir -p "$EVENTT_DATA_DIR"

./gradlew run
