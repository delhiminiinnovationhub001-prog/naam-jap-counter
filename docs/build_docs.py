"""Builds docs/Schematic.pdf and docs/User_Manual.pdf.

Run:  python docs/build_docs.py
Needs Microsoft Edge or Google Chrome (used headless to print HTML to PDF).
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
VERSION = "2.1"
DATE = "11-11-2026"

# --------------------------------------------------------------------------
# Schematic (SVG)
# --------------------------------------------------------------------------
C_3V3 = "#d32f2f"
C_GND = "#222222"
C_SIG = "#1565c0"
C_SPI = "#2e7d32"
C_SD = "#6a1b9a"

# Pin maps — must match include/config.h.
BOARDS = {
    "s3": {
        "title": "ESP32-S3-DevKitC-1",
        "subtitle": "N16R8 · 16 MB flash · 8 MB PSRAM",
        "env": "esp32-s3-n16r8",
        "usb": ["USB-C “UART” port", "5 V power, upload", "and serial log", "(5 V phone charger)"],
        "avoid": ["4. Do not use GPIO 26–37 (flash + octal PSRAM),", "    GPIO 19/20 (USB) or GPIO 43/44 (UART)."],
        "avoid_long": "GPIO 26–37 (used by the 16 MB flash and 8 MB octal PSRAM), GPIO 19/20 (native USB), "
                      "GPIO 43/44 (UART0 serial log), and GPIO 3, 45, 46 (boot strapping).",
        "sck": 4, "ws": 5, "sd": 6,
        "cs": 10, "rst": 8, "dc": 9, "mosi": 11, "clk": 12, "bl": 7, "sdcs": 14, "miso": 13,
    },
    "dev": {
        "title": "ESP32 DevKit V1",
        "subtitle": "ESP32-WROOM-32 · 4 MB flash",
        "env": "esp32dev",
        "usb": ["micro-USB port", "5 V power, upload", "and serial log", "(5 V phone charger)"],
        "avoid": ["4. Do not use GPIO 6–11 (flash), 34–39 (input", "    only) or GPIO 1/3 (UART)."],
        "avoid_long": "GPIO 6–11 (used by the flash chip), GPIO 34–39 (input only), GPIO 1/3 (UART0 serial log), "
                      "and GPIO 2, 12, 15 (boot strapping).",
        "sck": 26, "ws": 25, "sd": 33,
        "cs": 5, "rst": 17, "dc": 16, "mosi": 23, "clk": 18, "bl": 4, "sdcs": 13, "miso": 19,
    },
}


def pin_lists(b):
    mic = [  # (pin label, y, wire colour or None, esp label)
        ("VDD", 250, C_3V3, "3V3"),
        ("GND", 290, C_GND, "GND"),
        ("L/R", 330, None, None),
        ("SCK", 370, C_SIG, f"GPIO{b['sck']} · I2S SCK"),
        ("WS", 410, C_SIG, f"GPIO{b['ws']} · I2S WS"),
        ("SD", 450, C_SIG, f"GPIO{b['sd']} · I2S DATA"),
    ]
    tft = [
        ("CS", 200, C_SPI, f"GPIO{b['cs']} · TFT CS"),
        ("RESET", 240, C_SPI, f"GPIO{b['rst']} · TFT RST"),
        ("A0 (DC)", 280, C_SPI, f"GPIO{b['dc']} · TFT DC"),
        ("SDA", 320, C_SPI, f"GPIO{b['mosi']} · SPI MOSI"),
        ("SCK", 360, C_SPI, f"GPIO{b['clk']} · SPI SCK"),
        ("LED", 400, C_SPI, f"GPIO{b['bl']} · BACKLIGHT"),
        ("VCC", 440, C_3V3, "3V3"),
        ("GND", 480, C_GND, "GND"),
    ]
    sd = [
        ("SD_CS", 580, C_SD, f"GPIO{b['sdcs']} · SD CS"),
        ("SD_MISO", 620, C_SD, f"GPIO{b['miso']} · SPI MISO"),
        ("SD_MOSI", 660, C_SD, None),  # tapped from the SDA wire
        ("SD_SCK", 700, C_SD, None),   # tapped from the SCK wire
    ]
    return mic, tft, sd

MIC_X0, MIC_X1 = 70, 250
ESP_X0, ESP_X1 = 520, 860
TFT_X0, TFT_X1 = 1120, 1330
TAP_MOSI_X, TAP_SCK_X = 1000, 960


def text(x, y, s, size=14, anchor="start", weight="normal", color="#111", family="Segoe UI, Arial, sans-serif"):
    s = s.replace("&", "&amp;").replace("<", "&lt;")
    return (f'<text x="{x}" y="{y}" font-size="{size}" text-anchor="{anchor}" font-weight="{weight}" '
            f'fill="{color}" font-family="{family}">{s}</text>')


def line(x1, y1, x2, y2, color, width=2.5):
    return f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" stroke-width="{width}" stroke-linecap="round"/>'


def dot(x, y, color):
    return f'<circle cx="{x}" cy="{y}" r="5" fill="{color}"/>'


def pin(x, y, side):
    """Small square pin marker on a block edge."""
    return f'<rect x="{x - 4}" y="{y - 4}" width="8" height="8" fill="#fff" stroke="#111" stroke-width="1.5"/>'


def block(x0, y0, x1, y1, title, subtitle="", fill="#f7f7f7"):
    out = [f'<rect x="{x0}" y="{y0}" width="{x1 - x0}" height="{y1 - y0}" rx="8" fill="{fill}" stroke="#111" stroke-width="2.5"/>']
    out.append(text((x0 + x1) / 2, y0 + 24, title, 16, "middle", "bold"))
    if subtitle:
        out.append(text((x0 + x1) / 2, y0 + 42, subtitle, 12, "middle", color="#555"))
    return "\n".join(out)


def schematic_svg(key="s3"):
    b = BOARDS[key]
    MIC, TFT, SD = pin_lists(b)
    sheet = list(BOARDS).index(key) + 1
    o = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1400 900" width="100%">',
         '<rect width="1400" height="900" fill="#fff"/>']

    # Title bar
    o.append('<rect x="20" y="20" width="1360" height="70" fill="#fff3e0" stroke="#e65100" stroke-width="2"/>')
    o.append(text(40, 52, f"Naam Jap Counter — Schematic ({b['title']})", 24, weight="bold", color="#e65100"))
    o.append(text(40, 76, f"{b['title']} {b['subtitle'].split(' · ')[0]}  ·  INMP441 I2S microphone  ·  1.8\" ST7735 TFT with micro-SD"
                          f"  ·  build target {b['env']}", 14, color="#333"))
    o.append(text(1360, 52, f"Rev {VERSION}", 16, "end", "bold"))
    o.append(text(1360, 76, f"Date {DATE}  ·  Sheet {sheet} of {len(BOARDS)}", 13, "end", color="#333"))

    # ---- INMP441
    o.append(block(MIC_X0, 190, MIC_X1, 490, "INMP441", "I2S MEMS microphone"))
    for name, y, color, _ in MIC:
        o.append(pin(MIC_X1, y, "r"))
        o.append(text(MIC_X1 - 12, y + 5, name, 14, "end", "bold"))
    # L/R tied to GND
    o.append(line(MIC_X1, 330, 300, 330, C_GND))
    o.append(line(300, 330, 300, 290, C_GND))
    o.append(dot(300, 290, C_GND))
    o.append(text(310, 345, "L/R → GND (left channel)", 12, color="#555"))

    # ---- ESP32 board
    o.append(block(ESP_X0, 140, ESP_X1, 760, b["title"], b["subtitle"], "#eef4ff"))
    for name, y, color, esp in MIC:
        if not color:
            continue
        o.append(line(MIC_X1, y, ESP_X0, y, color))
        o.append(pin(ESP_X0, y, "l"))
        o.append(text(ESP_X0 + 12, y + 5, esp, 13))
    for name, y, color, esp in TFT + SD:
        if esp is None:
            continue
        o.append(pin(ESP_X1, y, "r"))
        o.append(text(ESP_X1 - 12, y + 5, esp, 13, "end"))
        o.append(line(ESP_X1, y, TFT_X0, y, color))

    # Shared SPI taps to the SD slot
    o.append(line(TAP_MOSI_X, 320, TAP_MOSI_X, 660, C_SD))
    o.append(line(TAP_MOSI_X, 660, TFT_X0, 660, C_SD))
    o.append(dot(TAP_MOSI_X, 320, C_SPI))
    o.append(line(TAP_SCK_X, 360, TAP_SCK_X, 700, C_SD))
    o.append(line(TAP_SCK_X, 700, TFT_X0, 700, C_SD))
    o.append(dot(TAP_SCK_X, 360, C_SPI))
    o.append(text(TAP_MOSI_X + 8, 652, f"MOSI (GPIO{b['mosi']})", 11, color=C_SD))
    o.append(text(TAP_SCK_X - 8, 692, f"SCK (GPIO{b['clk']})", 11, "end", color=C_SD))

    # BOOT button and USB inside the DevKit
    bx, by, bw = 535, 520, 175
    o.append(f'<rect x="{bx}" y="{by}" width="{bw}" height="110" rx="6" fill="#fff" stroke="#888" stroke-dasharray="5 4"/>')
    o.append(text(bx + 10, by + 20, "BOOT button (GPIO0)", 13, weight="bold"))
    o.append(text(bx + 10, by + 36, "on the board", 11, color="#666"))
    o.append(line(bx + 20, by + 62, bx + 50, by + 62, C_GND, 2))
    o.append(line(bx + 50, by + 62, bx + 82, by + 48, C_GND, 2))
    o.append(line(bx + 86, by + 62, bx + 116, by + 62, C_GND, 2))
    o.append(dot(bx + 50, by + 62, C_GND))
    o.append(dot(bx + 86, by + 62, C_GND))
    o.append(text(bx + 10, by + 84, "Short press: pause", 12, color="#444"))
    o.append(text(bx + 10, by + 100, "Hold 5 s: WiFi reset", 12, color="#444"))
    o.append(f'<rect x="{bx}" y="{by + 125}" width="{bw}" height="90" rx="6" fill="#fff" stroke="#888" stroke-dasharray="5 4"/>')
    o.append(text(bx + 10, by + 145, b["usb"][0], 13, weight="bold"))
    for i, s in enumerate(b["usb"][1:]):
        o.append(text(bx + 10, by + 165 + i * 18, s, 12, color="#444"))

    # ---- TFT and SD slot
    o.append(block(TFT_X0, 130, TFT_X1, 505, "1.8\" TFT ST7735", "128 × 160 SPI display"))
    for name, y, _, _ in TFT:
        o.append(pin(TFT_X0, y, "l"))
        o.append(text(TFT_X0 + 12, y + 5, name, 14, weight="bold"))
    o.append(block(TFT_X0, 518, TFT_X1, 730, "micro-SD slot", "same module · optional"))
    for name, y, _, _ in SD:
        o.append(pin(TFT_X0, y, "l"))
        o.append(text(TFT_X0 + 12, y + 5, name, 14, weight="bold"))

    # ---- Notes
    nx, ny = 60, 540
    o.append(f'<rect x="{nx}" y="{ny}" width="420" height="300" rx="6" fill="#fafafa" stroke="#bbb"/>')
    o.append(text(nx + 14, ny + 26, "Notes", 16, weight="bold"))
    notes = [
        "1. All modules run from the DevKit 3V3 pin.",
        "2. INMP441 L/R must go to GND (left channel).",
        f"3. TFT and SD share the SPI bus: SCK=GPIO{b['clk']},",
        f"    MOSI=GPIO{b['mosi']}, MISO=GPIO{b['miso']}; separate CS pins.",
        *b["avoid"],
        "5. Lines that cross without a dot are not joined.",
        "6. Keep mic wires short (< 15 cm).",
        "7. TFT module J1 jumper: leave as supplied when",
        "    powering VCC from 3V3.",
    ]
    for i, n in enumerate(notes):
        o.append(text(nx + 14, ny + 54 + i * 24, n, 13, color="#333"))

    # Legend
    lx, ly = 900, 790
    for i, (c, label) in enumerate([(C_3V3, "3V3"), (C_GND, "GND"), (C_SIG, "I2S audio"), (C_SPI, "SPI display"), (C_SD, "SD card")]):
        x = lx + i * 96
        o.append(line(x, ly, x + 28, ly, c, 4))
        o.append(text(x + 34, ly + 5, label, 12))
    o.append(text(1380, 860, "Pin map lives in include/config.h", 12, "end", color="#777"))

    o.append("</svg>")
    return "\n".join(o)


# --------------------------------------------------------------------------
# TFT screen mock-up (SVG) for the manual
# --------------------------------------------------------------------------
def tft_svg(kind="main"):
    s = 3  # scale
    o = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {160 * s + 24} {128 * s + 24}" width="{(160 * s + 24) * 0.75}">',
         f'<rect x="0" y="0" width="{160 * s + 24}" height="{128 * s + 24}" rx="14" fill="#333"/>',
         f'<rect x="12" y="12" width="{160 * s}" height="{128 * s}" fill="#000"/>']

    def t(x, y, txt, size, color, anchor="start"):
        o.append(f'<text x="{12 + x * s}" y="{12 + (y + 7 * size) * s}" font-size="{8 * size * s}" '
                 f'font-family="Consolas, monospace" fill="{color}" text-anchor="{anchor}">{txt}</text>')

    if kind == "main":
        o.append(f'<rect x="12" y="12" width="{160 * s}" height="{13 * s}" fill="#222"/>')
        t(2, 3, "09:45:03 PM", 1, "#fff")
        t(74, 3, "11-11-26", 1, "#fff")
        t(124, 3, "SD", 1, "#0f0")
        for i in range(3):
            h = 3 + i * 3
            o.append(f'<rect x="{12 + (144 + i * 5) * s}" y="{12 + (11 - h) * s}" width="{3 * s}" height="{h * s}" fill="#0f0"/>')
        t(80, 16, "ARJUN", 2, "#ff8000", "middle")
        t(80, 36, "1,234", 4, "#ff8000", "middle")
        o.append(f'<line x1="{12 + 8 * s}" y1="{12 + 72 * s}" x2="{12 + 152 * s}" y2="{12 + 72 * s}" stroke="#888" stroke-width="{s}"/>')
        t(4, 77, "Total 12,34,567", 1, "#fff")
        t(156, 77, "Mala 11", 1, "#fff", "end")
        t(4, 90, "All today 3,456", 1, "#888")
        t(4, 101, "All total 45,67,890", 1, "#888")
        t(156, 101, "87%", 1, "#888", "end")
        o.append(f'<rect x="{12 + 2 * s}" y="{12 + 113 * s}" width="{156 * s}" height="{12 * s}" fill="none" stroke="#888" stroke-width="{s / 2}"/>')
        o.append(f'<rect x="{12 + 3 * s}" y="{12 + 114 * s}" width="{98 * s}" height="{10 * s}" fill="#0f0"/>')
        o.append(f'<rect x="{12 + 70 * s}" y="{12 + 114 * s}" width="{s}" height="{10 * s}" fill="#f00"/>')
    else:
        o.append(f'<rect x="12" y="12" width="{160 * s}" height="{13 * s}" fill="#ff8000"/>')
        t(80, 3, "WIFI SETUP", 1, "#000", "middle")
        t(4, 18, "1. Join this WiFi:", 1, "#888")
        t(80, 29, "JapCounter-A1B2", 1, "#fff", "middle")
        t(4, 42, "Password:", 1, "#888")
        t(80, 53, "48207391", 2, "#ff0", "middle")
        t(4, 76, "2. Open in browser:", 1, "#888")
        t(80, 87, "192.168.4.1", 2, "#0ff", "middle")
        t(80, 115, "Today: 0", 1, "#ff8000", "middle")
    o.append("</svg>")
    return "\n".join(o)


# --------------------------------------------------------------------------
# HTML pages
# --------------------------------------------------------------------------
BASE_CSS = """
@page { size: A4; margin: 16mm 15mm 18mm 15mm; }
* { box-sizing: border-box; }
body { font-family: 'Segoe UI', Arial, sans-serif; color: #1d1d1d; font-size: 11pt; line-height: 1.45; margin: 0; }
h1 { color: #e65100; font-size: 22pt; margin: 0 0 6pt; }
h2 { color: #e65100; font-size: 15pt; border-bottom: 2px solid #ffcc80; padding-bottom: 3pt; margin: 18pt 0 8pt; break-after: avoid; }
h3 { font-size: 12pt; margin: 12pt 0 4pt; break-after: avoid; }
table { border-collapse: collapse; width: 100%; margin: 6pt 0 10pt; break-inside: avoid; }
th, td { border: 1px solid #ccc; padding: 4pt 6pt; text-align: left; vertical-align: top; font-size: 10pt; }
th { background: #fff3e0; }
code, .mono { font-family: Consolas, monospace; background: #f3f3f3; padding: 0 3px; border-radius: 3px; }
.note { background: #fff8e1; border-left: 4px solid #ffb300; padding: 6pt 10pt; margin: 8pt 0; break-inside: avoid; }
.warn { background: #ffebee; border-left: 4px solid #e53935; padding: 6pt 10pt; margin: 8pt 0; break-inside: avoid; }
.tip { background: #e8f5e9; border-left: 4px solid #43a047; padding: 6pt 10pt; margin: 8pt 0; break-inside: avoid; }
ol li, ul li { margin: 2pt 0; }
.fig { text-align: center; margin: 8pt 0; break-inside: avoid; }
.fig .cap { font-size: 9pt; color: #666; margin-top: 3pt; }
.page { break-before: page; }
.two { display: flex; gap: 16pt; align-items: center; }
.two > div { flex: 1; }
"""


def wiring_rows():
    """(module, pin, key or fixed value, purpose); key looks up the GPIO per board."""
    return [
        ("INMP441", "VDD", "3V3", "Power 3.3 V"),
        ("INMP441", "GND", "GND", ""),
        ("INMP441", "L/R", "GND", "Selects left channel"),
        ("INMP441", "SCK", "sck", "I2S bit clock"),
        ("INMP441", "WS", "ws", "I2S word select"),
        ("INMP441", "SD", "sd", "I2S data (mic → ESP)"),
        ("TFT", "VCC", "3V3", "Power"),
        ("TFT", "GND", "GND", ""),
        ("TFT", "CS", "cs", "Display chip select"),
        ("TFT", "RESET", "rst", "Display reset"),
        ("TFT", "A0", "dc", "Data / command"),
        ("TFT", "SDA", "mosi", "SPI MOSI (shared)"),
        ("TFT", "SCK", "clk", "SPI clock (shared)"),
        ("TFT", "LED", "bl", "Backlight, PWM brightness"),
        ("SD slot", "SD_CS", "sdcs", "SD chip select"),
        ("SD slot", "SD_MOSI", "mosi", "Same wire as TFT SDA"),
        ("SD slot", "SD_MISO", "miso", "SPI MISO"),
        ("SD slot", "SD_SCK", "clk", "Same wire as TFT SCK"),
        ("Board", "BOOT button", "0", "On board, nothing to wire"),
    ]


def board_pin(b, key):
    if key in b:
        return f"GPIO {b[key]}"
    return f"GPIO {key}" if key.isdigit() else key


def wiring_table():
    s3, dev = BOARDS["s3"], BOARDS["dev"]
    rows = "".join(
        f"<tr><td>{m}</td><td>{p}</td><td><b>{board_pin(dev, k)}</b></td><td><b>{board_pin(s3, k)}</b></td><td>{why}</td></tr>"
        for m, p, k, why in wiring_rows()
    )
    return (f"<table><tr><th>Module</th><th>Module pin</th><th>{dev['title']}</th><th>{s3['title']}</th>"
            f"<th>Purpose</th></tr>{rows}</table>")


def schematic_html():
    avoid = "".join(f"<div class=\"note\"><b>{b['title']} pins to avoid:</b> {b['avoid_long']}</div>" for b in BOARDS.values())
    sheets = "".join(f'<div class="sheet{" page" if i else ""}">{schematic_svg(k)}</div>' for i, k in enumerate(BOARDS))
    return f"""<!doctype html><html><head><meta charset="utf-8"><title>Naam Jap Counter - Schematic</title>
<style>{BASE_CSS}
@page {{ size: A4 landscape; margin: 8mm; }}
.sheet {{ width: 100%; }}
th, td {{ padding: 2pt 6pt; font-size: 9.5pt; }}
h1 {{ font-size: 18pt; }}
</style></head><body>
{sheets}
<div class="page">
<h1>Wiring table</h1>
<p>Every connection on the schematics, one wire per row. Use the column for your board, and choose the matching
build target in PlatformIO (<code>{BOARDS['dev']['env']}</code> or <code>{BOARDS['s3']['env']}</code>).
Use short jumper wires (under 15 cm), especially for the microphone.</p>
{wiring_table()}
{avoid}
</div>
</body></html>"""


def manual_html():
    return f"""<!doctype html><html><head><meta charset="utf-8"><title>Naam Jap Counter - User Manual</title>
<style>{BASE_CSS}
.cover {{ height: 255mm; display: flex; flex-direction: column; justify-content: center; text-align: center; }}
.cover h1 {{ font-size: 34pt; }}
.cover .sub {{ font-size: 14pt; color: #555; }}
.toc li {{ margin: 3pt 0; }}
</style></head><body>

<div class="cover">
  <div style="font-size:16pt;color:#e65100">|| Radhe Radhe ||</div>
  <h1>Naam Jap Counter</h1>
  <div class="sub">User Manual · Version {VERSION} · {DATE}</div>
  <div class="fig" style="margin-top:24pt">{tft_svg("main")}</div>
  <div class="sub" style="font-size:11pt">Counts only your word, tells the two chanting voices apart,<br>and keeps a separate count for each person.</div>
</div>

<div class="page">
<h2>Contents</h2>
<ol class="toc">
<li>What the device does</li><li>Parts and wiring</li><li>Installing the firmware</li><li>First-time WiFi setup</li>
<li>Reading the screen</li><li>Adding people and training voices</li><li>Daily use</li><li>The web dashboard</li>
<li>Correcting counts</li><li>Settings explained</li><li>Midnight, history and the SD card</li><li>The BOOT button</li>
<li>How well voice recognition works</li><li>Troubleshooting</li><li>Technical specifications</li>
<li>The Android app</li>
</ol>

<h2>1. What the device does</h2>
<ul>
<li><b>Counts only your word.</b> You choose the word or mantra (for example <b>Radha</b>) and teach it by saying it about 30 times. From then on <b>only that word is counted</b>, also when chanted continuously without pauses. Talking, taps, claps, the TV and room noise are ignored. Until the word is taught, nothing is counted.</li>
<li><b>Knows who is chanting.</b> Made for <b>2 people</b> (for example a couple). Each trains their voice once; every jap is counted for whoever said it, and the screen switches to that person's name automatically.</li>
<li><b>Celebrates milestones.</b> At every 100 japs of a person (100, 200 &hellip; 900) rings burst on the screen with the number; at every 1000 the screen flashes the word (<b>RADHA</b>) in colours with fireworks.</li>
<li><b>Shows everything on the screen:</b> the name, that person's count for today, their grand total, malas (108 japs), the family totals, and the date and time in IST.</li>
<li><b>Closes the day at 12 midnight IST.</b> Today's counts are added to each person's grand total, saved to history, and today starts again from 0.</li>
<li><b>Never loses counts.</b> Counts are stored in the device's flash memory and survive power cuts. A micro-SD card, if inserted, keeps a backup log.</li>
<li><b>Web dashboard.</b> From any phone or computer on the same WiFi you can see live counts, add people, train voices, correct counts, change settings, and download history.</li>
</ul>

<h2>2. Parts and wiring</h2>
<table>
<tr><th>Part</th><th>Notes</th></tr>
<tr><td><b>One</b> of: ESP32 DevKit V1 (ESP32-WROOM-32) <b>or</b> ESP32-S3-DevKitC-1 <b>N16R8</b></td><td>The brain of the device. Both run the same firmware with all features; the S3 has more memory and speed to spare.</td></tr>
<tr><td>INMP441 I2S MEMS microphone</td><td>Digital microphone; very low noise.</td></tr>
<tr><td>1.8" TFT LCD, ST7735, 128×160, SPI</td><td>The module's built-in micro-SD slot is optional.</td></tr>
<tr><td>micro-SD card (optional)</td><td>FAT32, up to 32 GB.</td></tr>
<tr><td>USB cable (micro-USB for DevKit V1, USB-C for S3) and 5 V phone charger</td><td>Power for everyday use.</td></tr>
<tr><td>Jumper wires / small enclosure</td><td>Keep microphone wires short.</td></tr>
</table>
<p>The pins depend on your board. Use the column for your board. The full schematics (one sheet per board)
are in the separate file <b>Schematic.pdf</b>.</p>
{wiring_table()}
<div class="tip"><b>Placing the microphone:</b> point the small hole on the INMP441 towards the person chanting, 20–50 cm away. Keep it away from fans and speakers.</div>
</div>
<div class="page">
<div class="fig">{schematic_svg("dev")}<div class="cap">Schematic for the ESP32 DevKit V1 (sheet 2 of Schematic.pdf)</div></div>
<div class="fig">{schematic_svg("s3")}<div class="cap">Schematic for the ESP32-S3-DevKitC-1 (sheet 1 of Schematic.pdf)</div></div>
</div>

<div class="page">
<h2>3. Installing the firmware</h2>
<ol>
<li>Install <b>Visual Studio Code</b> and its <b>PlatformIO IDE</b> extension.</li>
<li>Open the project folder (<code>radha</code>) in VS Code. PlatformIO downloads everything it needs the first time (this can take several minutes).</li>
<li>Choose your board in the PlatformIO toolbar at the bottom of VS Code (the "env" selector):
<code>env:esp32dev</code> for the ESP32 DevKit V1, or <code>env:esp32-s3-n16r8</code> for the ESP32-S3.</li>
<li>Connect the board by USB. On the ESP32-S3 use the USB-C port marked <b>UART</b> (or <b>COM</b>).</li>
<li>Click <b>✓ Build</b>, then <b>→ Upload</b>.</li>
<li>Optional: click the plug icon (Serial Monitor) to see the device's log at 115200 baud.</li>
</ol>
<div class="note">If upload fails with "No serial data received", hold the <b>BOOT</b> button, press and release <b>RST</b>, then release BOOT and upload again.</div>

<h2>4. First-time WiFi setup</h2>
<div class="two"><div>
<ol>
<li>Power the device. After the welcome screen it shows <b>WIFI SETUP</b> with a hotspot name (for example <code>JapCounter-A1B2</code>) and an <b>8-digit password</b>.</li>
<li>On your phone open WiFi settings and join that hotspot, using the password shown on the screen.</li>
<li>A setup page opens by itself. If it does not, open a browser and go to <code>192.168.4.1</code>.</li>
<li>Tap your home WiFi in the list, type its password and press <b>Save &amp; connect</b>.</li>
<li>The device restarts, joins your WiFi and shows <b>Connected</b> with its IP address. The time is set automatically within a few seconds.</li>
</ol>
</div><div class="fig">{tft_svg("setup")}<div class="cap">WiFi setup screen</div></div></div>
<div class="tip">Counting works even without WiFi. You can tap <b>Skip – use the counter without WiFi</b> on the setup page. Without internet the clock is not set, so the day will not close at midnight until WiFi is available.</div>
<p>If your WiFi is down when the device starts, it opens the setup hotspot again, keeps counting, and retries your WiFi every 30 seconds. When your WiFi returns it reconnects and the hotspot closes by itself.</p>

<h2>5. Reading the screen</h2>
<div class="two"><div class="fig">{tft_svg("main")}</div><div>
<table>
<tr><th>Area</th><th>Meaning</th></tr>
<tr><td>Top bar</td><td>Time in 12-hour IST with AM/PM, and date, <b>SD</b> when a card is in, WiFi bars (green = connected, yellow = setup hotspot, red = offline)</td></tr>
<tr><td>Name</td><td>Who chanted last. Yellow with "7/20" while training. <b>PAUSED</b> in red when paused.</td></tr>
<tr><td>Big number</td><td>That person's japs today</td></tr>
<tr><td>Total / Mala</td><td>That person's grand total, and today's malas (÷108)</td></tr>
<tr><td>All today / All total</td><td>The whole family together</td></tr>
<tr><td>87%</td><td>How sure the device was about the name</td></tr>
<tr><td>Bottom bar</td><td>Microphone level. Green while a jap is heard; red line = trigger level</td></tr>
</table></div></div>
<p>Until the word has been taught, the name line shows <b>TEACH WORD</b> in yellow and nothing is counted (see section 6).</p>
</div>

<div class="page">
<h2>6. Teaching the word and the voices</h2>
<p>Open the dashboard (<code>http://japcounter.local</code> or the IP address shown on the screen at start-up) or the Android app.
Your phone must be on the same WiFi. One training run teaches the counter both <b>the word</b> and <b>that person's voice</b>.</p>
<h3>Choose the word</h3>
<ol><li>In <b>Word counting</b> (web) or <b>Settings → Word</b> (app), type the word or mantra in English letters, for example <code>Radha</code>, and save. The name is a label for the screen; the counter learns the actual sound from the training below.</li></ol>
<h3>Add a person</h3>
<ol><li>In the <b>People</b> card type a name in English letters (up to 16 characters) and press <b>Add person</b>. Up to 2 people can be added.</li></ol>
<h3>Train their voice</h3>
<ol>
<li>Press <b>Erase voice &amp; retrain</b> under the person's name and confirm.</li>
<li>The screen shows the name in yellow with a counter such as <b>ARJUN 0/20</b>.</li>
<li><b>Only that person</b> says the word, <b>one at a time with a short pause (about half a second) after each</b>, at their usual distance and volume. The pauses let the counter cut out clean examples. Each one is also counted for them.</li>
<li>After 30 examples training stops by itself. The card shows <b>voice trained (30 chants) · word: 20 patterns</b>: the counter keeps the 20 most typical examples of the word for that person.</li>
<li>Repeat for each person. From now on the web page says <b>Counting only "Radha"</b>.</li>
</ol>
<div class="tip"><b>For the best results:</b> train in the room where the device will be used, at normal volume, and keep the room quiet while training (taps and noises are rejected, but talking during training is not). Train again if a person's voice changes, e.g. after a cold, or if their chanting is often missed.</div>
<table>
<tr><th>Button</th><th>What it does</th></tr>
<tr><td>Erase voice &amp; retrain</td><td>Erases the person's voice model and word patterns, then records new examples straight away. Counts are kept.</td></tr>
<tr><td>Add chants</td><td>Adds more chants to the existing voice (up to 40 are kept)</td></tr>
<tr><td>Teach fast chanting</td><td>After normal training: the person chants fast and continuously for about 10 seconds. The counter cuts out 20 fast words and learns them (8 fast + 12 slow patterns are kept), so fast chanting is counted correctly.</td></tr>
<tr><td>Erase voice only</td><td>Erases the voice model and word patterns without retraining; the person is not counted until trained again. Counts are kept.</td></tr>
<tr><td>Rename</td><td>Changes the name; counts and voice are kept</td></tr>
<tr><td>Remove</td><td>Deletes the person, their counts and voice</td></tr>
<tr><td>Stop training</td><td>Ends training early (at least 5 chants are needed to recognise a person)</td></tr>
</table>
<div class="note">Each person who chants should be trained. Someone who has not trained may still be counted, credited to the trained person whose voice is closest.</div>

<h2>7. Daily use</h2>
<ul>
<li>Switch the device on and chant. Every "Radha" is counted for whoever is chanting, <b>with or without pauses</b> &mdash; "radharadharadha…" works. The name and count appear on the screen.</li>
<li>Talking, coughing, tapping the device or the TV are not counted.</li>
<li>Up to about 3 words per second are counted. If you usually chant fast, use <b>Teach fast chanting</b> once after training (section 6).</li>
<li>Press the <b>BOOT</b> button once to <b>pause</b> while talking to someone; press again to resume.</li>
<li>One person at a time gives the best results. If two people chant together, the jap is counted once, for one of them.</li>
</ul>

<h2>8. The web dashboard</h2>
<table>
<tr><th>Card</th><th>Contents</th></tr>
<tr><td>Now chanting</td><td>Name, match %, their count today, family totals, live microphone level, Pause button, training progress</td></tr>
<tr><td>People</td><td>Each person's today / total / previous day, voice status and all buttons; the current chanter is highlighted</td></tr>
<tr><td>Detection settings</td><td>Sensitivity, chant length, silence gap, voice strictness, training length, smoothing, screen brightness</td></tr>
<tr><td>History</td><td>Last 60 days, one column per person, with a CSV download of the full history</td></tr>
<tr><td>Device</td><td>WiFi details, SD card status, Change / Forget WiFi, Reset all counts</td></tr>
</table>
</div>

<div class="page">
<h2>9. Correcting counts</h2>
<p>Each person (and Guest) has <b>+1</b>, <b>−1</b> and <b>Set today…</b> buttons on the dashboard. For example, if a jap was given to the wrong person, press −1 on the wrong person and +1 on the right one.</p>

<h2>10. Settings explained</h2>
<table>
<tr><th>Setting</th><th>Default</th><th>Change it when…</th></tr>
<tr><td>Sensitivity (1–10)</td><td>5</td><td>Raise if soft chanting is missed; lower if room noise is counted</td></tr>
<tr><td>Word matching strictness (1–10)</td><td>5</td><td>Raise if sounds that are not the word get counted; lower if some of your chants are missed</td></tr>
<tr><td>Minimum chant length</td><td>150 ms</td><td>Used while training: shorter sounds are not taken as examples</td></tr>
<tr><td>Silence between two japs</td><td>350 ms</td><td>Used while training: the pause that separates two examples</td></tr>
<tr><td>Strictness (1–10)</td><td>5</td><td>Raise if japs go to the wrong person (more will go to Guest); lower if too many go to Guest</td></tr>
<tr><td>Chants per training</td><td>20</td><td>More chants (30–40) give better recognition for similar voices</td></tr>
<tr><td>Smoothing</td><td>On</td><td>When unsure, the device gives the jap to whoever chanted the previous japs. Turn off if people take turns very quickly</td></tr>
<tr><td>Brightness</td><td>100%</td><td>Lower at night</td></tr>
</table>

<h2>11. Midnight, history and the SD card</h2>
<ul>
<li>At <b>12:00 midnight IST</b> each person's count for the day is added to their grand total and written to history; "Prev day" then shows that day's count.</li>
<li>If the device was off at midnight, the day is closed as soon as it gets the time again.</li>
<li>History is kept in the device (download it as CSV from the dashboard). With an SD card inserted, every day is also appended to <code>japlog.csv</code> on the card, and the names are in <code>names.txt</code>.</li>
<li><b>Reset all counts</b> erases counts and history in the device for everyone (names and voices are kept). The SD card log is not erased.</li>
</ul>

<h2>12. The BOOT button</h2>
<table>
<tr><th>Action</th><th>Result</th></tr>
<tr><td>Short press</td><td>Pause / resume counting</td></tr>
<tr><td>Hold for 5 seconds</td><td>Forget WiFi and restart in setup mode (counts are kept)</td></tr>
</table>

<h2>13. How well voice recognition works</h2>
<p>The device measures the tone and pitch pattern of each jap (its "voice fingerprint") and compares it with the trained chants of every person.</p>
<table>
<tr><th>Situation</th><th>Expected result</th></tr>
<tr><td>Clearly different voices (man / woman / child), one at a time</td><td>Good — most japs get the right name</td></tr>
<tr><td>Similar voices (e.g. two brothers)</td><td>Often mixed up; train with 30–40 chants and raise Strictness</td></tr>
<tr><td>Two or more people chanting together</td><td>Cannot be separated; counted once for one person</td></tr>
<tr><td>Whispering, a cold, very different distance</td><td>Less accurate; use Add chants to include these conditions</td></tr>
</table>
<div class="note">Recognition is a helper, not a guarantee. Check the counts now and then and correct them with the +1 / −1 buttons if needed.</div>
</div>

<div class="page">
<h2>14. Troubleshooting</h2>
<table>
<tr><th>Problem</th><th>What to do</th></tr>
<tr><td>Screen stays white or black</td><td>Check the TFT wiring (CS, A0, RESET, SDA, SCK). LED must go to the backlight pin (GPIO 4 on DevKit V1, GPIO 7 on S3). Check that the PlatformIO build target matches your board.</td></tr>
<tr><td>Wrong colours or a strip of garbage at the edge</td><td>In <code>include/config.h</code> change <code>TFT_TAB</code> to <code>INITR_GREENTAB</code> or <code>INITR_REDTAB</code> and upload again.</td></tr>
<tr><td>Screen shows <b>NO MIC SIGNAL</b> (red bar), or the dashboard says "Microphone not working"</td><td>The counter already tried both mic channels at start-up and got nothing, so it is a wiring fault. Check SD, SCK and WS against the wiring table, VDD on <b>3V3</b> (not 5V), GND, and L/R on GND, then press EN. The serial log line starting with <code>[mic]</code> says exactly what was found.</td></tr>
<tr><td>Nothing is counted, screen says TEACH WORD</td><td>The word has not been taught yet. Train at least one person (section 6).</td></tr>
<tr><td>Nothing is counted (word taught)</td><td>Is it paused? Chant a little louder or closer to the microphone; lower Word matching strictness.</td></tr>
<tr><td>Some chants are missed</td><td>Lower Word matching strictness by 1–2, or retrain that person, chanting the examples at your normal speed.</td></tr>
<tr><td>Other sounds are counted</td><td>Raise Word matching strictness by 1–2. Make sure only trained people chant near the counter.</td></tr>
<tr><td>Training does not advance (stays 0/20)</td><td>Say the word louder, with a clear half-second pause after each one; taps and whispers are not accepted as examples.</td></tr>
<tr><td>Lots of background noise</td><td>Keep the counter off tables with fans or motors on them (a soft pad under it helps). Use a short separate 3V3 wire to the mic and add a 10 µF capacitor across its VDD and GND.</td></tr>
<tr><td>Wrong names</td><td>Retrain the people involved with more chants; raise Strictness.</td></tr>
<tr><td>Everything goes to Guest</td><td>Lower Strictness, or train again in the room where the device is used.</td></tr>
<tr><td>Time shows --:--:--</td><td>No internet. Counting continues; the clock is set once WiFi works.</td></tr>
<tr><td>Cannot open japcounter.local</td><td>Use the IP address shown on the screen at start-up, or look for "JapCounter" in your router's device list.</td></tr>
<tr><td>Forgot / changed WiFi password</td><td>Hold BOOT for 5 seconds, then do the WiFi setup again.</td></tr>
<tr><td>No "SD" in the top bar</td><td>Format the card as FAT32, check the SD_CS and SD_MISO wires against the wiring table for your board, restart.</td></tr>
</table>

<h2>15. Technical specifications</h2>
<table>
<tr><td>Processor</td><td>ESP32 (DevKit V1): dual core 240 MHz, 520 KB RAM, 4 MB flash<br>or ESP32-S3 (N16R8): dual core 240 MHz, 512 KB RAM, 16 MB flash, 8 MB PSRAM</td></tr>
<tr><td>Microphone</td><td>INMP441, 24-bit I2S, sampled at 16 kHz</td></tr>
<tr><td>Display</td><td>1.8" ST7735, 160 × 128, adjustable backlight</td></tr>
<tr><td>Jap detection</td><td>Adaptive noise floor, voice burst + silence gap, adjustable</td></tr>
<tr><td>Voice recognition</td><td>MFCC + pitch fingerprint, nearest-neighbour match; word patterns (DTW) per person; 2 people</td></tr>
<tr><td>Storage</td><td>Counts in flash (NVS), daily history in flash (LittleFS), optional SD card log</td></tr>
<tr><td>Network</td><td>2.4 GHz WiFi, setup hotspot with captive portal, mDNS name <code>japcounter.local</code></td></tr>
<tr><td>Time</td><td>Internet time (NTP), Indian Standard Time (UTC+5:30)</td></tr>
<tr><td>Power</td><td>5 V USB-C, about 0.15–0.25 A</td></tr>
</table>
</div>

<div class="page">
<h2>16. The Android app</h2>
<p>The <b>Naam Jap</b> app (<code>NaamJap-1.1.0.apk</code>) controls the counter from an Android phone (Android 8.0 or newer) on the <b>same WiFi</b>.</p>
<h3>Installing</h3>
<ol>
<li>Copy <code>NaamJap-1.1.0.apk</code> to the phone (USB cable, Google Drive, WhatsApp to yourself…).</li>
<li>Open it. Android asks to allow installing apps from this source: allow it for the app you opened it with, then tap <b>Install</b>.</li>
<li>If Play Protect warns that the app is unknown, choose <b>Install anyway</b> — the app is not from the Play Store, but it is signed and talks only to your counter.</li>
</ol>
<h3>Connecting</h3>
<ol>
<li>Make sure the counter's WiFi is set up (section 4) and the phone is on the same WiFi.</li>
<li>Open the app. It searches the WiFi and lists the counter by name, e.g. <b>JapCounter-A1B2</b>. Tap it.</li>
<li>If nothing is found (some routers block discovery), type the counter's IP address under <b>Connect by IP address</b>.</li>
<li>The app remembers the counter and connects to it automatically next time. If the router gives the counter a new address, the app finds it again by itself.</li>
</ol>
<div class="tip"><b>New counter without WiFi?</b> On the app's first screen, follow <b>New counter?</b>: join the counter's hotspot in the phone's WiFi settings, then tap <b>Open setup page</b> to choose your home WiFi.</div>
<h3>Screens</h3>
<table>
<tr><th>Screen</th><th>What you can do</th></tr>
<tr><td>Live</td><td>Who is chanting, their count, malas and total, family totals, live microphone level, Pause / Resume, quick +1 / −1, training progress</td></tr>
<tr><td>People</td><td>Add people, train or retrain voices, add more chants, rename, clear voice, remove, correct counts (−1 / +1 / Set)</td></tr>
<tr><td>History</td><td>Every finished day with each person's count; <b>Share CSV</b> sends the full history to WhatsApp, e-mail, Drive…</td></tr>
<tr><td>Settings</td><td>All detection, recognition and screen settings; counter details; change or forget the counter's WiFi; reset all counts; switch counter</td></tr>
<tr><td>Web page</td><td>Opens the counter's own web page inside the app</td></tr>
</table>
<div class="note">The app works only while the phone is on the same WiFi as the counter. When you leave home it shows <b>Not reachable</b> and reconnects by itself when you are back.</div>
</div>
</body></html>"""


def find_browser():
    for p in [r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
              r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
              r"C:\Program Files\Google\Chrome\Application\chrome.exe"]:
        if os.path.exists(p):
            return p
    sys.exit("Edge or Chrome not found")


def to_pdf(browser, html_path, pdf_path):
    url = "file:///" + html_path.replace("\\", "/")
    subprocess.run([browser, "--headless=new", "--disable-gpu", "--no-pdf-header-footer",
                    f"--print-to-pdf={pdf_path}", url], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
    print("wrote", pdf_path)


def main():
    browser = find_browser()
    src = os.path.join(HERE, "src")
    os.makedirs(src, exist_ok=True)
    for name, html in [("Schematic", schematic_html()), ("User_Manual", manual_html())]:
        html_path = os.path.join(src, name + ".html")
        with open(html_path, "w", encoding="utf-8") as f:
            f.write(html)
        to_pdf(browser, html_path, os.path.join(HERE, name + ".pdf"))


if __name__ == "__main__":
    main()
