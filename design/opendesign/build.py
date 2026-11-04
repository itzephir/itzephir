#!/usr/bin/env python3
"""Build allowlisted, self-contained OpenDesign documents from the current site."""
import argparse
import hashlib
from html import escape
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import shutil
import subprocess
from urllib.parse import parse_qs, urlparse
from urllib.request import urlopen
from views import VIEWS, filename as dev_filename, render as render_dev_view

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
STATIC = REPO / "website/src/main/resources/static"
ROUTES = {"/": "index", "/projects": "projects", "/about": "about"}


def page_name(route, theme):
    stem = ROUTES[route]
    return f"{stem}.html" if theme == "dark" else f"{stem}-light.html"


class PreviewDocument(HTMLParser):
    """Retain rendered page geometry while routing links inside the design copy."""
    def __init__(self, theme):
        super().__init__(convert_charrefs=False)
        self.theme = theme
        self.parts = []

    def handle_starttag(self, tag, attrs):
        converted = []
        for name, value in attrs:
            if name.startswith("hx-"):
                continue
            if value is not None and name in {"href", "src", "srcset", "action"}:
                parsed = urlparse(value)
                if not parsed.scheme and not parsed.netloc:
                    if parsed.path.startswith("/assets/"):
                        value = "assets/" + parsed.path.removeprefix("/assets/")
                    elif name == "href" and parsed.path in ROUTES:
                        theme = parse_qs(parsed.query).get("theme", [self.theme])[0]
                        value = page_name(parsed.path, theme)
                    elif name == "href" and parsed.path.startswith("/dev"):
                        value = dev_filename("dev", self.theme)
                    elif name == "action":
                        # Tab forms are handled locally; no backend actions survive.
                        value = "#"
            converted.append(name if value is None else f'{name}="{escape(value, quote=True)}"')
        self.parts.append("<" + tag + (" " + " ".join(converted) if converted else "") + ">")

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)

    def handle_endtag(self, tag):
        if tag == "body":
            self.parts.append('''<script>
document.addEventListener('submit', event => {
  event.preventDefault();
  if (!event.target.matches('.tab-close-form')) return;
  const tab = event.target.closest('.editor-tab');
  const active = tab.querySelector('[aria-current="page"]');
  tab.remove();
  if (active) location.href = document.querySelector('.editor-tab-link')?.getAttribute('href') || 'index.html';
});
</script>''')
        self.parts.append(f"</{tag}>")

    def handle_data(self, value): self.parts.append(value)
    def handle_entityref(self, value): self.parts.append(f"&{value};")
    def handle_charref(self, value): self.parts.append(f"&#{value};")
    def handle_decl(self, value): self.parts.append(f"<!{value}>")
    def handle_comment(self, value): self.parts.append(f"<!--{value}-->")


def write(root, name, value):
    target = root / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(value, encoding="utf-8")


def json_text(value):
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def copy_assets(root):
    out = root / "assets"
    out.mkdir()
    for name in ("styles.css", "site-shell.css", "dev.css", "staging-badge.css", "portrait.jpg"):
        shutil.copyfile(STATIC / name, out / name)
    shutil.copytree(STATIC / "design", out / "design")
    (out / "fonts").mkdir()
    for name in ("ibm-plex-sans.ttf", "ibm-plex-mono.ttf", "ibm-plex-mono-medium.ttf"):
        shutil.copyfile(STATIC / "fonts" / name, out / "fonts" / name)
    for name in ("ibm-plex-sans", "ibm-plex-mono", "quicksand", "lucide"):
        shutil.copytree(REPO / "third_party" / name, root / "licenses" / name)


def tokens_from_figma(source):
    variables = {v["id"]: v for v in source["variables"]}
    collections = {c["id"]: c["name"] for c in source["collections"]}

    def resolve(variable, seen=()):
        if variable["id"] in seen:
            raise ValueError("Cyclic Figma variable alias")
        value = next(iter(variable["values"].values()))
        if isinstance(value, dict) and value.get("type") == "VARIABLE_ALIAS":
            return resolve(variables[value["id"]], (*seen, variable["id"]))
        if variable["type"] == "COLOR":
            return "#" + "".join(f"{round(value[c] * 255):02x}" for c in "rgb")
        return value

    themes, layout = {}, {}
    for variable in variables.values():
        collection = collections[variable["collection"]]
        if collection in ("Workspace Dark", "Workspace Light"):
            theme = collection.split()[-1].lower()
            themes.setdefault(theme, {})[variable["name"]] = {
                "value": resolve(variable), "figmaVariableId": variable["id"],
            }
        elif collection == "Workspace Layout":
            layout[variable["name"].replace("/", "-")] = {
                "value": resolve(variable), "figmaVariableId": variable["id"],
            }
    assert len(themes["dark"]) == len(themes["light"]) == 12
    # The site's nine shared semantic colors must agree with the Figma export.
    css = (STATIC / "styles.css").read_text()
    for theme in ("dark", "light"):
        selector = '.portfolio {' if theme == 'dark' else '.portfolio[data-theme="light"] {'
        block = css.split(selector, 1)[1].split("}", 1)[0]
        for name, value in re.findall(r"--([a-z-]+):\s*(#[a-fA-F0-9]+)", block):
            if len(value) == 4:
                value = "#" + "".join(c * 2 for c in value[1:])
            assert themes[theme][name]["value"] == value.lower(), f"Figma/site mismatch: {theme}/{name}"
    return themes, layout


