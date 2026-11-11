"""
Generates the Loop logo (a true parametric Mobius strip) as:
  - app/src/main/res/drawable/ic_launcher_foreground.xml  (adaptive-icon foreground, full colour)
  - app/src/main/res/drawable/ic_launcher_monochrome.xml  (themed-icon silhouette)
  - tools/loop_logo_preview.svg                           (browser preview)

The in-app animated mark (ui/brand/LoopMark.kt) uses exactly the same maths, so the
launcher icon and the live logo are the same object.

Surface:  x = (R + s*cos(u/2)) cos u,  y = (R + s*cos(u/2)) sin u,  z = s*sin(u/2)
          u in [0, 2pi), s in [-w, w]   -> one edge, one face: a Mobius strip.
"""
import math, os, sys

R, W = 1.0, 0.58
NU, NS = 120, 5
TILT_X = math.radians(44)   # look down onto the loop
SPIN_Z = math.radians(-52)  # rotate in plane so the twist sits front-left
LIGHT = (-0.45, 0.55, 0.70)

# Cyclic brand gradient: aqua -> indigo -> violet -> indigo -> aqua
STOPS = [(0.00, (0x35, 0xE0, 0xC2)), (0.25, (0x6C, 0x7C, 0xFF)),
         (0.50, (0xB0, 0x7C, 0xFF)), (0.75, (0x6C, 0x7C, 0xFF)), (1.00, (0x35, 0xE0, 0xC2))]


def grad(t):
    t = t % 1.0
    for (t0, c0), (t1, c1) in zip(STOPS, STOPS[1:]):
        if t0 <= t <= t1:
            k = (t - t0) / (t1 - t0)
            return tuple(c0[i] + (c1[i] - c0[i]) * k for i in range(3))
    return STOPS[-1][1]


def surf(u, s):
    a = R + s * math.cos(u / 2)
    return (a * math.cos(u), a * math.sin(u), s * math.sin(u / 2))


def rot(p):
    x, y, z = p
    c, s = math.cos(SPIN_Z), math.sin(SPIN_Z)
    x, y = x * c - y * s, x * s + y * c
    c, s = math.cos(TILT_X), math.sin(TILT_X)
    y, z = y * c - z * s, y * s + z * c
    return (x, y, z)


def normal(u, s):
    e = 1e-4
    pu = [(a - b) / (2 * e) for a, b in zip(surf(u + e, s), surf(u - e, s))]
    ps = [(a - b) / (2 * e) for a, b in zip(surf(u, s + e), surf(u, s - e))]
    n = (pu[1] * ps[2] - pu[2] * ps[1], pu[2] * ps[0] - pu[0] * ps[2], pu[0] * ps[1] - pu[1] * ps[0])
    l = math.sqrt(sum(v * v for v in n)) or 1
    return rot(tuple(v / l for v in n))


def quads():
    out = []
    for i in range(NU):
        u0, u1 = 2 * math.pi * i / NU, 2 * math.pi * (i + 1) / NU
        um = (u0 + u1) / 2
        for j in range(NS):
            s0 = -W + 2 * W * j / NS
            s1 = -W + 2 * W * (j + 1) / NS
            sm = (s0 + s1) / 2
            pts = [rot(surf(u0, s0)), rot(surf(u1, s0)), rot(surf(u1, s1)), rot(surf(u0, s1))]
            n = normal(um, sm)
            lam = abs(n[0] * LIGHT[0] + n[1] * LIGHT[1] + n[2] * LIGHT[2]) / math.sqrt(sum(v * v for v in LIGHT))
            shade = 0.62 + 0.62 * lam
            # lighter toward the strip's edges to suggest thickness / sheen
            edge = abs(sm) / W
            shade *= 1.0 - 0.10 * (1 - edge)
            base = grad(um / (2 * math.pi))
            col = tuple(max(0, min(255, int(c * shade + (255 - c) * max(0, shade - 1) * 0.6))) for c in base)
            depth = sum(p[2] for p in pts) / 4
            out.append((depth, pts, col))
    out.sort(key=lambda q: q[0])
    return out


def fit(qs, box, margin):
    xs = [p[0] for _, pts, _ in qs for p in pts]
    ys = [p[1] for _, pts, _ in qs for p in pts]
    minx, maxx, miny, maxy = min(xs), max(xs), min(ys), max(ys)
    sc = (box - 2 * margin) / max(maxx - minx, maxy - miny)
    cx, cy = (minx + maxx) / 2, (miny + maxy) / 2
    return lambda p: (box / 2 + (p[0] - cx) * sc, box / 2 - (p[1] - cy) * sc)


def hexc(c):
    return "#%02X%02X%02X" % c


def path_data(pts):
    return "M%.2f,%.2f L%.2f,%.2f L%.2f,%.2f L%.2f,%.2f Z" % tuple(v for p in pts for v in p)


def vector(name, box, margin, mono=False, viewport=None):
    qs = quads()
    f = fit(qs, box, margin)
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
             '    android:width="%ddp" android:height="%ddp"' % (box, box),
             '    android:viewportWidth="%d" android:viewportHeight="%d">' % (box, box)]
    if mono:
        # one silhouette: union of all quads in a single colour
        d = " ".join(path_data([f(p) for p in pts]) for _, pts, _ in qs)
        lines.append('    <path android:fillColor="#FFFFFFFF" android:strokeColor="#FFFFFFFF" android:strokeWidth="0.5" android:pathData="%s"/>' % d)
    else:
        for _, pts, col in qs:
            lines.append('    <path android:fillColor="%s" android:strokeColor="%s" android:strokeWidth="0.45" android:strokeLineJoin="round" android:pathData="%s"/>'
                         % (hexc(col), hexc(col), path_data([f(p) for p in pts])))
    lines.append('</vector>')
    return "\n".join(lines)


def svg(box=512, margin=40):
    qs = quads()
    f = fit(qs, box, margin)
    body = "\n".join('<path d="%s" fill="%s" stroke="%s" stroke-width="0.6" stroke-linejoin="round"/>'
                     % (path_data([f(p) for p in pts]), hexc(col), hexc(col)) for _, pts, col in qs)
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d">'
            '<rect width="%d" height="%d" rx="96" fill="#0A0E1A"/>%s</svg>' % (box, box, box, box, box, box, body))


if __name__ == "__main__":
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    res = os.path.join(root, "app", "src", "main", "res", "drawable")
    os.makedirs(res, exist_ok=True)
    # Adaptive icon: 108dp canvas, 66dp safe zone -> mark fills ~ 62dp
    open(os.path.join(res, "ic_launcher_foreground.xml"), "w").write(vector("fg", 108, 24))
    open(os.path.join(res, "ic_launcher_monochrome.xml"), "w").write(vector("mono", 108, 24, mono=True))
    open(os.path.join(root, "tools", "loop_logo_preview.svg"), "w").write(svg())
    print("ok", len(quads()), "quads")
