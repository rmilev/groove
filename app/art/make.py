import math, sys
out = sys.argv[1]
def lerp(a, b, t): return a + (b - a) * t
def color(t):
    c0, c1 = (0x1E, 0x6B, 0x55), (0xC8, 0xF7, 0xE6)
    t = t ** 1.6
    return "#%02X%02X%02X" % tuple(round(lerp(a, b, t)) for a, b in zip(c0, c1))

def record(cx, cy, scale, svg=True):
    """Grooves, lit groove brightening towards the needle dot, label. Returns SVG elements or VectorDrawable paths."""
    parts = []
    def circle_path(r):  # full circle as path, for VectorDrawable
        return f"M{cx - r:.2f},{cy:.2f}a{r:.2f},{r:.2f} 0 1,0 {2*r:.2f},0a{r:.2f},{r:.2f} 0 1,0 {-2*r:.2f},0"
    disc = 134 * scale
    if svg: parts.append(f'<circle cx="{cx}" cy="{cy}" r="{disc}" fill="#09120F"/>')
    else: parts.append(f'<path android:fillColor="#FF09120F" android:pathData="{circle_path(disc)}"/>')
    for i, r0 in enumerate(range(36, 132, 7)):
        r = r0 * scale; w = (1.6 if i % 3 else 2.4) * scale
        if svg: parts.append(f'<circle cx="{cx}" cy="{cy}" r="{r:.2f}" fill="none" stroke="#1C3A32" stroke-width="{w:.2f}"/>')
        else: parts.append(f'<path android:strokeColor="#FF1C3A32" android:strokeWidth="{w:.2f}" android:pathData="{circle_path(r)}"/>')
    R = 92 * scale; a0, a1 = -90.0, 200.0; n = 48
    def pt(a): return cx + R * math.cos(math.radians(a)), cy + R * math.sin(math.radians(a))
    if svg:
        x0, y0 = pt(a0); x1, y1 = pt(a1)
        parts.append(f'<path d="M{x0:.1f},{y0:.1f} A{R},{R} 0 1 1 {x1:.1f},{y1:.1f}" fill="none" stroke="#5DCAA5" stroke-opacity="0.22" stroke-width="{18*scale}" stroke-linecap="round" filter="url(#soft)"/>')
    for k in range(n):
        s, e = lerp(a0, a1, k / n), lerp(a0, a1, (k + 1) / n) + 0.6
        (xs, ys), (xe, ye) = pt(s), pt(min(e, a1))
        cap = "round" if k in (0, n - 1) else "butt"
        col = color((k + 0.5) / n)
        if svg: parts.append(f'<path d="M{xs:.2f},{ys:.2f} A{R:.2f},{R:.2f} 0 0 1 {xe:.2f},{ye:.2f}" fill="none" stroke="{col}" stroke-width="{7*scale:.2f}" stroke-linecap="{cap}"/>')
        else: parts.append(f'<path android:strokeColor="#FF{col[1:]}" android:strokeWidth="{7*scale:.2f}" android:strokeLineCap="{cap}" android:pathData="M{xs:.2f},{ys:.2f}A{R:.2f},{R:.2f} 0 0,1 {xe:.2f},{ye:.2f}"/>')
    x1, y1 = pt(a1)
    for (r, c) in ((8 * scale, "E9FFF7"), (22 * scale, None), (5 * scale, "09120F")):
        if c is None: continue
    if svg:
        parts.append(f'<circle cx="{x1:.1f}" cy="{y1:.1f}" r="{8*scale}" fill="#F2FFFA"/>')
        parts.append(f'<circle cx="{cx}" cy="{cy}" r="{22*scale}" fill="#5DCAA5"/><circle cx="{cx}" cy="{cy}" r="{5*scale}" fill="#09120F"/>')
    else:
        def dot(x, y, r): return f"M{x - r:.2f},{y:.2f}a{r:.2f},{r:.2f} 0 1,0 {2*r:.2f},0a{r:.2f},{r:.2f} 0 1,0 {-2*r:.2f},0"
        parts.append(f'<path android:fillColor="#FFF2FFFA" android:pathData="{dot(x1, y1, 8*scale)}"/>')
        parts.append(f'<path android:fillColor="#FF5DCAA5" android:pathData="{dot(cx, cy, 22*scale)}"/>')
        parts.append(f'<path android:fillColor="#FF09120F" android:pathData="{dot(cx, cy, 5*scale)}"/>')
    return "\n".join(parts)

defs = '''<defs>
 <radialGradient id="bg" cx="{cx}" cy="{cy}" r="{r}" gradientUnits="userSpaceOnUse">
  <stop offset="0" stop-color="#16362E"/><stop offset="0.55" stop-color="#0C1815"/><stop offset="1" stop-color="#070D0B"/>
 </radialGradient>
 <filter id="soft" x="-20%" y="-20%" width="140%" height="140%"><feGaussianBlur stdDeviation="6"/></filter>
</defs>'''
banner = f'''<svg xmlns="http://www.w3.org/2000/svg" width="640" height="360" viewBox="0 0 640 360">
{defs.format(cx=178, cy=180, r=520)}
<rect width="640" height="360" fill="url(#bg)"/>
{record(178, 180, 1.0)}
<text x="352" y="196" fill="#E9FFF7" font-family="Avenir Next" font-weight="700" font-size="78" letter-spacing="-2.5">groove</text>
<text x="356" y="236" fill="#5DCAA5" font-family="Avenir Next" font-weight="500" font-size="21" letter-spacing="5">LIVE LYRICS</text>
</svg>'''
open(f"{out}/banner.svg", "w").write(banner)
icon = f'''<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
{defs.format(cx=256, cy=256, r=400)}
<rect width="512" height="512" rx="112" fill="url(#bg)"/>
{record(256, 256, 1.55)}
</svg>'''
open(f"{out}/icon.svg", "w").write(icon)
# Adaptive icon foreground: 108dp canvas, content inside the 66dp safe circle (scale so the disc radius is ~33)
fg = record(54, 54, 33 / 134, svg=False)
open(f"{out}/ic_launcher_foreground.xml", "w").write(
    '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n' + fg + "\n</vector>\n")
