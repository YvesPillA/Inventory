"""Rebuild the bundled identifier fonts (requires fonttools==4.68.0).

Pass the upstream JetBrainsMono[wght].ttf as the only argument. Its pinned
SHA256 is checked before generating the fonts; normal Android builds use
the checked-in TTF files and do not require Python or network access.
"""
from pathlib import Path
import hashlib
import math
import sys

from fontTools import subset
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

ROOT = Path(__file__).resolve().parent.parent


def build(source, weight, style):
    font = instantiateVariableFont(TTFont(source), {"wght": weight}, inplace=False)
    # Select the upstream slashed-zero alternate without changing U+0030.
    for table in font["cmap"].tables:
        if table.isUnicode() and 48 in table.cmap:
            table.cmap[48] = "zero.zero"
    # A circular O needs a wider advance than the other identifier glyphs.
    pen = TTGlyphPen(None)
    for radius, direction in [(375, -1), (375 - {400: 90, 600: 110, 700: 120}[weight], 1)]:
        def point(angle, scale=1):
            return (round(405 + radius * scale * math.cos(angle)),
                    round(365 + radius * scale * math.sin(angle)))
        pen.moveTo(point(math.pi / 2))
        for index in range(8):
            angle = math.pi / 2 + direction * index * math.pi / 4
            pen.qCurveTo(point(angle + direction * math.pi / 8, 1 / math.cos(math.pi / 8)),
                         point(angle + direction * math.pi / 4))
        pen.closePath()
    name = font.getBestCmap()[ord("O")]
    font["glyf"][name] = pen.glyph()
    font["hmtx"][name] = (810, 30)
    font["post"].isFixedPitch = 0
    for name_id, value in {1: "FieldStock Identifiers", 2: style, 3: "FieldStockIdentifiers-" + style,
                           4: "FieldStock Identifiers " + style, 6: "FieldStockIdentifiers-" + style,
                           16: "FieldStock Identifiers", 17: style}.items():
        for record in font["name"].names:
            if record.nameID == name_id:
                record.string = value.encode(record.getEncoding())
    options = subset.Options()
    options.layout_features = []  # Preserve literal identifier characters, without code ligatures.
    sub = subset.Subsetter(options)
    sub.populate(unicodes=range(32, 127))
    sub.subset(font)
    target = ROOT / "app/src/main/res/font" / ("identifier_" + style.lower() + ".ttf")
    target.parent.mkdir(parents=True, exist_ok=True)
    font.save(target)


if __name__ == "__main__":
    source = Path(sys.argv[1])
    expected = "48715a42ec242c21e9f02692891e147d022299a52e48d5e413e1a942193ffeda"
    if hashlib.sha256(source.read_bytes()).hexdigest() != expected:
        raise SystemExit("Unexpected upstream font. See app/src/main/assets/fonts/NOTICE.txt.")
    for weight, style in [(400, "Regular"), (600, "SemiBold"), (700, "Bold")]:
        build(source, weight, style)
