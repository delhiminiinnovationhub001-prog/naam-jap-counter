"""Offline replay of the ESP32 word spotter, for tuning.

Capture on the device (serial 'T' then 'D'), save the text, then:
    python tools/word_tune.py capture.txt [--expect 20,20,0]

It finds the chanting takes (long voiced stretches) and the talking stretch,
replays SPRING subsequence DTW exactly like src/word_spotter.cpp, and sweeps
the matcher options to show which settings count closest to the truth.
"""
import argparse
import itertools
import math
import sys

import numpy as np

DIM = 12
QUANT = 4.0
FINALIZE_DELAY = 25


def load(path):
    frames, db, templates, persons = [], [], [], []
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
        elif line.startswith("T ") and not line.startswith("TH"):
            _, p, n, hexs = line.split(" ", 3)
            n = int(n)
            raw = bytes.fromhex(hexs[: n * DIM * 2])
            a = np.frombuffer(raw, dtype=np.int8).astype(np.float32).reshape(n, DIM) / QUANT
            templates.append(a)
            persons.append(int(p))
    return np.array(frames, np.float32), np.array(db), templates, persons


def segments(db, gap=15, min_len=40):
    """Voiced stretches (frames above the noise floor + 10 dB), merged across short gaps."""
    floor = np.percentile(db, 20)
    on = db > floor + 10
    segs, start, last = [], None, -999
    for i, v in enumerate(on):
        if v:
            if start is None or i - last > gap:
                if start is not None:
                    segs.append((start, last))
                start = i
            last = i
    if start is not None:
        segs.append((start, last))
    return [s for s in segs if s[1] - s[0] >= min_len], floor


def spring_all(x, templates, thr, max_stretch=2.0, min_stretch=0.5):
    """Same algorithm as word_spotter.cpp. Returns list of (t_end, score, ts, te)."""
    hits_out, pending = [], None
    tracks = []
    for tp in templates:
        m = len(tp)
        tracks.append({"d": np.full(m + 1, np.inf), "s": np.full(m + 1, -1, np.int64),
                       "best": np.inf, "bestn": np.inf, "ts": 0, "te": 0})
    for tr in tracks:
        tr["d"] = [math.inf] * len(tr["d"])
        tr["s"] = [-1] * len(tr["s"])
    inf = math.inf
    for t in range(len(x)):
        for tr, tp in zip(tracks, templates):
            m = len(tp)
            cost = np.sqrt(((tp - x[t]) ** 2).sum(1)).tolist()
            d, s = tr["d"], tr["s"]
            nd = [0.0] * (m + 1)
            ns = [t] * (m + 1)
            diag, diags = 0.0, t
            for i in range(1, m + 1):
                b, bs = diag, diags
                if nd[i - 1] < b:
                    b, bs = nd[i - 1], ns[i - 1]
                if d[i] < b:
                    b, bs = d[i], s[i]
                diag, diags = d[i], s[i]
                nd[i] = cost[i - 1] + b
                ns[i] = bs
            hit = None
            if tr["best"] < inf:
                best, te = tr["best"], tr["te"]
                if not any(nd[i] < best and ns[i] <= te for i in range(1, m + 1)):
                    hit = (tr["bestn"], tr["ts"], tr["te"])
                    tr["best"] = tr["bestn"] = inf
                    for i in range(1, m + 1):
                        if ns[i] <= hit[2]:
                            nd[i] = inf
            n = t - ns[m] + 1
            if ns[m] >= 0 and m * min_stretch <= n <= m * max_stretch:
                norm = nd[m] / (m + n)
                if norm <= thr and nd[m] < tr["best"]:
                    tr["best"], tr["bestn"], tr["ts"], tr["te"] = nd[m], norm, ns[m], t
            tr["d"], tr["s"] = nd, ns
            if hit:
                if pending and overlaps(pending, hit):
                    pending = (min(pending[0], hit[0]), min(pending[1], hit[1]), max(pending[2], hit[2]))
                else:
                    if pending:
                        hits_out.append(pending)
                    pending = hit
        if pending and t > pending[2] + FINALIZE_DELAY:
            hits_out.append(pending)
            pending = None
    if pending:
        hits_out.append(pending)
    return hits_out


def overlaps(a, b):
    ov = min(a[2], b[2]) - max(a[1], b[1])
    shorter = min(a[2] - a[1], b[2] - b[1]) + 1
    return ov * 2 > shorter


def stream_cmn(frames, db, floor, alpha):
    x = np.empty_like(frames)
    mean, ready = None, False
    for t in range(len(frames)):
        if db[t] > floor + 10:
            mean = frames[t].copy() if not ready else mean + alpha * (frames[t] - mean)
            ready = True
        x[t] = frames[t] - (mean if ready else frames[t])
    return x


