#!/usr/bin/env python3
"""The simulated backtest job the harness pins to a data vintage and container digest.

Six modes, three deliberately nondeterministic (what the byte-identical re-run gate is supposed
to catch) and three fixed. Each mode prints a small "result" to stdout; the gate hashes stdout
and compares two independent runs of the exact same image.
"""
import hashlib
import random
import sys
import time

DATA_VINTAGE = "vintage-2026-09-15"  # pinned, same every run; stands in for the real input data


def buggy_unordered_iteration():
    # A set's iteration order depends on PYTHONHASHSEED, which Python randomizes per process by
    # default (PEP 456): the same elements, iterated in a different order on every run.
    ids = {"order-883", "order-104", "order-552", "order-771", "order-219", "order-940"}
    return ",".join(ids)


def fixed_ordered_iteration():
    ids = {"order-883", "order-104", "order-552", "order-771", "order-219", "order-940"}
    return ",".join(sorted(ids))


def buggy_unseeded_rng():
    random.seed()  # explicit: the default, process-time-derived seed
    return ",".join(str(random.randint(0, 999999)) for _ in range(5))


def fixed_seeded_rng():
    random.seed(DATA_VINTAGE)  # deterministic: derived from the pinned input, not wall-clock
    return ",".join(str(random.randint(0, 999999)) for _ in range(5))


def buggy_wallclock_read():
    return f"processed-at:{time.time()}"


def fixed_no_wallclock():
    return f"processed-vintage:{DATA_VINTAGE}"


MODES = {
    "buggy_unordered_iteration": buggy_unordered_iteration,
    "fixed_ordered_iteration": fixed_ordered_iteration,
    "buggy_unseeded_rng": buggy_unseeded_rng,
    "fixed_seeded_rng": fixed_seeded_rng,
    "buggy_wallclock_read": buggy_wallclock_read,
    "fixed_no_wallclock": fixed_no_wallclock,
}

if __name__ == "__main__":
    mode = sys.argv[1]
    if mode not in MODES:
        print(f"unknown mode {mode}, expected one of {list(MODES)}", file=sys.stderr)
        sys.exit(2)
    print(MODES[mode]())
