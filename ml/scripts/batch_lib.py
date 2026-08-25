"""Shared batching/rate-limiting/durability helpers for ml/ generation
scripts (scale_seed_examples.py, generate_catalog_mood_tags.py, and future
ones) - pulled out so the pattern isn't copy-pasted per script.
"""

import json
import sqlite3
import time
from collections import deque


class RateLimiter:
    """Sliding-window limiter: at most max_calls per window_secs. One LM
    call == one unit here, regardless of how much work that call does."""

    def __init__(self, max_calls: int, window_secs: float):
        self.max_calls = max_calls
        self.window_secs = window_secs
        self.call_times: deque[float] = deque()

    def wait(self) -> None:
        now = time.monotonic()
        while self.call_times and now - self.call_times[0] > self.window_secs:
            self.call_times.popleft()
        if len(self.call_times) >= self.max_calls:
            sleep_for = self.window_secs - (now - self.call_times[0])
            if sleep_for > 0:
                time.sleep(sleep_for)
        self.call_times.append(time.monotonic())


class Checkpoint:
    """SQLite-backed durability: one commit per resolved key, so an
    interrupted run resumes instead of restarting. Failed keys are never
    written here, so they're naturally retried on the next run - callers
    just need to filter their work list against `done()` before starting."""

    def __init__(self, db_path: str, table: str = "checkpoint"):
        self.table = table
        self.con = sqlite3.connect(db_path)
        # WAL + tuned pragmas (the config popularized by BigBinary/Rails 7.1's
        # sqlite3 adapter defaults) - WAL lets readers and the writer proceed
        # concurrently instead of locking the whole file per write, which
        # matters here since checkpoint commits happen once per title.
        self.con.execute("PRAGMA journal_mode = WAL")
        self.con.execute("PRAGMA synchronous = NORMAL")
        self.con.execute("PRAGMA busy_timeout = 5000")
        self.con.execute("PRAGMA foreign_keys = ON")
        self.con.execute("PRAGMA temp_store = MEMORY")
        self.con.execute("PRAGMA cache_size = -20000")  # ~20MB page cache, negative = KB
        self.con.execute(
            f"""CREATE TABLE IF NOT EXISTS {table} (
                key TEXT PRIMARY KEY,
                value_json TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )"""
        )
        self.con.commit()

    def done(self) -> set[str]:
        return {row[0] for row in self.con.execute(f"SELECT key FROM {self.table}")}

    def save(self, key: str, value) -> None:
        self.con.execute(
            f"INSERT OR REPLACE INTO {self.table} (key, value_json, updated_at) VALUES (?, ?, ?)",
            (key, json.dumps(value), time.strftime("%Y-%m-%dT%H:%M:%S")),
        )
        self.con.commit()

    def close(self) -> None:
        self.con.close()
