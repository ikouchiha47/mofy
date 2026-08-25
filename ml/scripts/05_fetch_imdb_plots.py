"""Fetch IMDb plot summaries by talking to the Browser MCP Chrome extension
directly over WebSocket - no MCP server, no npx, no ceremony.

The extension dials out to ws://localhost:9009 and speaks a plain JSON
protocol:
    -> {"id": "<uuid>", "type": "browser_navigate", "payload": {"url": ...}}
    <- {"type": "messageResponse", "payload": {"requestId": "<id>", "result": ..., "error": ...}}

This script IS the WebSocket server. It takes over port 9009 (killing whatever
listens there, e.g. opencode's MCP server), waits for the extension to connect,
then drives it title by title.

Two extraction modes:
- default: navigate + browser_snapshot, then parse the accessibility snapshot
  for the Plot Summaries and the long Synopsis (works with the stock extension)
- --evaluate "<js>": navigate, then run the JS expression in the page via the
  patched extension's browser_evaluate tool and store the result verbatim
  (e.g. document.querySelector('ul.ipc-metadata-list li:nth-child(1)').innerText)

Output is an upserted JSONL file, one record per title:
    {"imdbID": "tt0780504", "plots": ["summary 1", ...], "synopsis": "..."}
or, with --evaluate, {"imdbID": ..., "plots": <js result>}.

Titles that fail after retries are recorded with a failed tag instead of being
silently dropped:
    {"imdbID": "tt...", "plots": null, "status": "failed", "error": "..."}
--skip-existing skips only successful records, so failed ones are retried on
the next run.

The file is rewritten atomically after every title, so an interrupted run
resumes instead of restarting.

Run with the ml venv:  ml/.venv/bin/python ml/scripts/05_fetch_imdb_plots.py ...

Input:  --ids / --input file / --db (catalog.db titles with degenerate overview)
Output: ml/data/imdb_plots.jsonl
"""

import argparse
import asyncio
import json
import os
import re
import sqlite3
import subprocess
import sys
import time
import uuid

import websockets

DEFAULT_OUT = "ml/data/imdb_plots.jsonl"
DEFAULT_WAIT = 2.0
DEFAULT_SETTLE = 1.0
DEFAULT_CONNECT_TIMEOUT = 60.0
WS_PORT = 9009


# ---------------------------------------------------------------------------
# Port takeover (same as the stock MCP server does on startup)
# ---------------------------------------------------------------------------

def kill_port(port):
    """Kill whatever listens on the port so we can bind it."""
    try:
        out = subprocess.run(
            ["lsof", f"-ti:{port}"], capture_output=True, text=True, check=False
        ).stdout
        pids = [p for p in out.split() if p]
        if pids:
            subprocess.run(["kill", "-9", *pids], check=False)
            time.sleep(0.5)
    except Exception:
        pass


# ---------------------------------------------------------------------------
# WebSocket protocol (the extension's plain JSON protocol)
# ---------------------------------------------------------------------------

async def send_and_wait(ws, type_, payload, timeout=30.0):
    """Send a request and wait for the matching messageResponse."""
    msg_id = str(uuid.uuid4())
    await ws.send(json.dumps({"id": msg_id, "type": type_, "payload": payload}))
    while True:
        raw = await asyncio.wait_for(ws.recv(), timeout=timeout)
        msg = json.loads(raw)
        if msg.get("type") != "messageResponse":
            continue
        p = msg.get("payload", {})
        if p.get("requestId") != msg_id:
            continue
        if p.get("error"):
            raise RuntimeError(p["error"])
        return p.get("result")


# ---------------------------------------------------------------------------
# Snapshot parsing
# ---------------------------------------------------------------------------

def extract_snapshot_yaml(text):
    """Pull the yaml accessibility snapshot out of a response.

    The stock MCP server wraps it in ```yaml fences; the extension's raw
    browser_snapshot returns it bare. Handle both.
    """
    if not text:
        return None
    m = re.search(r"```yaml\n(.*?)\n```", text, re.DOTALL)
    return m.group(1) if m else text


def _unquote(value):
    """Unquote a YAML double-quoted scalar from the snapshot."""
    if len(value) >= 2 and value.startswith('"') and value.endswith('"'):
        value = value[1:-1]
        value = value.replace('\\"', '"').replace("\\\\", "\\")
    return value


def parse_plot_snapshot(yaml_text):
    """Extract (plots, synopsis) from an IMDb plotsummary accessibility snapshot.

    Structure observed on the live page:
        link "Summaries" -> heading "Summaries"
        - text: <summary 1> —        (an author link follows each summary)
        - text: <summary 2> —
        link "Synopsis" -> heading "Synopsis"
        - text: <long synopsis>
        link "Contribute to this page" -> heading "Contribute to this page"
    """
    plots = []
    synopsis = None
    in_summaries = False
    in_synopsis = False
    for line in yaml_text.splitlines():
        if 'heading "Summaries"' in line:
            in_summaries, in_synopsis = True, False
            continue
        if 'heading "Synopsis"' in line:
            in_summaries, in_synopsis = False, True
            continue
        if 'heading "Contribute to this page"' in line:
            in_synopsis = False
            continue
        if not line.lstrip().startswith("- text:"):
            continue
        value = _unquote(line.lstrip()[len("- text:"):].strip())
        if value.endswith(" \u2014"):
            value = value[:-2].rstrip()
        if in_summaries:
            if value != "Add a Plot":
                plots.append(value)
        elif in_synopsis:
            if value != "It looks like we don't have any synopsis for this title yet.":
                synopsis = value
            in_synopsis = False  # only the first text after the Synopsis heading
    return plots, synopsis


