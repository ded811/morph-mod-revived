#!/usr/bin/env python3
# Morph Mod Revived - LGPL-3.0-only. See ../../LICENSE, ../../LICENSE.GPL, ../../NOTICE.
"""Writes neoforge/src/gametest/resources/data/fabric-gametest-api-v1/structure/empty.nbt.

The NeoForge test mod needs the structure every shared gametest runs in by
default, "fabric-gametest-api-v1:empty". Fabric API ships it as an SNBT file
(data/fabric-gametest-api-v1/gametest/structure/empty.snbt) that only Fabric's
own structure-manager hook reads: 8x8x8, all 512 positions minecraft:air, no
entities, DataVersion 2730. Vanilla (and so NeoForge) loads structures from
data/<namespace>/structure/<path>.nbt, gzipped binary NBT in the standard
structure layout, so this writes that same structure in that layout:

    {DataVersion: 2730 (int),
     size: [8, 8, 8] (list of int),
     blocks: [{pos: [x, y, z] (list of int), state: 0 (int)}, ...512],
     palette: [{Name: "minecraft:air"}],
     entities: []}

size and every pos MUST be lists of ints, not int arrays: StructureTemplate.load
reads them with getListOrEmpty, so an int array would silently load as size
0,0,0 with every block at the origin (NeoForgeHarnessGameTests checks the
loaded result). DataVersion 2730 is Fabric's; the game data-fixes it up to the
current version on load, as it does Fabric's SNBT. Blocks are written in the
SNBT's order (y outermost, then x, then z).

The NBT is deterministic and the gzip header is fixed (mtime 0), but the
deflate bytes depend on the zlib build: classic zlib 1.2.x (Python 3.11 on
Windows, for one) reproduces the committed file byte for byte, while the zlib
bundled with Python 3.14 on Windows compresses one byte differently. So to
check the committed file, compare the DECOMPRESSED data (gzip -dc empty.nbt,
or gzip.decompress in Python), not the .nbt hash, and do not commit a
regenerated file whose decompressed content is unchanged.

Standard library only. Run from anywhere:
    python neoforge/tools/gen_empty_structure.py
"""
import gzip
import io
import os
import struct

TAG_END, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 0, 3, 8, 9, 10


def name(n):
    b = n.encode('utf-8')
    return struct.pack('>H', len(b)) + b


def int_payload(v):
    return struct.pack('>i', v)


def string_payload(s):
    return name(s)


def list_payload(element_type, payloads):
    return bytes([element_type]) + struct.pack('>i', len(payloads)) + b''.join(payloads)


def compound_payload(entries):
    """entries: list of (tag type, name, payload bytes)."""
    out = b''
    for tag_type, key, payload in entries:
        out += bytes([tag_type]) + name(key) + payload
    return out + bytes([TAG_END])


def int_list(values):
    return list_payload(TAG_INT, [int_payload(v) for v in values])


def build():
    blocks = []
    for y in range(8):
        for x in range(8):
            for z in range(8):
                blocks.append(compound_payload([
                    (TAG_LIST, 'pos', int_list([x, y, z])),
                    (TAG_INT, 'state', int_payload(0)),
                ]))
    palette = [compound_payload([(TAG_STRING, 'Name', string_payload('minecraft:air'))])]
    root = compound_payload([
        (TAG_INT, 'DataVersion', int_payload(2730)),
        (TAG_LIST, 'size', int_list([8, 8, 8])),
        (TAG_LIST, 'blocks', list_payload(TAG_COMPOUND, blocks)),
        (TAG_LIST, 'palette', list_payload(TAG_COMPOUND, palette)),
        # An empty list's element type is TAG_End, as vanilla writes it.
        (TAG_LIST, 'entities', list_payload(TAG_END, [])),
    ])
    # The root is an unnamed compound.
    return bytes([TAG_COMPOUND]) + name('') + root


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    target = os.path.join(here, '..', 'src', 'gametest', 'resources', 'data',
                          'fabric-gametest-api-v1', 'structure', 'empty.nbt')
    target = os.path.normpath(target)
    os.makedirs(os.path.dirname(target), exist_ok=True)
    raw = io.BytesIO()
    with gzip.GzipFile(fileobj=raw, mode='wb', mtime=0, filename='') as gz:
        gz.write(build())
    with open(target, 'wb') as f:
        f.write(raw.getvalue())
    print('wrote', target, len(raw.getvalue()), 'bytes')


if __name__ == '__main__':
    main()
