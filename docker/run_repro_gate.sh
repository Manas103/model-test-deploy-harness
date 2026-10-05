#!/bin/bash
# Real Docker demonstration of the promotion gate: build one image, pin every run to its exact
# digest, run each of the 6 job modes twice, and compare sha256(stdout) across the two runs.
# Byte-identical (gate passes) is expected for the 3 "fixed" modes; a digest mismatch (gate
# catches it, promotion refused) is expected for the 3 "buggy" modes.
# Run from inside WSL2 (Docker Desktop is not configured on the Windows side of this machine):
#   wsl.exe -d Ubuntu-22.04 --cd "<this dir, /mnt/c/... path>" -- bash run_repro_gate.sh
set -euo pipefail
cd "$(dirname "$0")"

IMAGE_TAG="model-test-deploy-harness-job:local"
docker build -q -t "$IMAGE_TAG" . > /dev/null

DIGEST=$(docker inspect --format='{{.Id}}' "$IMAGE_TAG")
echo "Pinned container digest: $DIGEST"
echo

MODES=(buggy_unordered_iteration fixed_ordered_iteration buggy_unseeded_rng fixed_seeded_rng buggy_wallclock_read fixed_no_wallclock)

PASS_COUNT=0
CAUGHT_COUNT=0

for mode in "${MODES[@]}"; do
    OUT1=$(docker run --rm "$IMAGE_TAG" "$mode")
    OUT2=$(docker run --rm "$IMAGE_TAG" "$mode")
    HASH1=$(printf '%s' "$OUT1" | sha256sum | cut -d' ' -f1)
    HASH2=$(printf '%s' "$OUT2" | sha256sum | cut -d' ' -f1)

    if [[ "$HASH1" == "$HASH2" ]]; then
        RESULT="BYTE_IDENTICAL (gate passes, promotion allowed)"
        PASS_COUNT=$((PASS_COUNT + 1))
    else
        RESULT="MISMATCH (gate refuses promotion)"
        CAUGHT_COUNT=$((CAUGHT_COUNT + 1))
    fi

    echo "mode=$mode"
    echo "  run1 output: $OUT1"
    echo "  run1 sha256: $HASH1"
    echo "  run2 output: $OUT2"
    echo "  run2 sha256: $HASH2"
    echo "  result: $RESULT"
    echo
done

echo "Summary: $PASS_COUNT of 6 modes byte-identical, $CAUGHT_COUNT of 6 modes caught as nondeterministic"
echo "Expected: 3 byte-identical (the fixed_* modes), 3 caught (the buggy_* modes)"