def resample(tp, factor):
    """Time-scale a template (factor < 1 = shorter, for faster continuous chanting)."""
    n = max(6, int(round(len(tp) * factor)))
    idx = np.linspace(0, len(tp) - 1, n)
    lo = np.floor(idx).astype(int)
    hi = np.minimum(lo + 1, len(tp) - 1)
    w = (idx - lo)[:, None]
    return tp[lo] * (1 - w) + tp[hi] * w


def count_in(hits, seg):
    return sum(1 for h in hits if seg[0] - 5 <= (h[1] + h[2]) / 2 <= seg[1] + 5)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("capture")
    ap.add_argument("--expect", default="20,20,0", help="true counts per voiced stretch, in order")
    ap.add_argument("--quick", action="store_true")
    a = ap.parse_args()
    frames, db, templates, persons = load(a.capture)
    expect = [int(v) for v in a.expect.split(",")]
    segs, floor = segments(db)
    print(f"{len(frames)} frames ({len(frames) / 50:.1f} s), {len(templates)} templates "
          f"(lengths {[len(t) for t in templates]}), noise floor {floor:.0f} dB")
    print("voiced stretches:", [(round(s / 50, 1), round(e / 50, 1)) for s, e in segs])
    if len(segs) != len(expect):
        print(f"!! found {len(segs)} stretches, expected {len(expect)}; matching the longest ones in order")
        segs = sorted(sorted(segs, key=lambda s: s[1] - s[0], reverse=True)[: len(expect)])

    # Per-dimension weights from the templates' spread (1 = plain Euclidean).
    allt = np.concatenate(templates)
    std = allt.std(0) + 1e-3

    results = []
    alphas = [0.02] if a.quick else [0.01, 0.03]
    weights = ["none", "std"]
    scales = [1.0] if a.quick else [1.0, 0.8, 0.65]
    trims = [0.0] if a.quick else [0.0, 0.2, 0.35]   # fraction cut from the template's tail
    factors = np.arange(0.9, 2.21, 0.1)
    base = None
    for alpha, wname, scale, trim in itertools.product(alphas, weights, scales, trims):
        w = np.ones(DIM, np.float32) if wname == "none" else (1.0 / std).astype(np.float32) * std.mean()
        x = stream_cmn(frames, db, floor, alpha) * w
        tps = []
        for t in templates:
            keep = t[: max(8, int(round(len(t) * (1 - trim))))]
            keep = keep - keep.mean(0)  # re-centre after trimming (template CMN)
            tps.append(resample(keep, scale) * w)
        scale = (scale, trim)
        # threshold = factor x median distance between templates (like the firmware)
        intra = []
        for i, j in itertools.combinations(range(len(tps)), 2):
            intra.append(dtw(tps[i], tps[j]))
        med = float(np.median(intra)) if intra else 2.0
        if base is None:
            base = med
        # Quick screen: run once per stretch at the loosest threshold and filter hits by score.
        seg_hits = [spring_all(x[max(0, s - 25): e + 50], tps, med * factors.max()) for s, e in segs]
        for f in factors:
            thr = med * f
            counts = [sum(1 for h in hh if h[0] <= thr) for hh in seg_hits]
            err = sum(abs(c - e) for c, e in zip(counts, expect))
            results.append((err, alpha, wname, scale, round(float(f), 2), counts, round(thr, 2), x, tps))
        best = min((r for r in results if r[1:4] == (alpha, wname, scale)), key=lambda r: r[0])
        print(f"  alpha={alpha} weight={wname} scale={scale}: best err={best[0]} f={best[4]} counts={best[5]}", flush=True)
    results.sort(key=lambda r: (r[0], -r[4]))
    print("\nexact re-run of the best screened settings:")
    print("  (error, cmn_alpha, weighting, template_scale, thr_factor, counts, threshold)")
    for r in results[:8]:
        counts = [len(spring_all(r[7][max(0, s - 25): e + 50], r[8], r[6])) for s, e in segs]
        err = sum(abs(c - e) for c, e in zip(counts, expect))
        print("  ", (err,) + r[1:5] + (counts, r[6]))


def dtw(a, b):
    n, m = len(a), len(b)
    D = np.full((n + 1, m + 1), np.inf)
    D[0, 0] = 0
    for i in range(1, n + 1):
        c = np.sqrt(((b - a[i - 1]) ** 2).sum(1))
        for j in range(1, m + 1):
            D[i, j] = c[j - 1] + min(D[i - 1, j - 1], D[i - 1, j], D[i, j - 1])
    return D[n, m] / (n + m)


if __name__ == "__main__":
    sys.exit(main())
