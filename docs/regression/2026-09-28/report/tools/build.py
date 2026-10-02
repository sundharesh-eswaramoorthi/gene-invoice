#!/usr/bin/env python3
"""Builds the API and UI test report pages from ../../data/final/report-data.json and slides.json.

Usage: build.py "<cost sentence>" [api_url] [ui_url]
Writes api-report.html and ui-report.html into report/, one level up. Screenshots are read from report/slides/.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, '..', '..', 'data', 'final', 'report-data.json')

cost = sys.argv[1] if len(sys.argv) > 1 else ''
api_url = sys.argv[2] if len(sys.argv) > 2 else ''
ui_url = sys.argv[3] if len(sys.argv) > 3 else ''

d = json.load(open(DATA))
tpl = open(os.path.join(HERE, 'template.html')).read()

# The page prefixes gallery files with "slides/" itself.
for g in d['ui'].get('gallery', []):
    if g['file'].startswith('assets/'):
        g['file'] = g['file'][len('assets/'):]

SLIDES_FILE = os.path.join(HERE, 'slides.json')
slides = json.load(open(SLIDES_FILE)) if os.path.exists(SLIDES_FILE) else []

GALLERY = f'''<section aria-labelledby="s-slides" id="slides">
    <div class="sec-head"><h2 id="s-slides">Slide view: every screenshot</h2><p class="muted">All {len(slides)} screenshots taken by the UI cases in the final run, in test order, each headed by the case it belongs to and its result. Use the arrows, the arrow keys or the slider to move; narrow by area or result; open full screen to present.</p></div>
    <div class="slideview" id="sv" tabindex="0" aria-roledescription="slide view" aria-label="Screenshots from the final UI run">
      <div class="sv-head" aria-live="polite">
        <div class="sv-top"><span class="pill" id="sv-pill"></span><span class="id" id="sv-id"></span><span class="muted" id="sv-meta"></span><span class="sv-count" id="sv-count"></span></div>
        <h3 class="sv-title" id="sv-title"></h3>
        <p class="sv-cap" id="sv-cap"></p>
      </div>
      <div class="sv-stage" id="sv-stage">
        <img id="sv-img" alt="">
        <button type="button" class="sv-nav sv-prev" id="sv-prev" aria-label="Previous screenshot">&#8249;</button>
        <button type="button" class="sv-nav sv-next" id="sv-next" aria-label="Next screenshot">&#8250;</button>
      </div>
      <div class="sv-controls">
        <button type="button" class="btn" id="sv-prev2">Previous</button>
        <input type="range" id="sv-range" min="0" max="0" value="0" aria-label="Go to screenshot">
        <button type="button" class="btn" id="sv-next2">Next</button>
        <label class="sr" for="sv-area" style="position:absolute;left:-9999px">Area</label>
        <select id="sv-area"></select>
        <label class="sr" for="sv-result" style="position:absolute;left:-9999px">Result</label>
        <select id="sv-result"></select>
        <button type="button" class="btn" id="sv-fs">Full screen</button>
        <span class="sv-hint">Keys: ← → to move, Home / End for first and last.</span>
      </div>
    </div>
  </section>'''


def switch(current):
    def pill(label, url, is_current):
        if is_current:
            return f'<span class="tabpill" aria-current="page">{label}</span>'
        if url:
            return f'<a class="tabpill" href="{url}">{label}</a>'
        return ''
    out = pill('API report', api_url, current == 'API') + pill('UI report', ui_url, current == 'UI')
    if current == 'UI' and slides:
        out += f'<a class="tabpill" href="#slides">Slide view · {len(slides)} screenshots</a>'
    return out


def page(kind):
    block = d['api'] if kind == 'API' else d['ui']
    payload = {
        'kind': kind,
        'report': block,
        'meta': d['meta'],
        'history': d['history'],
        'decisions': d['decisions'],
        'knownLimitations': [x for x in d['knownLimitations']
                             if kind == 'UI' or x.get('kind') != 'UI-only'],
        'cost': cost,
        'slides': slides if kind == 'UI' else [],
    }
    blob = json.dumps(payload, ensure_ascii=False).replace('</', '<\\/')
    title = 'GeneInvoice API Verification' if kind == 'API' else 'GeneInvoice UI Verification'
    html = (tpl.replace('__TITLE__', title)
               .replace('__SWITCH__', switch(kind))
               .replace('__GALLERY__', GALLERY if kind == 'UI' else '')
               .replace('__DATA__', blob))
    path = os.path.join(HERE, '..', 'api-report.html' if kind == 'API' else 'ui-report.html')
    open(path, 'w').write(html)
    return path, len(html)


for k in ('API', 'UI'):
    p, n = page(k)
    print(f'{k}: {p} ({n/1024:.0f} KB)')
