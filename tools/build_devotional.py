"""Builds the app's Aarti and Bhagavad Gita data from the MIT-licensed
`hindu-devotional-texts` npm package (https://www.npmjs.com/package/hindu-devotional-texts).

    python tools/build_devotional.py <path to the unpacked package folder>

Writes android-app/app/src/main/assets/aartis.json and gita.json. The
package's data files are plain JavaScript object literals; this script turns
them into JSON (no Node.js needed).

Sources (from the package's SOURCES.md): Gita Devanagari from the public-domain
gita/gita dataset (repaired), English by Shri Purohit Swami (1935, public
domain), Hindi meanings written for the package. Aartis: traditional texts.
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "android-app", "app", "src", "main", "assets")


def js_literal_to_python(src: str):
    """Parses one JS object/array literal (keys may be unquoted; strings in
    '...', "..." or `...` without ${}; trailing commas allowed)."""
    i = 0
    n = len(src)

    def ws():
        nonlocal i
        while i < n:
            if src[i] in " \t\r\n":
                i += 1
            elif src.startswith("//", i):
                i = src.index("\n", i) if "\n" in src[i:] else n
            elif src.startswith("/*", i):
                i = src.index("*/", i) + 2
            else:
                break

    def string():
        nonlocal i
        q = src[i]
        i += 1
        out = []
        while i < n:
            c = src[i]
            if c == "\\":
                e = src[i + 1]
                i += 2
                if e == "n":
                    out.append("\n")
                elif e == "t":
                    out.append("\t")
                elif e == "u":
                    out.append(chr(int(src[i:i + 4], 16)))
                    i += 4
                elif e == "\n":
                    pass
                else:
                    out.append(e)
            elif c == q:
                i += 1
                return "".join(out)
            else:
                if q == "`" and src.startswith("${", i):
                    raise ValueError("template expression in data at %d" % i)
                out.append(c)
                i += 1
        raise ValueError("unterminated string")

    def value():
        nonlocal i
        ws()
        c = src[i]
        if c == "{":
            i += 1
            obj = {}
            while True:
                ws()
                if src[i] == "}":
                    i += 1
                    return obj
                if src[i] in "'\"`":
                    key = string()
                else:
                    m = re.compile(r"[A-Za-z_$][\w$]*").match(src, i)
                    key = m.group(0)
                    i = m.end()
                ws()
                assert src[i] == ":", "expected : at %d" % i
                i += 1
                obj[key] = value()
                ws()
                if src[i] == ",":
                    i += 1
        if c == "[":
            i += 1
            arr = []
            while True:
                ws()
                if src[i] == "]":
                    i += 1
                    return arr
                arr.append(value())
                ws()
                if src[i] == ",":
                    i += 1
        if c in "'\"`":
            return string()
        m = re.compile(r"-?\d+(\.\d+)?|true|false|null|undefined").match(src, i)
        if not m:
            raise ValueError("unexpected %r at %d" % (src[i:i + 30], i))
        i = m.end()
        t = m.group(0)
        return {"true": True, "false": False, "null": None, "undefined": None}.get(t, float(t) if "." in t else int(t) if t[0] in "-0123456789" else t)

    return value()


def load_default(path):
    src = open(path, encoding="utf-8").read()
    m = re.search(r"const \w+ = ", src)
    return js_literal_to_python(src[m.end():])


def main():
    pkg = sys.argv[1]
    dist = os.path.join(pkg, "dist")
    lic = open(os.path.join(pkg, "LICENSE"), encoding="utf-8").read().splitlines()[2]

    aartis = []
    adir = os.path.join(dist, "aartis")
    for f in sorted(os.listdir(adir)):
        if not f.endswith(".js") or f == "index.js":
            continue
        a = load_default(os.path.join(adir, f))
        v = a.get("verses", {})
        aartis.append({
            "id": a["slug"],
            "deity": a.get("deity", ""),
            "titleHi": a.get("titleHindi", ""),
            "titleEn": a.get("titleEnglish", ""),
            "hi": [x["text"] for x in v.get("hindi", [])],
            "en": [x["text"] for x in v.get("english", [])],
        })
    # Krishna first: the counter is for "Radha" naam jap.
    aartis.sort(key=lambda a: (a["deity"] != "krishna", a["deity"] != "vishnu", a["titleEn"]))

    g = load_default(os.path.join(dist, "scriptures", "bhagavad-geeta.js"))
    chapters = []
    total = 0
    for ch in g["chapters"]:
        verses = []
        for v in ch["verses"]:
            sa = v["devanagari"]
            m = re.search(r"[।|]{2}\s*(\d+)\.(\d+)\s*[।|]{2}\s*$", sa)
            verses.append({
                "n": "%s" % (m.group(2) if m else len(verses) + 1),
                "sa": re.sub(r"\s*[।|]{2}\s*\d+\.\d+\s*[।|]{2}\s*$", "॥", sa).strip(),
                "iast": re.sub(r"\|\|\s*\d+\.\d+\s*\|\|\s*$", "||", v.get("iast", "")).strip(),
                "hi": v.get("hindi", ""),
                "en": v.get("english", ""),
            })
        total += len(verses)
        chapters.append({
            "n": ch["number"],
            "titleSa": ch.get("titleSanskrit", ""),
            "titleEn": ch.get("titleEnglish", ""),
            "verses": verses,
        })
    gita = {
        "source": "hindu-devotional-texts (MIT, %s). Sanskrit: gita/gita (public domain). English: Shri Purohit Swami (1935, public domain)." % lic,
        "chapters": chapters,
    }
    assert total == 701, total
    assert len(aartis) >= 19, len(aartis)
    os.makedirs(OUT, exist_ok=True)
    json.dump({"source": "hindu-devotional-texts (MIT, %s)" % lic, "aartis": aartis},
              open(os.path.join(OUT, "aartis.json"), "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    json.dump(gita, open(os.path.join(OUT, "gita.json"), "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    print("aartis: %d, gita: %d chapters, %d verses" % (len(aartis), len(chapters), total))


if __name__ == "__main__":
    main()
