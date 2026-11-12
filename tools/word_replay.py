"""Replays a recorded feature stream through the firmware's word spotter.

Mirrors src/word_spotter.cpp (SPRING subsequence DTW, speech gating,
silence reset, hit merging, duplicate guard) so rule changes can be tested on
real recordings before uploading.

    python tools/word_replay.py templates.txt rec1.txt [rec2.txt ...]

templates.txt: output of the serial 'T' command. rec*.txt: 'D' dumps.
"""
import itertools
import math
import sys

import numpy as np

DIM, QUANT, FAST = 12, 4.0, 0x10


def load_templates(path):
    out = []
    for line in open(path, encoding="ascii", errors="replace"):
        if line.startswith("T ") and not line.startswith("TH"):
            _, p, n, hexs = line.strip().split(" ", 3)
            n, p = int(n), int(p)
            a = np.frombuffer(bytes.fromhex(hexs[: n * DIM * 2]), dtype=np.int8).astype(np.float32).reshape(n, DIM) / QUANT
            out.append((p & 0x0F, bool(p & FAST), a))
    return out


def load_stream(path):
    frames, db = [], []
    for line in open(path, encoding="ascii", errors="replace"):
        line = line.strip()
        if line.startswith("F") and " " in line:
            head, hexs = line[1:].split(" ", 1)
            if len(hexs) != DIM * 4:
                continue
            try:
                vals = [int(hexs[i:i + 4], 16) for i in range(0, DIM * 4, 4)]
                db.append(int(head))
            except ValueError:
                continue
            frames.append([(v - 65536 if v >= 32768 else v) / 16.0 for v in vals])
    return np.array(frames, np.float32), np.array(db, float)


def dtw(a, b):
    n, m = len(a), len(b)
    prev = np.full(m + 1, np.inf)
    prev[0] = 0
    for i in range(n):
        cur = np.full(m + 1, np.inf)
        c = np.sqrt(((b - a[i]) ** 2).sum(1))
        for j in range(1, m + 1):
            cur[j] = c[j - 1] + min(prev[j - 1], prev[j], cur[j - 1])
        prev = cur
    return prev[m] / (n + m)


def threshold(tpls, same_kind, strict=5):
    intra = [dtw(a[2], b[2]) for a, b in itertools.combinations(tpls, 2)
             if a[0] == b[0] and (not same_kind or a[1] == b[1])]
    base = float(np.median(intra)) if intra else 2.0
    return base * (1.9 - 0.08 * strict)


def overlaps(a, b):
    ov = min(a[1], b[1]) - max(a[0], b[0])
    return ov * 2 > min(a[1] - a[0], b[1] - b[0]) + 1


def replay(frames, db, tpls, thr, min_stretch=0.5, spacing=0, finalize=8, silence_reset=8, gate_db=8.0):
    floor = np.percentile(db, 20)
    inf = math.inf
    tracks = [{"d": [inf] * (len(t[2]) + 1), "s": [-1] * (len(t[2]) + 1), "best": inf, "bn": inf, "ts": 0, "te": 0}
              for t in tpls]
    mean, ready, silence = None, False, silence_reset
    pending, last, counted = None, None, []

    def finish(h):
        nonlocal last
        if last and overlaps(h, last):
            return
        if spacing and last and (h[0] + h[1]) / 2 - (last[0] + last[1]) / 2 < spacing:
            return
        last = h
        counted.append(h)

    def merge(h):
        nonlocal pending
        if last and overlaps(h, last):
            return
        if pending and overlaps(pending, h):
            pending = (min(pending[0], h[0]), max(pending[1], h[1]), min(pending[2], h[2]))
            return
        if pending:
            finish(pending)
        pending = h

    for t in range(len(frames)):
        speech = db[t] >= floor + gate_db
        if speech:
            mean = frames[t].copy() if not ready else mean + 0.02 * (frames[t] - mean)
            ready = True
        if not ready:
            continue
        x = frames[t] - mean
        silence = 0 if speech else silence + 1
        if silence < silence_reset:
            for tr, (_, _, tp) in zip(tracks, tpls):
                m = len(tp)
                cost = np.sqrt(((tp - x) ** 2).sum(1)).tolist()
                d, s = tr["d"], tr["s"]
                nd, ns = [0.0 if speech else inf] + [0.0] * m, [t] * (m + 1)
                diag, diags = nd[0], t
                for i in range(1, m + 1):
                    b, bs = diag, diags
                    if nd[i - 1] < b:
                        b, bs = nd[i - 1], ns[i - 1]
                    if d[i] < b:
                        b, bs = d[i], s[i]
                    diag, diags = d[i], s[i]
                    nd[i], ns[i] = cost[i - 1] + b, bs
                if tr["best"] < inf and not any(nd[i] < tr["best"] and ns[i] <= tr["te"] for i in range(1, m + 1)):
                    h = (tr["ts"], tr["te"], tr["bn"])
                    tr["best"] = tr["bn"] = inf
                    for i in range(1, m + 1):
                        if ns[i] <= h[1]:
                            nd[i] = inf
                    merge(h)
                n = t - ns[m] + 1
                if ns[m] >= 0 and m * min_stretch <= n <= m * 2:
                    norm = nd[m] / (m + n)
                    if norm <= thr and nd[m] < tr["best"]:
                        tr["best"], tr["bn"], tr["ts"], tr["te"] = nd[m], norm, ns[m], t
                tr["d"], tr["s"] = nd, ns
        elif silence == silence_reset:
            for tr, tp in zip(tracks, tpls):
                if tr["best"] < inf:
                    merge((tr["ts"], tr["te"], tr["bn"]))
                m = len(tp[2])
                tr["d"], tr["s"], tr["best"], tr["bn"] = [inf] * (m + 1), [-1] * (m + 1), inf, inf
        if pending and t > pending[1] + finalize:
            finish(pending)
            pending = None
    if pending:
        finish(pending)
    return counted


def main():
    tpls = load_templates(sys.argv[1])
    recs = [(p, *load_stream(p)) for p in sys.argv[2:]]
    slow = [t for t in tpls if not t[1]]
    print(f"{len(tpls)} templates ({len(slow)} slow, {len(tpls) - len(slow)} fast)")
    for sets_name, ts in (("slow only", slow), ("slow+fast", tpls)):
        for same_kind in (False, True):
            thr = threshold(ts, same_kind)
            for ms, sp in itertools.product((0.5, 0.7, 0.8), (0, 12)):
                counts = []
                for path, fr, db in recs:
                    voiced = int((db > np.percentile(db, 20) + 15).sum() / 50)
                    c = replay(fr, db, ts, thr, min_stretch=ms, spacing=sp)
                    counts.append(f"{len(c):4d} ({len(c) / max(voiced, 1):.2f}/s over {voiced}s)")
                print(f"{sets_name:9s} thr-from-{'same-kind' if same_kind else 'all-pairs':9s} thr={thr:5.2f} "
                      f"min_stretch={ms} spacing={sp:2d}: " + " | ".join(counts), flush=True)


if __name__ == "__main__":
    main()
