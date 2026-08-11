#!/usr/bin/env python3
"""Join the GetBrandCount per-type extraction (re/branddb_layout.json) with the
. so's dynamic symbol table to produce the full deviceType -> (name, stride,
info_table vaddr, info_table size) map for the brand DB.

vaddr == file offset for the first PT_LOAD (covers 0..0x6521b22), so the
info-table bytes are readable directly from the .so at st_value."""
import json, struct, sys

SO = sys.argv[1] if len(sys.argv) > 1 else "apk-raw/lib/armeabi/libet_jni_ir_tools.so"
so = open(SO, "rb").read()

# --- dynamic symtab ---
dyn_off = 0x6521cd0
symtab = strtab = 0
i = dyn_off
while True:
    tag, val = struct.unpack_from("<iI", so, i)
    if tag == 0: break
    if tag == 6: symtab = val
    if tag == 5: strtab = val
    i += 8

def sym(idx):
    o = symtab + idx * 16
    st_name, st_value, st_size, st_info, st_other, st_shndx = struct.unpack_from("<IIIBBH", so, o)
    nm = ""
    if st_name:
        s = strtab + st_name; e = so.index(b"\x00", s); nm = so[s:e].decode("latin1")
    return st_value, st_size, nm

# --- relocations: offset -> symidx ---
rel_off, rel_sz = 0x1e40, 0x1c8
off2sym = {}
for k in range(rel_sz // 8):
    o = rel_off + k * 8
    r_off, r_info = struct.unpack_from("<II", so, o)
    if (r_info & 0xff) == 21:  # R_ARM_GLOB_DAT
        off2sym[r_off] = r_info >> 8

# slot vaddr -> (name, value, size)
slot = {}
for off, si in off2sym.items():
    v, sz, nm = sym(si)
    slot[off] = (nm, v, sz)

layout = json.load(open("re/branddb_layout.json"))
print("deviceType  stride  ptr_slot        info_symbol                 info_vaddr    info_size")
out = []
for c in layout:
    dt = c["deviceType"]; pa = c["ptr_addr"]; st = c["brand_stride"]
    nm, v, sz = slot.get(pa, ("<unknown>", None, None))
    tag = ("0x%05x" % dt) if (dt and dt != 0xe000) else "<stale>"
    vs = ("0x%08x" % v) if v is not None else "<none>"
    zs = ("%d" % sz) if sz is not None else "<none>"
    print(f"  {tag:>9}  {st:>6}  0x{pa:08x}    {nm:<28} {vs:>12}  {zs}")
    out.append({"deviceType": dt, "stride": st, "ptr_slot": pa,
                "info_symbol": nm, "info_vaddr": v, "info_size": sz})

json.dump(out, open("re/branddb_full.json", "w"), indent=2)
print("\n-> re/branddb_full.json")

# also dump the full slot map (all device tables, even ones our walker lost)
print("\n=== full pointer-table slot map (slot -> info symbol) ===")
for off in sorted(slot):
    nm, v, sz = slot[off]
    print(f"  0x{off:08x} -> {nm:<30} vaddr=0x{v:08x} size={sz}")
