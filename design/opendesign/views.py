"""Editable Dev UI specimens composed from the existing LocatorPanel selectors."""
from html import escape
import re

VIEWS = {
    "dev": "Проекты в разработке", "dev-panel": "Все проекты", "dev-owner": "Мои проекты",
    "dev-settings": "Настройки проекта", "dev-admin-settings": "Владельцы проекта",
    "dev-create": "Новый проект", "dev-review": "Заявки", "dev-users": "Пользователи",
    "dev-account": "Моя учётная запись", "dev-login": "Вход в панель",
    "dev-token": "Проект создан", "dev-empty": "Пустое состояние",
    "dev-stale": "Данные обновились", "dev-catalog": "Каталог: проектирование",
}


def filename(view, theme):
    return view + ("-light" if theme == "light" else "") + ".html"


def icon(theme, name):
    return f'<span class="icon icon-18" aria-hidden="true"><img src="assets/design/{theme}/{name}.svg" width="18" height="18" alt=""></span>'


def link(view, text, theme, css="button secondary", current=False):
    return f'<a href="{filename(view,theme)}" class="{css}"' + (' aria-current="page"' if current else '') + f'>{escape(text)}</a>'


def heading(title, description):
    return f'<div class="page-heading"><h1>{escape(title)}</h1><p class="muted">{escape(description)}</p></div>'


def input_field(name, title, value="", kind="text", help_text="", required=True):
    control = f'<input type="{kind}" id="{name}" name="{name}" value="{escape(value,quote=True)}" autocomplete="off"' + (' required' if required else '') + '>'
    return f'<div class="field"><label for="{name}">{escape(title)}</label>{control}' + (f'<p class="help">{escape(help_text)}</p>' if help_text else '') + '</div>'


def form(content, destination=None):
    dest = f' data-preview-destination="{destination}"' if destination else ''
    return f'<form action="#" method="post"{dest}>{content}</form>'


def project_fields(empty=False):
    return input_field("project", "Имя проекта", "" if empty else "itzephir", help_text="Например, minor-website. Строчные латинские буквы, цифры, дефис и подчёркивание.") + '''
<div class="field"><label for="description">Описание</label><textarea id="description" name="description" rows="3" maxlength="500">''' + ("" if empty else "Личное пространство для проектов и инструментов.") + '''</textarea><p class="help">До 500 символов. Описание появится на витрине.</p></div>
<div class="field"><label for="readiness">Готовность</label><select id="readiness" name="readiness"><option value="development">В разработке</option><option value="planning">Проектирование</option></select></div>''' + input_field("slug", "Адрес проекта", "" if empty else "itzephir", help_text="Будет использован в адресах проекта.") + '''
<div class="field"><label for="routing">Размещение</label><select id="routing" name="routing"><option>Домен проекта</option><option>Docker</option><option>Прокси</option><option>Перенаправление</option></select></div>''' + input_field("target", "Адрес приложения или alias:port", "", help_text="Заполняется только для выбранного способа размещения.", required=False)


PROJECTS = [
    ("itzephir", "Личное пространство для проектов и инструментов.", "В разработке", "Одобрен", "approved"),
    ("itzflow", "Инструмент настройки рабочих процессов.", "В разработке", "На проверке", "pending"),
    ("itzmoney", "Личный и семейный бюджет.", "Проектирование", "Одобрен", "approved"),
]


def project_rows(theme, pending=False, owner=False):
    rows = []
    for name, description, readiness, status, css in PROJECTS:
        if pending and css != "pending":
            continue
        actions = link("dev-settings" if owner else "dev-admin-settings", "Редактировать", theme)
        if pending:
            actions += form('<button type="submit">Одобрить</button><button type="submit" class="secondary">Отклонить</button>')
        rows.append(f'<article class="project-row"><div><h2>{link("dev-settings",name,theme,"")}</h2><p class="project-description">{description}</p><div class="project-row-meta"><p class="muted mono">/{name}</p><span class="help">{readiness}</span><p class="help">Домен проекта</p><span class="badge {css}">{status}</span></div></div><div class="row-actions">{actions}</div></article>')
    return '<div class="project-list">' + "".join(rows) + '</div>'