def token_files(themes, layout):
    css = ["/* Approved Figma Workspace variables; values resolve original aliases. */"]
    for theme in ("dark", "light"):
        selector = ':root, [data-theme="dark"]' if theme == 'dark' else '[data-theme="light"]'
        css.append(selector + " {\n" + "\n".join(f"  --{k}: {v['value']};" for k, v in themes[theme].items()) + "\n}")
    css.append(":root {\n" + "\n".join(f"  --{k}: {v['value']}px;" for k, v in layout.items()) + "\n}")
    dtcg = {}
    for theme, colors in themes.items():
        dtcg[theme] = {}
        for name, data in colors.items():
            value = data["value"]
            dtcg[theme][name] = {"$type": "color", "$value": {
                "colorSpace": "srgb", "components": [int(value[i:i+2], 16) / 255 for i in (1, 3, 5)],
                "hex": value}, "$extensions": {"com.itzephir.figmaVariableId": data["figmaVariableId"]}}
    dtcg["layout"] = {k: {"$type": "dimension", "$value": {"value": v["value"], "unit": "px"},
                           "$extensions": {"com.itzephir.figmaVariableId": v["figmaVariableId"]}}
                      for k, v in layout.items()}
    return "\n\n".join(css) + "\n", dtcg


CATALOG_STYLE = '''
html,body{margin:0;background:var(--bg);color:var(--text);font:400 15px/22px "IBM Plex Sans",sans-serif}
body{overflow-wrap:break-word}*{box-sizing:border-box}a{color:var(--accent)}
main{max-width:1056px;margin:auto;padding:48px 32px 80px}header{display:flex;justify-content:space-between;gap:24px;align-items:center;margin-bottom:48px}
h1{font-size:40px;line-height:48px;font-weight:500;letter-spacing:-.8px;margin:0 0 16px}h2{font-size:26px;line-height:34px;font-weight:500;margin:0 0 24px}
h3{font-weight:500;margin:0 0 12px}p{margin:0 0 16px}section{margin:0 0 56px}.lead{font-size:18px;line-height:28px;max-width:680px;color:var(--muted)}
.eyebrow{font:12px/20px "IBM Plex Mono",monospace;color:var(--muted);margin-bottom:12px}.grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}
.tile{border:1px solid var(--line);border-radius:12px;padding:24px;background:var(--panel);min-width:0}.tile p{color:var(--muted)}
.swatch{height:72px;border-radius:8px;border:1px solid var(--line);margin-bottom:16px}.row{display:flex;gap:16px;flex-wrap:wrap;align-items:center}
code{font:12px/20px "IBM Plex Mono",monospace;color:var(--muted)}.brand{width:134px;height:48px;object-fit:contain}.brand-light{display:none}
[data-theme="light"] .brand-dark{display:none}[data-theme="light"] .brand-light{display:block}
button{font:inherit;cursor:pointer;min-height:44px;border-radius:8px;padding:10px 16px;border:1px solid var(--line);background:var(--panel);color:var(--text)}
button:focus-visible,a:focus-visible{outline:2px solid var(--accent);outline-offset:4px}.wide{grid-column:1/-1}.specimen{font-size:40px;line-height:48px;margin-bottom:16px}
.reference img{width:100%;height:220px;object-fit:contain;object-position:top;background:var(--side);border:1px solid var(--line);border-radius:8px}
.reference a{display:block;margin:12px 0}.scale{display:flex;align-items:center;gap:16px;margin:12px 0}.scale b{display:block;height:12px;background:var(--accent);border-radius:2px}
@media(max-width:719px){main{padding:32px 24px 48px}header{align-items:flex-start;gap:12px;margin-bottom:32px}h1{font-size:32px;line-height:40px}.grid{grid-template-columns:1fr}.specimen{font-size:30px;line-height:38px}section{margin-bottom:40px}}
'''