# ---------------------------------------------------------------------------
# JSONL store
# ---------------------------------------------------------------------------

def load_records(path):
    records = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    rec = json.loads(line)
                    records[rec["imdbID"]] = rec
    return records


def save_records(path, records):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        for imdb_id in sorted(records):
            f.write(json.dumps(records[imdb_id], ensure_ascii=False) + "\n")
    os.replace(tmp, path)


# ---------------------------------------------------------------------------
# ID sources
# ---------------------------------------------------------------------------

def ids_from_db(db_path):
    """catalog_items whose overview is missing or degenerate - mirrors the
    polars logic in 03_enrich_tmdb_gaps.py (empty, title copied verbatim,
    "No overview found." sentinel, or under 20 words)."""
    con = sqlite3.connect(db_path)
    try:
        rows = con.execute(
            """
            SELECT tconst FROM catalog_items
            WHERE TRIM(overview) = ''
               OR TRIM(overview) = TRIM(title)
               OR TRIM(overview) = 'No overview found.'
               OR (LENGTH(overview) - LENGTH(REPLACE(overview, ' ', '')) + 1) < 20
            ORDER BY tconst
            """
        ).fetchall()
    finally:
        con.close()
    return [r[0] for r in rows]


def collect_ids(args):
    ids = []
    if args.ids:
        ids += [i.strip() for i in args.ids.split(",") if i.strip()]
    if args.input:
        if args.input == "-":
            ids += [line.strip() for line in sys.stdin if line.strip()]
        else:
            with open(args.input, encoding="utf-8") as f:
                ids += [line.strip() for line in f if line.strip()]
    if args.db:
        ids += ids_from_db(args.db)
    seen = set()
    out = []
    for i in ids:
        if i not in seen:
            seen.add(i)
            out.append(i)
    return out


# ---------------------------------------------------------------------------
# Per-title processing
# ---------------------------------------------------------------------------

def process_title(imdb_id, text, args, records):
    """Extract plots/synopsis from a snapshot response and upsert it."""
    yaml_text = extract_snapshot_yaml(text)
    if yaml_text is None:
        print(f"[{imdb_id}]: no snapshot in response")
        return
    if args.snapshots_dir:
        os.makedirs(args.snapshots_dir, exist_ok=True)
        with open(os.path.join(args.snapshots_dir, f"{imdb_id}.yaml"), "w", encoding="utf-8") as f:
            f.write(yaml_text)
    plots, synopsis = parse_plot_snapshot(yaml_text)
    records[imdb_id] = {"imdbID": imdb_id, "plots": plots, "synopsis": synopsis}
    save_records(args.out, records)
    status = f"{len(plots)} summaries, synopsis={'yes' if synopsis else 'no'}"
    if not plots and not synopsis:
        status += " (WARNING: nothing extracted - check snapshot)"
    print(f"[{imdb_id}]: {status}")


def process_evaluate(imdb_id, result, args, records):
    """Store the raw result of a --evaluate JS expression."""
    records[imdb_id] = {"imdbID": imdb_id, "plots": result}
    save_records(args.out, records)
    preview = str(result)[:80].replace("\n", " ")
    print(f"[{imdb_id}]: {preview}")


def process_failure(imdb_id, error, args, records):
    """Record a failed fetch with a failed tag so it is retried on a later run."""
    records[imdb_id] = {"imdbID": imdb_id, "plots": None, "status": "failed", "error": error}
    save_records(args.out, records)


async def drive(ws, conn_q, state):
    """Drive the extension through all titles, surviving disconnects.

    Chrome terminates the MV3 service worker when idle, which kills the
    WebSocket. On a dropped connection we wait for the extension to reconnect
    (its loop reconnects within ~1s if the worker is alive; the alarm wake
    revives a dead worker within ~30s) and retry the current title.
    """
    ids, args, records = state["ids"], state["args"], state["records"]
    failed = 0
    for i, imdb_id in enumerate(ids, 1):
        ok, ws, err = await process_one(ws, conn_q, imdb_id, args, records)
        if not ok:
            failed += 1
            process_failure(imdb_id, err, args, records)
            print(f"[{i}/{len(ids)}] {imdb_id}: FAILED - recorded (retried on next run): {err}")
        if i < len(ids):
            await asyncio.sleep(args.wait)
    done_count = len(records) - sum(1 for r in records.values() if r.get("status") == "failed")
    print(f"run finished: {len(ids)} attempted, {len(ids) - failed} succeeded, {failed} failed "
          f"(total {done_count} good records, {sum(1 for r in records.values() if r.get('status') == 'failed')} failed in {args.out})")


