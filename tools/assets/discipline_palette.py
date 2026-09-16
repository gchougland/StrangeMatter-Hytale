"""Mineral material colors sampled from the original discipline icons."""

DISCIPLINES = {
    'insight': ('cognition', (84, 197, 91)),
    'energetic': ('energy', (58, 189, 232)),
    'gravitic': ('gravity', (215, 83, 53)),
    'shade': ('shadow', (135, 82, 208)),
    'spatial': ('space', (61, 136, 221)),
    'chrono': ('time', (230, 181, 56)),
}

MINERAL_RGB = {family: rgb for family, (_, rgb) in DISCIPLINES.items()}
MATERIALS = {'discipline_' + discipline: rgb for discipline, rgb in DISCIPLINES.values()}

def material(family):
    return 'discipline_' + DISCIPLINES[family][0]

def crystal_light(family):
    rgb = MINERAL_RGB[family]
    # Low-intensity native light, retaining a little neutral fill.
    lo, hi = min(rgb), max(rgb)
    return '#' + ''.join(format(1 + round((c - lo) * 4 / (hi - lo)), 'x') for c in rgb)
