#!/usr/bin/env python3
"""Read the approved Figma file through the project's primary figma-cli tool."""
import argparse
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--resume', action='store_true', help='Reuse existing nonempty SVG exports after an interrupted export')
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
references = args.output / 'references'
references.mkdir(exist_ok=True)
environment = {**os.environ, 'FIGMA_FILE': 'itzephir — programmer theme sketches'}
code = '''const cs=(await figma.variables.getLocalVariableCollectionsAsync()).filter(c=>c.name.startsWith("Workspace"));
const vs=await figma.variables.getLocalVariablesAsync();return {file:figma.fileKey,
collections:cs.map(c=>({id:c.id,name:c.name,modes:c.modes})),
variables:vs.filter(v=>cs.some(c=>c.id===v.variableCollectionId)).map(v=>({id:v.id,name:v.name,type:v.resolvedType,collection:v.variableCollectionId,values:v.valuesByMode}))};'''
result = subprocess.run(['figma-cli', 'eval', code], env=environment, capture_output=True, text=True, check=True, timeout=90)
variables = json.loads(result.stdout)
if variables.get('file') != 'zHX90tHJtN7q1PqavIO68s':
    raise ValueError('The active Figma file is not the approved itzephir source')
(args.output / 'figma-workspace.json').write_text(json.dumps(variables, ensure_ascii=False, indent=2) + '\n')
rows = json.loads(Path(__file__).with_name('screens.json').read_text())
for i, row in enumerate(rows, 1):
    target = references / row['file']
    if not (args.resume and target.exists() and target.stat().st_size > 100):
        subprocess.run(['figma-cli', 'export', 'node', row['id'], '--format', 'svg', '--output', str(target)],
                       env=environment, check=True, capture_output=True, timeout=90)
    print(f"{i}/{len(rows)} {row['title']}", flush=True)