async def process_one(ws, conn_q, imdb_id, args, records):
    """Fetch one title, retrying across reconnects. Returns (ok, ws, error)."""
    url = f"https://www.imdb.com/title/{imdb_id}/plotsummary/"
    last_error = None
    for attempt in range(3):
        try:
            await send_and_wait(ws, "browser_navigate", {"url": url})
            if args.settle > 0:
                await asyncio.sleep(args.settle)
            if args.evaluate:
                result = await send_and_wait(ws, "browser_evaluate", {"expression": args.evaluate})
                process_evaluate(imdb_id, result, args, records)
            else:
                text = await send_and_wait(ws, "browser_snapshot", {})
                process_title(imdb_id, text, args, records)
            return True, ws, None
        except websockets.exceptions.ConnectionClosed:
            last_error = "connection dropped"
            print(f"  connection dropped - waiting for extension to reconnect...")
            try:
                ws = await asyncio.wait_for(conn_q.get(), timeout=args.connect_timeout)
            except asyncio.TimeoutError:
                raise ExtensionGone("extension did not reconnect within timeout")
            print(f"  reconnected")
        except asyncio.TimeoutError:
            last_error = "no response within 30s"
            print(f"  no response within 30s (attempt {attempt + 1}/3)")
        except Exception as e:
            print(f"  ERROR {e}")
            return False, ws, str(e)
    return False, ws, last_error or "unknown error"


class ExtensionGone(Exception):
    pass


async def run(ids, args, records):
    kill_port(WS_PORT)
    conn_q = asyncio.Queue()
    done = asyncio.Event()  # keeps handler coroutines alive so the server doesn't close their connections
    state = {"ids": ids, "args": args, "records": records, "error": None}

    async def handler(ws):
        print("extension connected")
        await conn_q.put(ws)
        # websockets closes the connection when the handler returns, so stay
        # alive until the whole run finishes (otherwise every connection is
        # closed by the server as soon as it is queued).
        await done.wait()

    async with websockets.serve(handler, "localhost", WS_PORT):
        try:
            ws = await asyncio.wait_for(conn_q.get(), timeout=args.connect_timeout)
        except asyncio.TimeoutError:
            print(
                "ERROR: browser extension never connected. It auto-reconnects "
                "within ~30s (alarm wake); if not, click the Browser MCP "
                "extension icon in the toolbar, then 'Connect'."
            )
            return
        try:
            await drive(ws, conn_q, state)
        except ExtensionGone as e:
            print(f"ERROR: {e} - keep the connected tab open and re-run with --skip-existing to resume")
        except Exception as e:
            state["error"] = e
            print(f"ERROR: {e}")
        finally:
            done.set()


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Fetch IMDb plot summaries by talking to the Browser MCP extension directly over WebSocket (no MCP server)."
    )
    parser.add_argument("--ids", help="Comma-separated IMDb tt IDs to fetch")
    parser.add_argument("--input", help="File with one tt ID per line, or '-' for stdin")
    parser.add_argument("--db", help="Read IDs from a catalog.db (titles with missing/degenerate overview)")
    parser.add_argument("--limit", type=int, default=None, help="Only fetch this many titles this run")
    parser.add_argument("--out", default=DEFAULT_OUT, help=f"Output JSONL path (default: {DEFAULT_OUT})")
    parser.add_argument("--wait", type=float, default=DEFAULT_WAIT, help=f"Seconds between titles (default: {DEFAULT_WAIT})")
    parser.add_argument("--settle", type=float, default=DEFAULT_SETTLE, help=f"Seconds to wait after navigate before snapshotting (default: {DEFAULT_SETTLE})")
    parser.add_argument("--skip-existing", action="store_true", help="Skip titles already in the output file")
    parser.add_argument("--snapshots-dir", default=None, help="Dump raw snapshots here for debugging")
    parser.add_argument("--connect-timeout", type=float, default=DEFAULT_CONNECT_TIMEOUT, help=f"Seconds to wait for the extension to connect (default: {DEFAULT_CONNECT_TIMEOUT})")
    parser.add_argument(
        "--evaluate",
        default=None,
        help="Instead of snapshot parsing, run this JS expression in each page and store the result verbatim (requires the patched extension with browser_evaluate).",
    )
    args = parser.parse_args()

    ids = collect_ids(args)
    if not ids:
        parser.error("no IDs to fetch - give --ids, --input, or --db")
    if args.limit is not None:
        ids = ids[: args.limit]

    records = load_records(args.out)
    if args.skip_existing:
        before = len(ids)
        ids = [i for i in ids if i not in records or records[i].get("status") == "failed"]
        print(f"{before} requested, {len(ids)} to fetch (successful ones skipped, failed-tagged ones retried)")

    if not ids:
        print("nothing to fetch")
        return

    print(f"fetching {len(ids)} titles -> {args.out}")
    print(
        f"NOTE: this takes over port {WS_PORT}, killing any running Browser MCP "
        "server (e.g. opencode's). The Chrome extension reconnects; opencode's "
        "browser tools may need an opencode restart afterwards."
    )

    asyncio.run(run(ids, args, records))


if __name__ == "__main__":
    main()