def html_document(title, body):
    return f'''<!doctype html><html lang="ru" data-theme="dark"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>{escape(title)}</title>
<link rel="stylesheet" href="assets/styles.css"><link rel="stylesheet" href="assets/dev.css">
<link rel="stylesheet" href="tokens.css"><style>{CATALOG_STYLE}</style></head><body><main>
<header><div><img class="brand brand-dark" src="assets/design/dark/wordmark.svg" alt="itzephir" width="134" height="48">
<img class="brand brand-light" src="assets/design/light/wordmark.svg" alt="itzephir" width="134" height="48"></div>
<button type="button" id="theme">Светлая тема</button></header>{body}</main>
<script>document.querySelector('#theme').addEventListener('click',()=>{{const r=document.documentElement;const light=r.dataset.theme!=='light';r.dataset.theme=light?'light':'dark';document.querySelector('#theme').textContent=light?'Тёмная тема':'Светлая тема';}});</script></body></html>'''


def components_html(themes, layout):
    swatches = "".join(f'''<article class="tile"><div class="swatch" style="background:var(--{name})"></div><h3>{name}</h3>
<code>Dark {row['value']}<br>Light {themes['light'][name]['value']}</code></article>''' for name, row in themes["dark"].items())
    spaces = "".join(f'<div class="scale"><code>{name} · {row["value"]}px</code><b style="width:{row["value"]}px"></b></div>'
                     for name, row in layout.items() if name.startswith("space-") and row["value"] in (4,8,12,16,24,32,48,64))
    return html_document("itzephir · Дизайн-система", f'''
<p class="eyebrow">DESIGN SYSTEM / WORKSPACE</p><h1>Основа itzephir</h1>
<p class="lead">Сдержанные поверхности, ясная структура редактора и типографика IBM Plex. Компоненты и токены из утверждённого дизайна.</p>
<section><h2>Цвета</h2><div class="grid">{swatches}</div></section>
<section><h2>Типографика</h2><article class="tile"><p class="eyebrow">IBM PLEX SANS / 400, 500</p>
<p class="specimen">Пространство для своих проектов</p><p style="font-size:18px;line-height:28px">Читаемая кириллица, спокойная иерархия и понятные действия.</p>
<p>Основной интерфейс · 15/22 px</p><p class="eyebrow">IBM PLEX MONO / 400, 500</p><code>~/itzephir / projects / about.md<br>const theme = "dark";</code></article></section>
<section><h2>Действия и формы</h2><div class="grid dev-ui">
<article class="tile"><h3>Кнопки</h3><div class="row"><button type="button">Сохранить</button><button class="secondary" type="button">Отмена</button></div></article>
<article class="tile"><label class="field">Название проекта<input value="itzephir"></label></article>
<article class="tile"><label class="field">Готовность<select><option>Проектирование</option><option>В разработке</option><option>Готов</option></select></label></article>
<article class="tile wide"><label class="field">Описание<textarea>Инструменты для себя, мобильные приложения и backend.</textarea></label></article>
<article class="tile"><h3>Статусы</h3><div class="row"><span class="badge">Проектирование</span><span class="badge pending">На рассмотрении</span><span class="badge rejected">Отклонён</span></div></article>
<article class="tile wide"><div class="notice"><h3>Пока нет проектов</h3><p>Создайте первый проект, чтобы он появился в вашем пространстве.</p></div></article></div></section>
<section><h2>Карточки и навигация</h2><div class="grid">
<article class="project"><div class="project-top"><h2>itzephir</h2></div><p class="muted">Личное пространство для проектов.</p><p class="project-stack mono muted">Kotlin · Ktor · HTML</p></article>
<a class="shortcut" href="#brand">projects/</a><article class="tile"><h3>Клавиатура и касания</h3><p>44 px цель, видимый фокус и одинаковая иерархия в обеих темах.</p><button type="button" class="icon-button" aria-label="Поиск"><img class="brand-dark" src="assets/design/dark/search.svg" width="18" height="18" alt=""><img class="brand-light" src="assets/design/light/search.svg" width="18" height="18" alt=""></button></article></div></section>
<section><h2>Отступы и скругления</h2><article class="tile">{spaces}<div class="row"><code>Контролы 6–8 px</code><code>Карточки 10–12 px</code><code>Портрет 16 px</code></div></article></section>
<section id="brand"><h2>Брендинг</h2><div class="grid"><article class="tile"><img src="assets/design/dark/wordmark.svg" width="179" height="64" alt="Wordmark на тёмном фоне" style="max-width:100%;height:auto;background:#1b1d20;padding:16px;box-sizing:content-box"></article>
<article class="tile"><img src="assets/design/light/wordmark.svg" width="179" height="64" alt="Wordmark на светлом фоне" style="max-width:100%;height:auto;background:#fafbfc;padding:16px;box-sizing:content-box"></article>
<article class="tile"><p>Используйте полный SVG wordmark и короткий связный знак itz. Буквы уже переведены в кривые.</p></article></div></section>
<section><h2>Правила применения</h2><p class="lead">Базовые компоненты подходят личным продуктам. Webring, вкладки посещённых страниц и геометрия шапки относятся к портфолио. Подробные спецификации находятся в DESIGN.md и USAGE.md.</p></section>''')


