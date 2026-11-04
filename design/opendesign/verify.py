#!/usr/bin/env python3
"""Validate the built design package before copying it into OpenDesign."""
import argparse
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import re
from urllib.parse import unquote, urlparse

from views import VIEWS, filename


def verify(root):
    root = root.resolve()
    checked = []

    class Document(HTMLParser):
        def __init__(self, file):
            super().__init__()
            self.file = file

        def handle_starttag(self, tag, attrs):
            for key, value in attrs:
                assert not key.startswith('hx-'), (self.file, key)
                if not value or key not in ('href', 'src', 'srcset', 'action', 'data-preview-destination'):
                    continue
                parsed = urlparse(value)
                if parsed.scheme or parsed.netloc or not parsed.path:
                    continue
                target = (self.file.parent / unquote(parsed.path)).resolve()
                assert not value.startswith('/') and target.is_relative_to(root), (self.file, value)
                assert target.is_file(), (self.file, value)
                checked.append(target)
            if tag == 'form':
                assert dict(attrs).get('action') == '#', self.file

    for file in root.rglob('*.html'):
        Document(file).feed(file.read_text())
    for file in root.rglob('*.css'):
        # Repository evidence is an inert source snapshot, not a preview stylesheet.
        if 'context' in file.relative_to(root).parts:
            continue
        for value in re.findall(r'''url\(['"]?([^\)'"\s]+)''', file.read_text()):
            if value.startswith(('data:', 'http:', 'https:', '#')):
                continue
            target = (file.parent / value).resolve()
            assert target.is_relative_to(root) and target.is_file(), (file, value)
            checked.append(target)
    for view in VIEWS:
        for theme in ('dark', 'light'):
            text = (root / 'project' / filename(view, theme)).read_text()
            assert f'data-theme="{theme}"' in text
            assert filename(view, 'light' if theme == 'dark' else 'dark') in text
            assert 'about.md' not in text
    for package in ('project', 'design-system'):
        directory = root / package
        expected = json.loads((directory / 'sources/checksums.json').read_text())
        for name, value in expected.items():
            file = directory / name
            assert not file.is_symlink() and hashlib.sha256(file.read_bytes()).hexdigest() == value, name
    result = {'html_files': len(list(root.rglob('*.html'))), 'validated_links_and_resources': len(checked),
              'figma_references': len(list((root / 'project/references').glob('*.svg'))),
              'preview_forms_are_local': True}
    print(json.dumps(result))
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package', type=Path)
    verify(parser.parse_args().package)
