#!/usr/bin/env python3
"""Extract brand-name string arrays from the decompiled HxdIR resources into a
Kotlin table mapping Bigme deviceType -> List<String> (brand name by index).

Brand names are factual identifiers (LG, Samsung, ...), not copyrightable
content. The index alignment only matters when the proprietary brand DB is
loaded (opt-in, user-supplied .rodata); for the open IRDB path names come from
filesystem folders instead.
"""
import re
import sys
import xml.etree.ElementTree as ET

SRC = "jadx-res/resources/res/values-en-rUS/arrays.xml"
OUT = "app/src/main/java/dev/ilyaask/openir/codec/BrandNames.kt"

# array name -> Bigme deviceType constant (from et.song.remotestar.hxd.ir.IRType)
ARRAY_TO_TYPE = {
    "strs_tv_brand": 8192,
    "strs_iptv_brand": 8448,
    "strs_dc_brand": 8960,
    "strs_ap_brand": 9984,
    "strs_audio_brand": 10496,
    "strs_hw_brand": 12032,
    "strs_stb_brand": 16384,
    "strs_dvd_brand": 24576,
    "strs_fans_brand": 32768,
    "strs_pjt_brand": 40960,
    "strs_air_brand": 49152,
    "strs_light_brand": 57344,
}


def esc(s: str) -> str:
    return s.replace("\\", "\\\\").replace("\"", "\\\"")


def main() -> int:
    root = ET.parse(SRC).getroot()
    by_type = {}
    for arr in root.findall("array"):
        name = arr.get("name", "")
        if name not in ARRAY_TO_TYPE:
            continue
        items = [it.text or "" for it in arr.findall("item")]
        by_type[ARRAY_TO_TYPE[name]] = items

    lines = [
        "package dev.ilyaask.openir.codec",
        "",
        "/**",
        " * Brand display names indexed exactly as the proprietary Bigme brand DB",
        " * expects (brand index -> name), grouped by Bigme `deviceType`.",
        " *",
        " * Source: decompiled `HxdIR` resources (`values-en-rUS/arrays.xml`,",
        " * `strs_<type>_brand`). Brand names are factual identifiers; the index",
        " * alignment is only used when the user opts into the proprietary brand DB.",
        " */",
        "object BrandNames {",
        "    val byDeviceType: Map<Int, List<String>> = mapOf(",
    ]
    for dt in sorted(by_type):
        names = by_type[dt]
        kv = ", ".join('"%s"' % esc(n) for n in names)
        lines.append(f"        {dt} to listOf({kv}),")
    lines += [
        "    )",
        "",
        "    fun names(deviceType: Int): List<String> = byDeviceType[deviceType] ?: emptyList()",
        "}",
        "",
    ]
    with open(OUT, "w") as f:
        f.write("\n".join(lines))
    print(f"wrote {OUT}: {sum(len(v) for v in by_type.values())} names across {len(by_type)} types")
    return 0


if __name__ == "__main__":
    sys.exit(main())
