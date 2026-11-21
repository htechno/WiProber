"""Pack a MACLookup JSON download into the scanner's offline resource (no build-time network)."""
import argparse
import json
import re
import struct
import unicodedata
from pathlib import Path

BLOCK_BITS = {"MA-L": 24, "MA-M": 28, "MA-S": 36, "IAB": 36}
# Keep common infrastructure brands recognizable in an eight-character OUI field.
ALIASES = {
    "ruckus wireless": "Ruckus", "routerboard.com": "RouterBd",
    "hewlett packard enterprise": "HPE", "hewlett packard": "HP",
    "tp-link": "TP-Link", "cisco systems": "Cisco", "ubiquiti": "Ubiquiti",
    "asustek": "ASUS", "huawei": "Huawei", "aruba networks": "Aruba",
    "keenetic": "Keenetic", "d-link": "D-Link",
}
LEGAL_SUFFIX = re.compile(
    r"(?:[\s,.]+(?:co|ltd|limited|inc|llc|corp|corporation|company|gmbh|ag|sarl|sa|bv|plc)\b[.,]*)+$",
    re.IGNORECASE,
)


def short_name(name):
    folded = name.casefold()
    for brand, alias in ALIASES.items():
        if folded.startswith(brand) and (len(name) == len(brand) or not folded[len(brand)].isalnum()):
            return alias
    stem = LEGAL_SUFFIX.sub("", name)
    compact = "".join(c for c in stem if c.isalnum() or c == "-") or name
    width = 0
    for index, char in enumerate(compact):
        width += 2 if unicodedata.east_asian_width(char) in ("W", "F") else 1
        if width > 8:
            return compact[:index]
    return compact


def pack(records):
    assignments = {}
    for record in records:
        prefix = record["macPrefix"].replace(":", "").upper()
        bits = BLOCK_BITS.get(record["blockType"])
        if bits is None:
            # Unassigned canonical placeholders may mask a less-specific allocation.
            if record["private"] and re.fullmatch(r"(?:[0-9A-F]{6}|[0-9A-F]{7}|[0-9A-F]{9})", prefix):
                assignments.setdefault((int(prefix, 16) << 8) | (len(prefix) * 4), "")
            continue
        if len(prefix) * 4 != bits or not re.fullmatch(r"[0-9A-F]+", prefix):
            raise ValueError(f"Unexpected {record['blockType']} prefix: {prefix}")
        if int(prefix[:2], 16) & 3:  # Group and locally administered addresses.
            continue
        name = " ".join(record["vendorName"].split())
        if record["private"] or name.casefold() in {"private", "ieee registration authority"}:
            name = ""
        assignments[(int(prefix, 16) << 8) | bits] = name
    if not assignments:
        raise ValueError("No IEEE assignments in input")

    names = sorted(set(assignments.values()) - {""})
    indices = {name: i for i, name in enumerate(names, start=1)}
    # Big endian. Index zero means masked/unassigned. Both strings are length-prefixed UTF-8.
    data = bytearray(struct.pack(">4sH", b"OUI1", len(names)))
    for name in names:
        for text in (short_name(name), name):
            encoded = text.encode("utf-8")
            data.extend(struct.pack(">H", len(encoded)))
            data.extend(encoded)
    data.extend(struct.pack(">I", len(assignments)))
    for key, name in sorted(assignments.items()):
        data.extend(struct.pack(">QH", key, indices.get(name, 0)))
    return data


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("json_download", type=Path)
    args = parser.parse_args()
    dest = Path(__file__).resolve().parents[1] / "app/src/main/res/raw/mac_vendors.bin"
    dest.write_bytes(pack(json.loads(args.json_download.read_text(encoding="utf-8-sig"))))
