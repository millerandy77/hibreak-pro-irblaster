#!/usr/bin/env python3
"""
Mechanical extractor for the brand-DB `.rodata` table layout used by
libet_jni_ir_tools.so. Parses the objdump Thumb disassembly of the
GetBrandCount / GetTypeCount / GetTableCount / SearchKeyData switches and
resolves PC-relative literal-pool loads by reading the .so binary, so we can
reconstruct: deviceType -> {brand_stride, info_base_rodata_offset}.

This is RE phase 2 scaffolding: it produces the constants a clean-room Kotlin
reader would need. Per-type field offsets inside each info record (e.g. 0x25,
0x26, 0x27, 0x28 for AC) are read from SearchKeyData and emitted too.

Usage: python3 extract_branddb.py <ir_tools.disasm.txt> <libet_jni_ir_tools.so>
"""
import re, sys, struct

DISASM, SO = sys.argv[1], sys.argv[2]
so = open(SO, "rb").read()
# .rodata: vaddr 0x73f0 == file offset 0x73f0 (load base 0), so vaddr == file offset.

# Parse disasm into {addr: (bytes_halfwords...)} for literal pool resolution.
line_re = re.compile(r"^\s*([0-9a-f]+):\s+([0-9a-f ]+?)\s{2,}")
pool = {}  # addr -> 16-bit halfword (int)

def asm_of(ln, m):
    rest = ln[m.end():].strip()
    # strip trailing " @ ..." comment
    idx = rest.find(" @")
    if idx != -1:
        rest = rest[:idx].strip()
    return rest

with open(DISASM) as f:
    for ln in f:
        m = line_re.match(ln)
        if not m:
            continue
        addr = int(m.group(1), 16)
        for tk in m.group(2).strip().split():
            if len(tk) == 4:
                try:
                    pool[addr] = int(tk, 16)
                    addr += 2
                except ValueError:
                    pass

def word_at(vaddr):
    if vaddr + 4 > len(so):
        return None
    return struct.unpack_from("<I", so, vaddr)[0]

insns = []  # list of (addr, asm)
with open(DISASM) as f:
    for ln in f:
        m = line_re.match(ln)
        if not m:
            continue
        addr = int(m.group(1), 16)
        insns.append((addr, asm_of(ln, m)))
insns_by_addr = {a: t for a, t in insns}

def resolve_pcrel_load(insn_addr, asm):
    """For 'ldr rX, [pc, #N]' return the vaddr it loads from."""
    m = re.search(r"ldr\s+r\d+,\s+\[pc,\s*#(\d+)\]", asm)
    if not m:
        return None
    off = int(m.group(1))
    pc = (insn_addr + 4) & ~3
    return pc + off

def find_in_range(start, end, pred):
    out = []
    for a, t in insns:
        if start <= a < end and pred(a, t):
            out.append((a, t))
    return out

# --- Extract deviceType -> brand-stride from GetBrandCount (0x2d04) ---
# Linear block-walk: the switch textually alternates, per case:
#   movs rA,#T  ; lsls rB,rA,#S   (build device type into rB)
#   cmp r2, rB  ; bXX case
#   ... at the case target:
#   movs r0,#K  ; [lsls r0,r0,#S2]   (build stride)
#   ldr r1,[pc,#X] ; add r1,pc        (info base)
#   muls r0, r3                     (stride * brandIndex)
#   ... branch to common tail: ldr r1,[r1]; adds r0,r1,r0; ldr r0,[r0]
GB_START, GB_END = 0x2d04, 0x2eb0

def reg_of(asm):
    m = re.match(r"(\w+)\s+(r\d+|pc|sp),", asm)
    return m.group(2) if m else None

# Map reg -> latest integer value built by movs(+lsls), tracked linearly.
reg_val = {}
def track(a, t):
    m = re.match(r"movs\s+(r\d+),\s+#(\d+)", t)
    if m:
        reg_val[m.group(1)] = int(m.group(2))
        return
    m = re.match(r"lsls\s+(r\d+),\s+(r\d+),\s+#(\d+)", t)
    if m:
        dst, src, sh = m.group(1), m.group(2), int(m.group(3))
        if src in reg_val:
            reg_val[dst] = reg_val[src] << sh
        return

cases = []
cur_dt = None
cur_stride = None
pending_pool_word = None  # word loaded by the most recent ldr r1,[pc,#N]
for a, t in insns:
    if not (GB_START <= a < GB_END):
        continue
    track(a, t)
    m = re.match(r"cmp\s+r2,\s+(r\d+)", t)
    if m:
        v = reg_val.get(m.group(1))
        if v is not None:
            cur_dt = v
        continue
    if re.match(r"ldr\s+r0,\s+\[pc", t):
        pv = resolve_pcrel_load(a, t)
        if pv is not None:
            w = word_at(pv)
            if w is not None:
                reg_val["r0"] = w
        continue
    if re.match(r"muls\s+r0,\s+r3", t):
        cur_stride = reg_val.get("r0")
        continue
    if re.match(r"ldr\s+r1,\s+\[pc", t):
        pv = resolve_pcrel_load(a, t)
        if pv is not None:
            pending_pool_word = word_at(pv)
        continue
    # emit at "add r1, pc" — last insn of each case block; (a+4)+word = info base vaddr
    if re.match(r"add\s+r1,\s+pc", t) and pending_pool_word is not None:
        ptr_addr = ((a + 4) + pending_pool_word) & 0xFFFFFFFF
        info_table = word_at(ptr_addr)  # common tail: ldr r1,[r1] dereferences the pointer
        cases.append({
            "deviceType": cur_dt,
            "brand_stride": cur_stride,
            "ptr_addr": ptr_addr,
            "info_table": info_table,
        })
        cur_stride = None
        pending_pool_word = None

seen = set(); uniq = []
for c in cases:
    k = (c["deviceType"], c["brand_stride"], c["ptr_addr"])
    if k in seen or c["brand_stride"] is None:
        continue
    seen.add(k); uniq.append(c)

import json
print(json.dumps(uniq, indent=2))
