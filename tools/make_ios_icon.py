import math
import struct
import zlib
import os

SIZE = 1024
SS = 2  # supersampling
W = SIZE * SS

BG_TOP = (10, 48, 44)
BG_BOTTOM = (18, 133, 111)
WHITE = (255, 255, 255)
MINT = (198, 232, 222)
DEEP = (10, 62, 55)
PATH_COLOR = (250, 197, 92)


def lerp(a, b, t):
    return a + (b - a) * t


def bg_color(y):
    t = y / (W - 1)
    t = t ** 1.15
    return tuple(lerp(BG_TOP[i], BG_BOTTOM[i], t) for i in range(3))


def edge(ax, ay, bx, by, px, py):
    return (px - ax) * (by - ay) - (py - ay) * (bx - ax)


def in_polygon(px, py, poly):
    inside = False
    count = len(poly)
    for i in range(count):
        ax, ay = poly[i]
        bx, by = poly[(i + 1) % count]
        if (ay > py) != (by > py):
            x_cross = ax + (py - ay) * (bx - ax) / (by - ay)
            if px < x_cross:
                inside = not inside
    return inside


def seg_distance(px, py, ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return math.hypot(px - ax, py - ay)
    t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
    t = max(0.0, min(1.0, t))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


# Bentuk pada skala 1024, lalu dikalikan SS.
def s(v):
    return v * SS


far_peak = (s(690), s(430))
far = [(far_peak[0], far_peak[1]), (s(470), s(812)), (s(940), s(812))]

main_peak = (s(500), s(258))
main = [(main_peak[0], main_peak[1]), (s(150), s(812)), (s(846), s(812))]

cap = [(s(500), s(258)), (s(392), s(470)), (s(500), s(424)), (s(608), s(470))]

route = [(s(322), s(742)), (s(430), s(664)), (s(504), s(586)), (s(596), s(512)), (s(646), s(470))]
route_width = s(13)
dot_r = s(17)

rows = []
for y in range(W):
    row = bytearray()
    for x in range(W):
        color = bg_color(y)
        if in_polygon(x, y, far):
            color = MINT
        if in_polygon(x, y, main):
            color = WHITE
        best = min(seg_distance(x, y, route[i][0], route[i][1], route[i + 1][0], route[i + 1][1])
                   for i in range(len(route) - 1))
        if best <= route_width / 2:
            color = PATH_COLOR
        for cx, cy in (route[0], route[-1]):
            if math.hypot(x - cx, y - cy) <= dot_r:
                color = PATH_COLOR
        row.extend(int(c) for c in color)
    rows.append(bytes(row))

# Downscale box filter SSxSS.
out_rows = []
for y in range(SIZE):
    row = bytearray()
    for x in range(SIZE):
        r = g = b = 0
        for dy in range(SS):
            src = rows[y * SS + dy]
            base = (x * SS) * 3
            for dx in range(SS):
                r += src[base + dx * 3]
                g += src[base + dx * 3 + 1]
                b += src[base + dx * 3 + 2]
        n = SS * SS
        row.extend((r // n, g // n, b // n))
    out_rows.append(bytes(row))


def chunk(tag, data):
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)


raw = b"".join(b"\x00" + r for r in out_rows)
png = b"\x89PNG\r\n\x1a\n"
png += chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 2, 0, 0, 0))
png += chunk(b"IDAT", zlib.compress(raw, 9))
png += chunk(b"IEND", b"")

target = os.path.join("ios", "LerengOffline", "Assets.xcassets", "AppIcon.appiconset")
os.makedirs(target, exist_ok=True)
with open(os.path.join(target, "icon-1024.png"), "wb") as handle:
    handle.write(png)
print("wrote", os.path.join(target, "icon-1024.png"), len(png), "bytes")
