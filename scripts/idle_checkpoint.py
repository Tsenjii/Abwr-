"""Measure a fresh uninterrupted idle interval without polling the browser."""
import time


def idle_for(seconds, *, clock=None, pause=None):
    if isinstance(seconds, bool) or not isinstance(seconds, (int, float)) or not 1 <= seconds <= 1800:
        raise ValueError('Idle interval must be 1–1800 seconds')
    clock = clock or time.monotonic
    pause = pause or time.sleep
    started = clock()
    while True:
        elapsed = clock() - started
        remaining = seconds - elapsed
        if remaining <= 0:
            return elapsed
        pause(min(15, remaining))
