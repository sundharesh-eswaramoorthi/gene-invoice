#!/usr/bin/env python3
"""Collects every final-run UI screenshot, matches it to its test case and
writes compressed slides plus slides.json for the UI report's slide view.

Needs the raw final-run folder (runs/final/<area>/shots/*.png and report-data.json under final/), which is not
kept in docs; point E2E at it. The slides and slides.json it produced are already in report/.
"""
import glob
import json
import os
import re
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
E = os.environ.get('E2E', os.path.join(HERE, '..', '..', 'scripts'))
OUT = os.path.join(HERE, '..', 'slides')
os.makedirs(OUT, exist_ok=True)

AREAS = ['regions', 'asof', 'approvals', 'tasks', 'automation', 'regression', 'migration']
report = json.load(open(os.path.join(E, 'final', 'report-data.json')))
captions = {}
for g in report['ui'].get('gallery', []):
    captions[os.path.splitext(os.path.basename(g['file']))[0].split('-', 1)[-1]] = g['caption']

# Every case of the final run, by area.
cases = {}
for area in AREAS:
    for name, source in ((f'{area}.json', 'suite'), (f'{area}-explore.json', 'explore')):
        p = os.path.join(E, 'runs', 'final', name)
        if not os.path.exists(p):
            continue
        for c in json.load(open(p)).get('cases', []):
            cases.setdefault(area, []).append({**c, '_source': source})


def natural(s):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r'(\d+)', s)]


def owner(area, png):
    """The case this screenshot belongs to: named by the case, else the longest case id the file name starts with."""
    base = os.path.basename(png)
    source = 'explore' if '/explore/' in png else 'suite'
    for c in cases.get(area, []):
        refs = [c.get('screenshot')] + list(c.get('screenshots') or [])
        # Some cases list several screenshots in one comma-separated string.
        names = [os.path.basename(x.strip()) for r in refs if isinstance(r, str) for x in r.split(',')]
        if base in names:
            return c
    stem = os.path.splitext(base)[0]
    best = None
    for c in cases.get(area, []):
        cid = c.get('id') or ''
        if cid and (stem == cid or stem.startswith(cid + '-')) and (best is None or len(cid) > len(best['id'])):
            best = c
    if best:
        return best
    # Short names such as "U8-rule" or "XU-02b-1": try ever shorter leading parts, with any
    # trailing sub-step letter removed, against the end of each case id ("MIG-X-U8", "AUT-XU-02").
    parts = stem.split('-')
    pool = sorted(cases.get(area, []), key=lambda c: (c['_source'] != source, c.get('kind') != 'UI'))
    for n in range(len(parts), 0, -1):
        cand = re.sub(r'(?<=\d)[a-z]$', '', '-'.join(parts[:n]))
        for c in pool:
            cid = c.get('id') or ''
            if cid == cand or cid.endswith('-' + cand):
                return c
    for d in report['ui'].get('defects', []):
        if stem.startswith(d['id']):
            return {'id': d['id'], 'title': 'Open defect: ' + d['title'], 'feature': 'Evidence for the open defect',
                    'prd': d.get('prd', ''), 'status': 'DEFECT'}
    return None


slides = []
for area in AREAS:
    pngs = sorted(glob.glob(os.path.join(E, 'runs', 'final', area, 'shots', '**', '*.png'), recursive=True),
                  key=lambda p: ('/explore/' in p, natural(os.path.basename(p))))
    for p in pngs:
        c = owner(area, p)
        stem = os.path.splitext(os.path.basename(p))[0]
        out = f'{area}-{stem}.jpg'
        dst = os.path.join(OUT, out)
        if not os.path.exists(dst):
            subprocess.run(['sips', '-s', 'format', 'jpeg', '-s', 'formatOptions', '68', '-Z', '1280', p, '--out', dst],
                           check=True, capture_output=True)
        slides.append({
            'file': out,
            'area': area,
            'caseId': c['id'] if c else stem,
            'title': (c.get('title') if c else '') or stem.replace('-', ' '),
            'feature': (c.get('feature') or '') if c else '',
            'prd': (c.get('prd') or '') if c else '',
            'status': (c.get('status') or '') if c else '',
            'source': 'explore' if '/explore/' in p else 'suite',
            'shot': stem,
            'caption': captions.get(stem, ''),
        })

# Number the screenshots within each case.
by_case = {}
for s in slides:
    by_case.setdefault((s['area'], s['caseId']), []).append(s)
for group in by_case.values():
    for i, s in enumerate(group, 1):
        s['step'], s['steps'] = i, len(group)

json.dump(slides, open(os.path.join(HERE, 'slides.json'), 'w'), ensure_ascii=False)
unmatched = [s['shot'] for s in slides if not s['status']]
size = sum(os.path.getsize(os.path.join(OUT, s['file'])) for s in slides)
print(f'{len(slides)} slides, {size / 1e6:.1f} MB, {len(unmatched)} without a matching case: {unmatched[:12]}')