def build(args):
    out = args.output.resolve()
    if out.exists() and any(out.iterdir()):
        raise ValueError("Output must be empty; preserve the previous package separately")
    project, system = out / "project", out / "design-system"
    project.mkdir(parents=True)
    system.mkdir()
    source = json.loads(args.variables.read_text())
    assert source["file"] == "zHX90tHJtN7q1PqavIO68s", "Unexpected Figma source"
    themes, layout = tokens_from_figma(source)
    css, dtcg = token_files(themes, layout)
    colors = '\n\n## Color Palette\n\n| Role | Name | Hex | Usage |\n| --- | --- | --- | --- |\n'
    colors += '\n'.join(f"| {name} | {name} | {row['value']} | Dark; light {themes['light'][name]['value']} |" for name, row in themes['dark'].items()) + '\n'
    design_body = (HERE / 'DESIGN.md').read_text() + colors
    for root in (project, system):
        copy_assets(root)
        write(root, "tokens.css", css)
        write(root, "design-tokens.json", json_text(dtcg))
        write(root, "sources/figma-workspace.json", json_text(source))
        shutil.copyfile(REPO / "website/DESIGN.md", root / "sources/portfolio-design.md")
        write(root, "DESIGN.md", design_body)
        write(root, "components.html", components_html(themes, layout))
        write(root, "brand.json", json_text({"logo": {"primary": "assets/design/light/wordmark.svg",
              "alternates": ["assets/design/dark/wordmark.svg"],
              "notes": "Original outlined wordmark. Use light/ assets on light surfaces and dark/ assets on dark surfaces."}}))
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip()
    context_files = ["website/src/main/resources/static/styles.css",
                     "website/src/main/resources/static/site-shell.css",
                     "website/src/main/resources/static/dev.css"]
    for name in context_files:
        target = system / "context/github/itzephir/files" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(REPO / name, target)
    write(system, "context/github/itzephir.md", f"# itzephir stylesheet evidence\n\nSource: https://github.com/itzephir/itzephir\n\nBase revision: {revision}\n\nOnly the three stylesheets already bundled under assets/ are duplicated under itzephir/files. They retain the existing visual foundation and component selectors. No additional application source, GitHub connector or live administrative data is included.\n")
    write(system, "context/source-context.md", "# Source context\n\nFigma: https://www.figma.com/design/zHX90tHJtN7q1PqavIO68s/\n\nApproved specifications: sources/portfolio-design.md\n\nExact Workspace variables and aliases: sources/figma-workspace.json\n\nBounded repository evidence: context/github/itzephir.md and context/github/itzephir/files/.\n")
    usage = "# Using itzephir\n\nRead DESIGN.md, reuse tokens.css and the original CSS/assets.\n\n"
    usage += "Use components.html for visual review. Preserve both themes and mobile support. "
    usage += "The portfolio shell is specific to itzephir; other personal products reuse the visual foundation, not its navigation.\n"
    write(system, "USAGE.md", usage)
    manifest = {"schemaVersion": "od-design-system-project/v1", "id": "itzephir", "name": "itzephir",
                "category": "Personal workspace", "description": "Editor and product-app foundations; IBM Plex, dark/light themes, muted blue.",
                "files": {"design": "DESIGN.md", "tokens": "tokens.css", "designTokens": "design-tokens.json", "components": "components.html"},
                "assetsDir": "assets", "usage": "USAGE.md", "importMode": "verbatim",
                "fonts": [{"family": "IBM Plex Sans", "file": "assets/fonts/ibm-plex-sans.ttf", "weight": "100 700"},
                          {"family": "IBM Plex Mono", "file": "assets/fonts/ibm-plex-mono.ttf", "weight": 400},
                          {"family": "IBM Plex Mono", "file": "assets/fonts/ibm-plex-mono-medium.ttf", "weight": 500}],
                "preview": {"dir": ".", "pages": [{"path": "components.html", "role": "showcase", "title": "Foundations and components"}]}}
    write(system, "manifest.json", json_text(manifest))
    for route in ROUTES:
        for theme in ("dark", "light"):
            with urlopen(args.base_url.rstrip("/") + route + "?theme=" + theme, timeout=30) as response:
                raw = response.read().decode()
            # HTMX is server enhancement; the design copy uses ordinary links.
            raw = re.sub(r'<script\b[^>]*src="/assets/htmx-[^"]+"[^>]*>\s*</script>', "", raw)
            parser = PreviewDocument(theme)
            parser.feed(raw)
            write(project, page_name(route, theme), "".join(parser.parts))
    for view in VIEWS:
        for theme in ("dark", "light"):
            shell = (project / page_name("/about", theme)).read_text()
            write(project, dev_filename(view, theme), render_dev_view(view, theme, shell))
    rows = json.loads((HERE / "screens.json").read_text())
    (project / "references").mkdir()
    gallery = []
    for row in rows:
        source_file = args.references / row["file"]
        assert source_file.stat().st_size > 100, "Missing/empty Figma reference: " + row["file"]
        shutil.copyfile(source_file, project / "references" / row["file"])
        gallery.append(f'''<article class="tile reference" id="{escape(row['title'].split(' / ')[0])}-{escape(row['id'].replace(':','-'))}">
<img src="references/{escape(row['file'])}" alt="{escape(row['title'])}" loading="lazy">
<a href="references/{escape(row['file'])}">{escape(row['title'])}</a>
<a href="https://www.figma.com/design/zHX90tHJtN7q1PqavIO68s/?node-id={row['id'].replace(':','-')}" target="_blank" rel="noopener noreferrer">Оригинал в Figma</a></article>''')
    write(project, "references.html", html_document("itzephir · Макеты Figma", '<p class="eyebrow">APPROVED FIGMA REFERENCES</p><h1>Макеты itzephir</h1><p class="lead">Утверждённые экраны, состояния и листы компонентов. Это SVG-референсы исходных кадров. Основные страницы и библиотека компонентов доступны как HTML/CSS.</p><p><a href="catalog.html">Обзор проекта</a></p><div class="grid">' + "".join(gallery) + '</div>'))
    dev_cards = "".join(f'<article class="tile"><h2>{escape(title)}</h2><p><a href="{dev_filename(view,"dark")}">Тёмная тема</a></p><a href="{dev_filename(view,"light")}">Светлая тема</a></article>' for view, title in VIEWS.items())
    write(project, "catalog.html", html_document("itzephir · Обзор проекта", '''<p class="eyebrow">ITZEPHIR / DESIGN WORKSPACE</p><h1>Проект itzephir</h1><p class="lead">Страницы портфолио, панель и состояния — редактируемые HTML/CSS. Утверждённые макеты Figma сохранены рядом для сверки.</p><div class="grid">
<article class="tile"><h2>Главная</h2><p><a href="index.html">Тёмная тема</a></p><a href="index-light.html">Светлая тема</a></article>
<article class="tile"><h2>Проекты</h2><p><a href="projects.html">Тёмная тема</a></p><a href="projects-light.html">Светлая тема</a></article>
<article class="tile"><h2>Обо мне</h2><p><a href="about.html">Тёмная тема</a></p><a href="about-light.html">Светлая тема</a></article>
<article class="tile"><h2>Дизайн-система</h2><a href="components.html">Цвета, типографика, компоненты</a></article>
<article class="tile"><h2>Макеты Figma</h2><a href="references.html">Утверждённые экраны и состояния</a></article></div><section><h2>Витрина и панель</h2><div class="grid">''' + dev_cards + '</div></section>'))
    write(project, "README.md", "# itzephir\n\nOpen catalog.html for the project overview, index.html for the welcome screen, components.html for the design system and references.html for the approved Figma frames.\n\nHTML/CSS files are editable. Dark and light variants share the original styles and bundled assets. Backend behavior remains in the Ktor application.\n")
    for root in (project, system):
        hashes = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
                  for p in sorted(root.rglob("*")) if p.is_file()}
        write(root, "sources/checksums.json", json_text(hashes))
    print(json.dumps({"html_pages": 6 + 2 * len(VIEWS), "figma_references": len(rows), "color_tokens": 24,
                      "layout_tokens": len(layout), "project_files": sum(p.is_file() for p in project.rglob('*')),
                      "design_system_files": sum(p.is_file() for p in system.rglob('*'))}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--variables", type=Path, required=True)
    parser.add_argument("--references", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    build(parser.parse_args())