def gallery(theme):
    cards = []
    for name, description, readiness, _, _ in PROJECTS:
        action = link("dev-settings", "Открыть проект", theme, "project-link") if readiness != "Проектирование" else ""
        cards.append(f'<article class="project-card"><h2>{name}</h2><p class="project-description">{description}</p><p class="muted mono">/dev/project/{name}/</p><div class="project-card-actions">{action}<span class="help">{readiness}</span></div></article>')
    return '<div class="dev-gallery-meta"><p class="eyebrow mono">dev / Открытая мастерская</p>' + link("dev-panel", "Панель проектов", theme) + '</div>' + heading(VIEWS["dev"], "Сервисы, инструменты и новые идеи, над которыми я работаю.") + '<p class="eyebrow mono">ПРОЕКТЫ / 3</p><div class="project-grid">' + "".join(cards) + '</div>'


def content(view, theme):
    if view in ("dev", "dev-catalog"):
        return gallery(theme)
    if view in ("dev-panel", "dev-owner", "dev-review", "dev-empty"):
        pending, owner = view == "dev-review", view == "dev-owner"
        result = heading(VIEWS[view] if view != "dev-empty" else "Все проекты", "Проверьте имя и адрес перед публикацией." if pending else "Настройки и статус ваших проектов." if owner else "Проекты, заявки и доступы к ним.")
        if not owner:
            result += link("dev-create", "Новый проект", theme, "button")
        if view == "dev-panel":
            result += '<div class="notice"><h2>Заявки ждут проверки</h2><p>Проверьте данные перед публикацией на витрине.</p>' + link("dev-review", "Проверить заявки", theme) + '</div>'
        result += '<nav class="filters">' + "".join(link(dest, title, theme, "", current=dest == view) for dest, title in [("dev-panel", "Все"), ("dev-review", "На проверке"), ("dev-owner", "Одобрен"), ("dev-empty", "Отклонён")]) + '</nav>'
        return result + ('<div class="empty"><p>Проектов с таким статусом пока нет.</p></div>' if view == "dev-empty" else project_rows(theme, pending, owner))
    if view == "dev-create":
        return heading("Новый проект", "Проект администратора будет одобрен сразу.") + '<section class="card">' + form(project_fields(True) + '<button type="submit">Создать проект</button>', filename("dev-token", theme)) + '</section>'
    if view in ("dev-settings", "dev-admin-settings", "dev-stale"):
        result = link("dev-panel", "К проектам", theme, "back-link") + heading("Настройки проекта", "itzephir") + '<span class="badge approved">Одобрен</span>'
        if view == "dev-stale":
            result += '<p class="notice" role="alert">Данные проекта изменились. Обновите страницу и повторите попытку. ' + link("dev-settings", "Обновить данные", theme, "") + '</p>'
        result += '<section class="card">' + form(project_fields() + ('<p class="help">После изменения данных проекта он отправится на повторное одобрение.</p>' if view != "dev-admin-settings" else '') + '<button type="submit">Сохранить изменения</button>') + '</section>'
        if view == "dev-admin-settings":
            result += '<section class="card"><h2>Владельцы проекта</h2>' + form('<label class="owner-option"><input type="checkbox" checked>Владелец проекта</label><label class="owner-option"><input type="checkbox">Второй владелец</label><button type="submit">Сохранить владельцев</button>') + '</section>'
        return result + '<section class="card details"><h2>Адреса проекта</h2><p class="eyebrow mono">PRODUCTION</p><p class="mono">itzephir.com/dev/project/itzephir/</p><p class="eyebrow mono">STAGE</p><p class="mono">dev.itzephir.com/dev/project/itzephir/</p></section>'
    if view == "dev-users":
        return heading("Пользователи", "Управление доступом к проектам.") + '<div class="card user-list"><article class="user-row"><h2>Администратор</h2><span class="help">Администратор</span></article><article class="user-row"><h2>Владелец проекта</h2><span class="help">Владелец проектов</span></article></div><section class="card create-project"><h2>Добавить владельца</h2>' + form(input_field("username", "Логин") + input_field("new-password", "Пароль", kind="password", help_text="Не менее 12 символов. Проекты можно назначить в их настройках.") + '<button type="submit">Добавить пользователя</button>') + '</section>'
    if view == "dev-account":
        return heading("Моя учётная запись", "Владелец проекта") + '<section class="card"><h2>Изменить пароль</h2>' + form(input_field("current-password", "Текущий пароль", kind="password") + input_field("new-password", "Новый пароль", kind="password", help_text="Не менее 12 символов. Старые сессии будут завершены.") + '<button type="submit">Сохранить пароль</button>') + '</section><section class="card"><h2>GitHub</h2><p class="muted">Подключите GitHub, чтобы входить без пароля.</p>' + form('<button type="submit" class="secondary">Подключить GitHub</button>') + '</section>'
    if view == "dev-login":
        return heading("Вход в панель", "Войдите, чтобы управлять своими проектами.") + '<section class="card login-card">' + form(input_field("username", "Логин") + input_field("password", "Пароль", kind="password") + '<button type="submit">Войти</button>', filename("dev-owner", theme)) + form('<button type="submit" class="secondary">Войти через GitHub</button>') + '</section>'
    if view == "dev-token":
        return '<h1>Проект создан</h1><p>itzephir</p><div class="card"><h2>Сохраните API-токен</h2><p class="muted">Он показывается только сейчас. Используйте его для доступа к проекту.</p><pre><code>Пример: здесь появится токен созданного проекта</code></pre></div>' + link("dev-settings", "К настройкам проекта", theme, "button")
    raise ValueError(view)


