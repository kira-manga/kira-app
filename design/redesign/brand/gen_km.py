#!/usr/bin/env python3
# Rebuild the YM icon glyph as "KM", keeping the original identity:
# dark-navy tile, blue->cyan diagonal gradient, glossy 3D depth, rounded bold letters, accent dot.
# Parametric so the review loop can tune. Outputs km.svg.
import sys

P = dict(
    SW=58,                 # stroke weight (bold)
    RADIUS=112,            # tile corner radius (~22%)
    # letter gradient (diagonal, lower-left dark -> upper-right cyan), like the original
    G0="#063f9e", G1="#2fd6ff",
    DOT=(404, 334, 17),
    GLOSS=0.30,            # white top sheen opacity
    DEPTH=7,               # 3D extrude depth (layers)
    CONNECT=False,         # tuck K toward M
)
# allow overrides: gen_km.py SW=62 GLOSS=0.2 ...
for a in sys.argv[1:]:
    if "=" in a:
        k, v = a.split("=", 1)
        if k in P:
            P[k] = type(P[k])(eval(v)) if k in ("DOT",) else (float(v) if "." in v or k=="GLOSS" else (v if v.startswith("#") else int(v))) if k not in ("CONNECT",) else (v.lower()=="true")

SW=int(P["SW"]); R=int(P["RADIUS"]); G0=P["G0"]; G1=P["G1"]; DOT=P["DOT"]; GLOSS=float(P["GLOSS"]); DEPTH=int(P["DEPTH"])

# letterforms (viewport 0..500), bold rounded; K replaces Y, M matched, sitting like the original
K = [
    [(156, 150), (156, 352)],          # stem
    [(252, 151), (170, 250)],          # upper arm
    [(170, 250), (260, 352)],          # lower arm
]
M = [
    [(292, 352), (292, 150), (338, 262), (384, 150), (384, 352)],
]
STROKES = K + M

def poly(pts):
    return 'M%.1f,%.1f' % pts[0] + ''.join(' L%.1f,%.1f' % p for p in pts[1:])

o = []
o.append('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 500 500" width="500" height="500">')
o.append('<defs>')
o.append('<radialGradient id="bg" cx="32%" cy="13%" r="125%">'
         '<stop offset="0" stop-color="#1c2740"/><stop offset="0.46" stop-color="#0e1626"/>'
         '<stop offset="1" stop-color="#070b14"/></radialGradient>')
o.append('<linearGradient id="lg" x1="120" y1="370" x2="395" y2="135" gradientUnits="userSpaceOnUse">'
         '<stop offset="0" stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient>' % (G0, G1))
o.append('<linearGradient id="gloss" x1="0" y1="0" x2="0" y2="1">'
         '<stop offset="0" stop-color="#ffffff" stop-opacity="%.2f"/>'
         '<stop offset="0.5" stop-color="#ffffff" stop-opacity="0"/></linearGradient>' % GLOSS)
o.append('<filter id="sh" x="-30%" y="-30%" width="160%" height="160%">'
         '<feDropShadow dx="0" dy="7" stdDeviation="7" flood-color="#02122e" flood-opacity="0.55"/></filter>')
o.append('</defs>')
o.append('<rect width="500" height="500" rx="%d" fill="url(#bg)"/>' % R)

# 3D depth: a few darker offset copies behind (down-right), then the gradient face on top
for i in range(DEPTH, 0, -1):
    oy = i * 1.1
    o.append('<g transform="translate(%.1f,%.1f)">' % (oy, oy))
    for s in STROKES:
        o.append('<path d="%s" fill="none" stroke="#04265f" stroke-width="%d" '
                 'stroke-linecap="round" stroke-linejoin="round"/>' % (poly(s), SW))
    o.append('</g>')
# front face: gradient stroke + drop shadow
o.append('<g filter="url(#sh)">')
for s in STROKES:
    o.append('<path d="%s" fill="none" stroke="url(#lg)" stroke-width="%d" '
             'stroke-linecap="round" stroke-linejoin="round"/>' % (poly(s), SW))
o.append('</g>')
# gloss sheen on top
for s in STROKES:
    o.append('<path d="%s" fill="none" stroke="url(#gloss)" stroke-width="%d" '
             'stroke-linecap="round" stroke-linejoin="round"/>' % (poly(s), SW))
# accent dot (with the same gradient + a tiny highlight)
o.append('<circle cx="%d" cy="%d" r="%d" fill="url(#lg)"/>' % DOT)
o.append('<circle cx="%d" cy="%d" r="%.1f" fill="#ffffff" opacity="0.35"/>' % (DOT[0]-4, DOT[1]-5, DOT[2]*0.4))
o.append('</svg>')

open('km.svg', 'w').write('\n'.join(o))
print('wrote km.svg  SW=%d DEPTH=%d GLOSS=%.2f' % (SW, DEPTH, GLOSS))
