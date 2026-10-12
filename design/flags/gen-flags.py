#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Meelano VPN — flag tile generator.

Why a generator instead of 40 hand-placed SVGs: the flags are drawn at 19x14dp. At that size only
band geometry survives; a real Union Jack SVG and a 6-path approximation are indistinguishable on a
phone, and the approximation is 12x smaller. This file is the source of truth; the XML in
android/app/src/main/res/drawable/ is generated output. Run it after adding a country:

    python3 design/flags/gen-flags.py

Design rules encoded here:
  - viewport is exactly the tile (19x14), so no scaling fuzz from a 24-grid;
  - the last band snaps to the tile edge to avoid a sub-pixel seam between bands;
  - 3 colours max per flag, discs only where they are the identity of the flag;
  - a code we do not ship renders as the neutral chip in-app — never a wrong flag (see DESIGN-SYSTEM §4).
"""
import os, sys

W, H = 19.0, 14.0
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "android", "app", "src", "main", "res", "drawable")

R = '#D80027'; W_T = '#FFFFFF'; BLU = '#0052B4'; G = '#3E9A00'; Y = '#FFDA44'


def rect(x, y, w, h, c):
    return f'<path android:pathData="M{x:.2f},{y:.2f}H{x+w:.2f}V{y+h:.2f}H{x:.2f}Z" android:fillColor="{c}"/>'


def circle(cx, cy, r, c):
    return (f'<path android:pathData="M{cx-r:.2f},{cy:.2f}a{r:.2f},{r:.2f} 0 1,0 {2*r:.2f},0'
            f'a{r:.2f},{r:.2f} 0 1,0 {-2*r:.2f},0Z" android:fillColor="{c}"/>')


def bands_h(colors):
    n = len(colors); out = []
    for i, c in enumerate(colors):
        y = H * i / n
        h = H - y if i == n - 1 else H / n
        out.append(rect(0, y, W, h, c))
    return out


def bands_v(colors):
    n = len(colors); out = []
    for i, c in enumerate(colors):
        x = W * i / n
        w = W - x if i == n - 1 else W / n
        out.append(rect(x, 0, w, H, c))
    return out


def nordic(cross_c, white_under=None, xoff=6.2, t=3.0):
    out = []
    if white_under:                      # Norway/Denmark/Iceland: a white fimbriation first
        out.append(rect(0, H / 2 - (t + 2) / 2, W, t + 2, white_under))
        out.append(rect(xoff - (t + 2) / 2, 0, t + 2, H, white_under))
    out.append(rect(0, H / 2 - t / 2, W, t, cross_c))
    out.append(rect(xoff - t / 2, 0, t, H, cross_c))
    return out


def tri_hoist(x1, c):
    return f'<path android:pathData="M0,0L{x1:.2f},{H/2:.2f}L0,{H:.2f}Z" android:fillColor="{c}"/>'


def star(cx, cy, r, c):
    p = []
    import math
    for i in range(10):
        ang = -math.pi / 2 + i * math.pi / 5
        rad = r if i % 2 == 0 else r * 0.42
        p.append(f'{cx + rad*math.cos(ang):.2f},{cy + rad*math.sin(ang):.2f}')
    return f'<path android:pathData="M{p[0]}L{p[1]}L{p[2]}L{p[3]}L{p[4]}L{p[5]}L{p[6]}L{p[7]}L{p[8]}L{p[9]}Z" android:fillColor="{c}"/>'


def union_jack(x, y, w, h):
    """the canton of AU/NZ: diagonals + upright cross, in 4 paths"""
    return [
        rect(x, y, w, h, '#012169'),
        f'<path android:pathData="M{x:.2f},{y:.2f}L{x+w:.2f},{y+h:.2f}M{x+w:.2f},{y:.2f}L{x:.2f},{y+h:.2f}" '
        f'android:strokeColor="#FFFFFF" android:strokeWidth="{h*0.2:.2f}"/>',
        f'<path android:pathData="M{x:.2f},{y:.2f}L{x+w:.2f},{y+h:.2f}M{x+w:.2f},{y:.2f}L{x:.2f},{y+h:.2f}" '
        f'android:strokeColor="#C8102E" android:strokeWidth="{h*0.09:.2f}"/>',
        rect(x, y + h * 0.36, w, h * 0.28, '#FFFFFF'),
        rect(x + w * 0.36, y, w * 0.28, h, '#FFFFFF'),
        rect(x, y + h * 0.42, w, h * 0.16, '#C8102E'),
        rect(x + w * 0.42, y, w * 0.16, h, '#C8102E'),
    ]


def full_jack():
    out = [rect(0, 0, W, H, '#012169')]
    out += [
        f'<path android:pathData="M0,0L{W},{H}M{W},0L0,{H}" android:strokeColor="#FFFFFF" android:strokeWidth="3.6"/>',
        f'<path android:pathData="M0,0L{W},{H}M{W},0L0,{H}" android:strokeColor="#C8102E" android:strokeWidth="1.6"/>',
    ]
    out += [rect(0, H / 2 - 2.2, W, 4.4, '#FFFFFF'), rect(W / 2 - 2.2, 0, 4.4, H, '#FFFFFF'),
            rect(0, H / 2 - 1.3, W, 2.6, '#C8102E'), rect(W / 2 - 1.3, 0, 2.6, H, '#C8102E')]
    return out


SPECS = {
    'de': bands_h(['#111111', '#DD0000', '#FFCE00']),
    'be': bands_v(['#111111', '#FAA00D', '#EF3340']),
    'fr': bands_v(['#0055A4', '#FFFFFF', '#EF4135']),
    'nl': bands_h(['#AE1C28', '#FFFFFF', '#21468B']),
    'lu': bands_h(['#EF3340', '#FFFFFF', '#00A1DE']),
    'it': bands_v(['#009246', '#FFFFFF', '#CE2B37']),
    'es': [rect(0, 0, W, H, '#AA151B'), rect(0, H * 0.25, W, H * 0.5, '#F1BF00')],
    'pt': [rect(0, 0, W, H, '#DA291C'), rect(0, 0, W * 0.4, H, '#046A38'), circle(W * 0.4, H / 2, 2.0, '#FFD100')],
    'se': [rect(0, 0, W, H, '#006AA7')] + nordic('#FECC02', xoff=6.2),
    'no': [rect(0, 0, W, H, '#BA0C2F')] + nordic('#BA0C2F', '#FFFFFF', 6.2, 2.4),
    'dk': [rect(0, 0, W, H, '#C8102E')] + nordic('#FFFFFF', None, 6.0, 2.6),
    'fi': [rect(0, 0, W, H, '#FFFFFF')] + nordic('#003580', None, 6.0, 3.0),
    'is': [rect(0, 0, W, H, '#02529C')] + nordic('#DC1E35', '#FFFFFF', 6.0, 2.4),
    'pl': bands_h(['#FFFFFF', '#DC143C']),
    'cz': bands_h(['#FFFFFF', '#D7141A']) + [
        f'<path android:pathData="M0,0L{W*0.45:.2f},{H/2:.2f}L0,{H:.2f}Z" android:fillColor="#11457E"/>'],
    'at': bands_h(['#ED2939', '#FFFFFF', '#ED2939']),
    'ch': [rect(0, 0, W, H, '#DA291C')] + nordic('#FFFFFF', None, W / 2, 2.6),
    'gb': full_jack(),
    'us': [rect(0, 0, W, H, '#FFFFFF')] + [rect(0, H * i / 13.0, W, H / 13.0, '#B22234') for i in range(0, 13, 2)] + \
          [rect(0, 0, W * 0.42, H * 7 / 13.0, '#3C3B6E')],
    'ca': [rect(0, 0, W * 0.25, H, '#D80621'), rect(W * 0.75, 0, W * 0.25, H, '#D80621'),
           f'<path android:pathData="M{W/2:.2f},2.4L{W/2+1.2:.2f},{H/2-1:.2f}L{W/2+2.8:.2f},{H/2-2.6:.2f}'
           f'L{W/2+1.4:.2f},{H/2+1.8:.2f}L{W/2+2.6:.2f},{H/2+1.8:.2f}L{W/2:.2f},{H-2.2:.2f}'
           f'L{W/2-2.6:.2f},{H/2+1.8:.2f}L{W/2-1.4:.2f},{H/2+1.8:.2f}L{W/2-2.8:.2f},{H/2-2.6:.2f}'
           f'L{W/2-1.2:.2f},{H/2-1:.2f}Z" android:fillColor="#D80621"/>'],
    'tr': [rect(0, 0, W, H, '#E30A17'), circle(W * 0.36, H / 2, 3.0, '#FFFFFF'), circle(W * 0.42, H / 2, 2.4, '#E30A17'),
           star(W * 0.55, H / 2, 1.5, '#FFFFFF')],
    'ae': bands_h(['#00732F', '#FFFFFF', '#000000']) + [tri_hoist(W * 0.3, '#FF0000')],
    'qa': [rect(0, 0, W, H, '#8D1B3D'),
           f'<path android:pathData="M0,0L{W*0.32:.2f},0L{W*0.4:.2f},{H*0.1:.2f}L{W*0.32:.2f},{H*0.2:.2f}'
           f'L{W*0.4:.2f},{H*0.3:.2f}L{W*0.32:.2f},{H*0.4:.2f}L{W*0.4:.2f},{H*0.5:.2f}L{W*0.32:.2f},{H*0.6:.2f}'
           f'L{W*0.4:.2f},{H*0.7:.2f}L{W*0.32:.2f},{H*0.8:.2f}L{W*0.4:.2f},{H*0.9:.2f}L{W*0.32:.2f},{H:.2f}L0,{H:.2f}Z" '
           f'android:fillColor="#FFFFFF"/>'],
    'kz': [rect(0, 0, W, H, '#00AFCA'), circle(W * 0.62, H / 2, 2.4, '#FEC50C'), rect(0, 0, 2.0, H, '#FEC50C')],
    'am': bands_h(['#D90012', '#0033A0', '#F2A800']),
    'ge': [rect(0, 0, W, H, '#FFFFFF'), rect(0, H / 2 - 1.6, W, 3.2, '#FF0000'), rect(W / 2 - 1.6, 0, 3.2, H, '#FF0000')],
    'az': bands_h(['#00B5E2', '#EF3340', '#509E2F']) + [circle(W * 0.4, H / 2, 2.1, '#FFFFFF'),
                                                          circle(W * 0.46, H / 2, 1.7, '#EF3340'), star(W * 0.56, H / 2, 0.9, '#FFFFFF')],
    'ua': bands_h(['#0057B7', '#FFD700']),
    'ru': bands_h(['#FFFFFF', '#0039A6', '#D52B1E']),
    'md': bands_v(['#0046AE', '#FFD200', '#CC092F']),
    'jp': [rect(0, 0, W, H, '#FFFFFF'), circle(W / 2, H / 2, 3.2, '#BC002D')],
    'kr': [rect(0, 0, W, H, '#FFFFFF'), circle(W / 2, H / 2, 3.0, '#CD2E3A'),
           f'<path android:pathData="M{W/2-3:.2f},{H/2:.2f}a3,3 0 0,0 6,0a1.5,1.5 0 0,1 -3,0a1.5,1.5 0 0,0 -3,0Z" '
           f'android:fillColor="#0047A0"/>'],
    'sg': [rect(0, 0, W, H * 0.5, '#EF3340'), rect(0, H * 0.5, W, H * 0.5, '#FFFFFF'),
           circle(W * 0.26, H * 0.26, 2.2, '#FFFFFF'), circle(W * 0.33, H * 0.26, 1.7, '#EF3340'),
           star(W * 0.5, H * 0.22, 0.7, '#FFFFFF'), star(W * 0.58, H * 0.34, 0.7, '#FFFFFF')],
    'hk': [rect(0, 0, W, H, '#DE2910'), circle(W / 2, H / 2, 2.2, '#FFFFFF')],
    'in': bands_h(['#FF9933', '#FFFFFF', '#138808']) + [circle(W / 2, H / 2, 1.8, '#000080'),
                                                          circle(W / 2, H / 2, 0.5, '#FFFFFF')],
    'au': [rect(0, 0, W, H, '#012169')] + union_jack(0, 0, W * 0.5, H * 0.52) + \
          [circle(W * 0.72, H * 0.74, 1.0, '#FFFFFF'), circle(W * 0.58, H * 0.3, 0.7, '#FFFFFF'),
           circle(W * 0.88, H * 0.32, 0.7, '#FFFFFF'), circle(W * 0.82, H * 0.5, 0.7, '#FFFFFF')],
    'nz': [rect(0, 0, W, H, '#012169')] + union_jack(0, 0, W * 0.5, H * 0.52) + \
          [circle(W * 0.8, H * 0.28, 0.8, '#CC142B'), circle(W * 0.9, H * 0.52, 0.8, '#CC142B'),
           circle(W * 0.7, H * 0.72, 0.8, '#CC142B'), circle(W * 0.86, H * 0.82, 0.8, '#CC142B')],
    'br': [rect(0, 0, W, H, '#009C3B'),
           f'<path android:pathData="M{W/2:.2f},1.4L{W-1.4:.2f},{H/2:.2f}L{W/2:.2f},{H-1.4:.2f}L1.4,{H/2:.2f}Z" '
           f'android:fillColor="#FFDF00"/>', circle(W / 2, H / 2, 2.6, '#002776')],
    'ar': bands_h(['#74ACDF', '#FFFFFF', '#74ACDF']) + [circle(W / 2, H / 2, 1.3, '#F6B40E')],
    'eg': bands_h(['#CE1126', '#FFFFFF', '#000000']) + [
        f'<path android:pathData="M{W/2-1.1:.2f},{H/2-1.5:.2f}h2.2v2.2l-1.1,1.2l-1.1,-1.2z" android:fillColor="#C09300"/>'],
    'ir': bands_h(['#239F40', '#FFFFFF', '#DA0000']) + [circle(W / 2, H / 2, 1.1, '#DA0000')],
    'iq': bands_h(['#CE1126', '#FFFFFF', '#000000']) + [rect(W * 0.35, H * 0.38, W * 0.3, H * 0.24, '#007A3D')],
    'sk': bands_h(['#FFFFFF', '#0B4EA2', '#EF1B1B']),
    'si': bands_h(['#FFFFFF', '#0F52BA', '#C8102E']),
    'hu': bands_h(['#CD2A3E', '#FFFFFF', '#436F4D']),
    'lt': bands_h(['#FDB913', '#006A44', '#C1272D']),
    'lv': [rect(0, 0, W, H, '#9E3039'), rect(0, H * 0.4, W, H * 0.2, '#FFFFFF')],
    'ee': bands_h(['#007206', '#000000', '#FFFFFF']),
    'rs': bands_h(['#C6363C', '#FFFFFF', '#C6363C']),
    'bg': bands_h(['#FFFFFF', '#00966E', '#D62612']),
    'ro': bands_v(['#002B7F', '#FCD116', '#CE1126']),
    'gr': [rect(0, 0, W, H, '#0D5EAF')] + [rect(0, H * i / 9.0, W, H / 9.0, '#FFFFFF') for i in range(0, 9, 2)] +
          [rect(0, 0, W * 0.38, H * 0.44, '#0D5EAF'), rect(0, H * 0.16, W * 0.38, H * 0.12, '#FFFFFF'),
           rect(W * 0.13, 0, W * 0.12, H * 0.44, '#FFFFFF')],
}


def main():
    os.makedirs(OUT, exist_ok=True)
    written = 0
    for cc, body in sorted(SPECS.items()):
        header = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- flag_{cc}: a 19x14dp tile drawn for this app (docs/DESIGN-SYSTEM.md §4). Bands snap to\n'
            '     the tile edge so nothing blurs at 19dp. Generated by design/flags/gen-flags.py — edit that. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="19dp" android:height="14dp"\n'
            '    android:viewportWidth="19" android:viewportHeight="14">\n'
        )
        paths = ''.join(f'    {p}\n' for p in body if p)
        open(os.path.join(OUT, f'flag_{cc}.xml'), 'w', encoding='utf-8').write(header + paths + '</vector>\n')
        written += 1
    print(f'{written} flag tiles -> {OUT}')
    print('codes covered:', ' '.join(sorted(SPECS)))


if __name__ == '__main__':
    main()