def render(view, theme, shell):
    opposite = "dark" if theme == "light" else "light"
    public = view in ("dev", "dev-catalog", "dev-login")
    controls = '<div class="dev-controls">' + link("dev" if view != "dev" else "dev-panel", "Назад", theme, "back-link") + f'<a href="{filename(view,opposite)}" class="theme-toggle icon-button mobile-theme" aria-label="Переключить тему">{icon(theme,"theme")}</a></div>'
    body = content(view, theme)
    if public:
        body = f'<div class="dev-public dev-ui{ " dev-login" if view == "dev-login" else ""}">{controls}{body}</div>'
    else:
        owner = view in ("dev-owner", "dev-settings", "dev-account")
        items = [("dev-owner" if owner else "dev-panel", "Мои проекты" if owner else "Все проекты", "folder")]
        if not owner:
            items += [("dev-review", "Заявки", "folder-open"), ("dev-users", "Пользователи", "users")]
        items += [("dev-account", "Аккаунт", "settings")]
        nav = '<nav class="dev-sidebar" aria-label="Управление проектами"><p class="eyebrow mono">УПРАВЛЕНИЕ</p>'
        for dest, title, name in items:
            nav += f'<a href="{filename(dest,theme)}" class="dev-menu-link"' + (' aria-current="page"' if dest == view else '') + f'>{icon(theme,name)}{title}</a>'
        nav += '<p class="help dev-role">' + ('Владелец проектов' if owner else 'Администратор') + '</p>'
        nav += form('<button type="submit" class="secondary">Выйти</button>', filename("dev-login", theme)) + link("dev", "Открыть витрину", theme, "dev-menu-link") + '</nav>'
        body = f'<div class="dev-panel">{controls}{nav}<div class="dev-workspace dev-ui"><p class="eyebrow mono">dev / panel</p><div class="dev-document">{body}</div></div></div>'
    # The shared header/footer come directly from the current Ktor document.
    out = re.sub(r'<main\b[^>]*>.*?</main>', '<main class="page-canvas" id="content">' + body + '</main>', shell, count=1, flags=re.S)
    out = out.replace('>Обо мне<', '>' + VIEWS[view] + '<').replace('about.md', 'dev/')
    route = '/dev' if public else '/dev/panel'
    if view in ('dev-users', 'dev-account'):
        route += '/' + view.removeprefix('dev-')
    elif view == 'dev-create':
        route += '/new'
    out = out.replace('>/about</span>', '>' + route + '</span>')
    out = out.replace(f'assets/design/{theme}/profile.svg', f'assets/design/{theme}/dev.svg')
    out = out.replace('href="about.html"', f'href="{filename(view,"dark")}"').replace('href="about-light.html"', f'href="{filename(view,"light")}"')
    out = re.sub(r'<title>.*?</title>', '<title>' + VIEWS[view] + ' · itzephir</title>', out, count=1)
    out = out.replace('</body>', '''<script>document.addEventListener('submit',event=>{
event.preventDefault();const target=event.target.dataset.previewDestination;if(target){location.href=target;return;}
let notice=event.target.querySelector('[role="status"]');if(!notice){notice=document.createElement('p');notice.role='status';notice.className='notice';event.target.append(notice);}
notice.textContent='Это макет: данные не отправляются на сервер.';});</script></body>''')
    return out
