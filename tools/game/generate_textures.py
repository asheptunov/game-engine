"""Generate this project's original 16x16 block art using only the Python standard library."""
from pathlib import Path
import struct
import zlib

SIZE = 16
DESTINATION = Path(__file__).resolve().parents[2] / "assets" / "game"


def noise(x, y):
    return ((x * 374761393 + y * 668265263 + 12345) ^ (x * y * 1274126177)) & 0xFFFFFFFF


def dirt(x, y):
    palette = [0x8D5835, 0x9D6741, 0xAB734A, 0x79482E, 0xB58155]
    return palette[noise(x, y) % len(palette)]


def grass(x, y):
    palette = [0x68A33B, 0x76B345, 0x589434, 0x86BC4B, 0x4B8230]
    return palette[noise(x // 2, y) % len(palette)]


def grass_side(x, y):
    edge = 3 + noise(x, 0) % 3
    return grass(x, y) if y < edge else dirt(x, y)


def stone(x, y):
    if (y + (x // 5) * 2) % 7 == 0 or (x + (y // 7) * 3) % 9 == 0:
        return 0x626974
    return [0x949AA3, 0xA5AAB1, 0x858D98, 0xB1B6BE][noise(x, y) % 4]


def bark(x, y):
    stripe = (x + (1 if y > 9 and 5 < x < 10 else 0)) % 5
    if stripe == 0:
        return 0x66442B
    return [0xA77B4C, 0xB58853, 0x956A41, 0xC19259][(stripe + noise(x, y) % 2) % 4]


def wood_end(x, y):
    ring = int(((x - 7.5) ** 2 + (y - 7.5) ** 2) ** .5)
    return [0xD5AF73, 0xB98D55, 0xE1BF85][ring % 3]


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


def write_texture(name, pattern):
    rows = bytearray()
    for y in range(SIZE):
        rows.append(0)  # PNG's unfiltered scanline marker.
        for x in range(SIZE):
            color = pattern(x, y)
            rows.extend((color >> 16, (color >> 8) & 255, color & 255))
    header = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 2, 0, 0, 0)
    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
    data += chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")
    (DESTINATION / name).write_bytes(data)


if __name__ == "__main__":
    DESTINATION.mkdir(parents=True, exist_ok=True)
    for name, pattern in {
        "dirt.png": dirt, "grass-top.png": grass, "grass-side.png": grass_side,
        "stone.png": stone, "wood-side.png": bark, "wood-end.png": wood_end,
    }.items():
        write_texture(name, pattern)
