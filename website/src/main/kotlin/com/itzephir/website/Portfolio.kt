package com.itzephir.website

import kotlinx.html.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

enum class Theme(val value: String) {
    Dark("dark"), Light("light");
    val opposite: Theme get() = if (this == Dark) Light else Dark
    companion object { fun from(value: String?): Theme? = entries.find { it.value == value } }
}

enum class PortfolioPage(val path: String, val title: String) {
    Welcome("/", "Дмитрий Дворянников — Kotlin Developer"),
    Projects("/projects", "Мои проекты — itzephir"),
    Profile("/about", "Обо мне — itzephir");
    val tabKey: String get() = name.lowercase()
    val tabLabel: String get() = when (this) {
        Welcome -> "welcome.md"
        Projects -> "projects"
        Profile -> "about.md"
    }
    val tabIcon: String get() = when (this) {
        Welcome -> "file"
        Projects -> "folder"
        Profile -> "profile"
    }
    companion object { fun fromTabKey(value: String): PortfolioPage? = entries.find { it.tabKey == value } }
}

private const val telegram = "https://t.me/ItzEphir"
private const val github = "https://github.com/itzephir"
private const val email = "mailto:d.y.dvoryannikov@mail.ru"
private const val ring = "https://webring.otomir23.me/"
private data class Project(val name: String, val description: String, val stack: String, val url: String)
private val projects = listOf(
    Project("Photorus", "Микросервисы для Telegram WebApp: OCR, AI-разбор правил, SSE и MongoDB.", "Kotlin · Ktor · Docker", "$github/photorus-tgapp-backend"),
    Project("WhereRubles", "Android-приложение для учёта личного баланса. Проект ШМР Яндекса, 2025.", "Kotlin · Android · Yandex SMR", "$github/WhereRubles"),
    Project("Coffee", "Android-приложение, которое каждый день встречает новым комплиментом.", "Kotlin · Android · Mobile", "$github/Coffee"),
    Project("T.Yurist", "Android-клиент продуктового учебного проекта T.Yurist.", "Kotlin · Android · Product", "$github/T.Yurist-android"),
    Project("Storyline", "Простое Android-приложение для писателей и работы с историями.", "Kotlin · Android · Writing", "$github/Storyline"),
)
private val assets = ConcurrentHashMap<String, String>()
internal fun asset(name: String): String = assets.computeIfAbsent(name) {
    val bytes = checkNotNull(Theme::class.java.getResourceAsStream("/static/$name")) { "Missing asset: $name" }.use { it.readBytes() }
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).take(6).joinToString("") { "%02x".format(it) }
    "/assets/$name?v=$hash"
}

fun HTML.document(theme: Theme, page: PortfolioPage = PortfolioPage.Welcome, neighbors: WebringNeighbors = WebringNeighbors(), tabs: WorkspaceTabs = WorkspaceTabs().open(page)) {
    lang = "ru"
    head {
        meta { charset = "utf-8" }
        meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
        title { +page.title }
        meta { name = "description"; content = "Дмитрий Дворянников — Kotlin-разработчик из Москвы. Инфраструктура и собственные проекты." }
        meta { attributes["property"] = "og:type"; content = "website" }
        meta { attributes["property"] = "og:url"; content = "https://itzephir.com${page.path}" }
        meta { attributes["property"] = "og:title"; content = page.title }
        meta { attributes["property"] = "og:description"; content = "Инфраструктура, сервисы и собственные проекты на Kotlin." }
        meta { attributes["property"] = "og:image"; content = "https://itzephir.com${asset("avatar.jpg")}" }
        meta { name = "htmx-config"; content = "{\"allowEval\":false,\"allowScriptTags\":false,\"selfRequestsOnly\":true}" }
        link { rel = "canonical"; href = "https://itzephir.com${page.path}" }
        // Favicon contrast follows browser chrome, independently of the site's saved theme.
        Theme.entries.forEach { chromeTheme ->
            val query = "(prefers-color-scheme: ${chromeTheme.value})"
            listOf(16, 32).forEach { size ->
                link {
                    rel = "icon"; type = "image/png"; sizes = "${size}x$size"; media = query
                    href = asset("design/${chromeTheme.value}/favicon-$size.png")
                }
            }
            link { rel = "icon"; type = "image/svg+xml"; media = query; href = asset("design/${chromeTheme.value}/logo.svg") }
        }
        link { rel = "stylesheet"; href = asset("styles.css") }
        link { rel = "preload"; href = asset("fonts/ibm-plex-sans.ttf"); attributes["as"] = "font"; type = "font/ttf"; attributes["crossorigin"] = "anonymous" }
        script { src = asset("htmx-2.0.11.min.js"); defer = true }
    }
    body { portfolio(theme, page, neighbors, tabs) }
}

fun FlowContent.portfolio(theme: Theme, page: PortfolioPage = PortfolioPage.Welcome, neighbors: WebringNeighbors = WebringNeighbors(), tabs: WorkspaceTabs = WorkspaceTabs().open(page)) {
    div("portfolio page-${page.name.lowercase()}") { portfolioContent(theme, page, neighbors, tabs) }
}
fun TagConsumer<*>.portfolio(theme: Theme, page: PortfolioPage = PortfolioPage.Welcome, neighbors: WebringNeighbors = WebringNeighbors(), tabs: WorkspaceTabs = WorkspaceTabs().open(page)) {
    div("portfolio page-${page.name.lowercase()}") { portfolioContent(theme, page, neighbors, tabs) }
}
private fun DIV.portfolioContent(theme: Theme, page: PortfolioPage, neighbors: WebringNeighbors, tabs: WorkspaceTabs) {
    id = "portfolio"; attributes["data-theme"] = theme.value
    a("#content", classes = "skip-link") { +"Перейти к содержимому" }
    header("app-header") {
        a("/", classes = "brand") {
            attributes["aria-label"] = "itzephir — главная"
            span("logo") { img(src = asset("design/${theme.value}/logo.svg"), alt = "", classes = "logo-artwork") { width = "64"; height = "64" } }
            span("brand-name") { +"itzephir" }
            span("mono muted workspace-label") { +"/ workspace" }
        }
        div("header-actions") {
            nav("webring mono") {
                attributes["aria-label"] = "Webring"
                a("${ring}itzephir/prev", classes = "ring-neighbor ring-previous") {
                    rel = "prev"; title = neighbors.previous; attributes["aria-label"] = "Предыдущий сайт: ${neighbors.previous}"
                    icon(theme, "chevron-left", 16, "webring-left-mobile"); span { +neighbors.previous }
                }
                a(ring, classes = "ring-index") { attributes["aria-label"] = "Все участники webring"; +"webring" }
                a("${ring}itzephir/next", classes = "ring-neighbor ring-next") {
                    rel = "next"; title = neighbors.next; attributes["aria-label"] = "Следующий сайт: ${neighbors.next}"
                    span { +neighbors.next }; icon(theme, "chevron-right", 16, "webring-right-mobile")
                }
            }
            span("mono muted header-language") { +"RU" }
            themeToggle(theme, page, false)
            externalLink(github, "icon-button header-github") { attributes["aria-label"] = "GitHub itzephir"; icon(theme, "github", 18) }
        }
    }
    nav("editor-tabs mono") {
        attributes["aria-label"] = "Открытые страницы"
        tabs.pages.forEach { opened -> editorTab(theme, opened, page, tabs.canClose(opened)) }
    }
    main(if (page == PortfolioPage.Welcome) "welcome-canvas" else "page-canvas") {
        id = "content"
        when (page) {
            PortfolioPage.Welcome -> welcome(theme, page)
            PortfolioPage.Projects -> projectList(theme, page)
            PortfolioPage.Profile -> profile(theme, page)
        }
    }
}
private fun FlowContent.editorTab(theme: Theme, opened: PortfolioPage, current: PortfolioPage, canClose: Boolean) {
    val active = opened == current
    div("editor-tab${if (active) " active" else ""}") {
        attributes["data-tab"] = opened.tabKey
        a(opened.path, classes = "editor-tab-link") {
            if (active) attributes["aria-current"] = "page"
            val image = opened.tabIcon
            icon(theme, if (active && image in listOf("file", "folder")) "$image-active" else image, 15)
            +opened.tabLabel
        }
        if (canClose) form(action = "/tabs/close", method = FormMethod.post, classes = "tab-close-form") {
            input(type = InputType.hidden, name = "tab") { value = opened.tabKey }
            input(type = InputType.hidden, name = "current") { value = current.tabKey }
            button(type = ButtonType.submit, classes = "tab-close") {
                attributes["aria-label"] = "Закрыть ${opened.tabLabel}"
                title = "Закрыть ${opened.tabLabel}"
                icon(theme, "close", 12)
            }
        }
    }
}
private fun FlowContent.themeToggle(theme: Theme, page: PortfolioPage, mobile: Boolean) {
    a("${page.path}?theme=${theme.opposite.value}", classes = "theme-toggle icon-button ${if (mobile) "mobile-theme" else "desktop-theme"}") {
        id = if (mobile) "theme-toggle-mobile" else "theme-toggle"
        attributes["aria-label"] = if (theme == Theme.Dark) "Включить светлую тему" else "Включить тёмную тему"
        title = if (theme == Theme.Dark) "Светлая тема" else "Тёмная тема"
        attributes["hx-get"] = href; attributes["hx-target"] = "#portfolio"
        attributes["hx-swap"] = "outerHTML show:none"; attributes["hx-sync"] = "#portfolio:drop"
        icon(theme, "theme", 18)
    }
}
private fun FlowContent.welcome(theme: Theme, page: PortfolioPage) {
    section("welcome-identity content-width") {
        attributes["aria-labelledby"] = "name"
        div("identity-and-theme") {
            div("greeting") {
                img(src = asset("portrait.png"), alt = "Дмитрий Дворянников", classes = "portrait") { width = "56"; height = "56"; attributes["fetchpriority"] = "high" }
                div { p("greeting-title") { +"Привет!" }; p("mono muted greeting-role") { +"Инфраструктура · Проекты" } }
            }
            themeToggle(theme, page, true)
        }
        h1("hero-name") {
            id = "name"; span { +"Дмитрий" }; +" "
            span { +"Дворянников"; span("personal-cursor") { attributes["aria-hidden"] = "true" } }
        }
        p("welcome-description muted") { +"Kotlin-разработчик из Москвы. Работаю в Яндексе, занимаюсь инфраструктурой и создаю собственные проекты." }
    }
    section("shortcuts content-width") {
        attributes["aria-labelledby"] = "shortcuts-heading"
        div("section-label mono muted") {
            h2 { id = "shortcuts-heading"; +"ОТКРЫТЬ В WORKSPACE" }
            span { span("desktop-only") { +"03 РАЗДЕЛА" }; span("mobile-only") { +"03" } }
        }
        div("shortcut-grid") {
            shortcut(theme, "01", "/projects", "projects/", "folder-open", "Посмотреть проекты", "От launcher для macOS до backend.", "Инструменты и приложения.")
            shortcut(theme, "02", "/about", "about.md", "profile", "Познакомиться ближе", "Опыт, технологии и подход к работе.", "Опыт, технологии и подход.")
            shortcut(theme, "03", telegram, "contacts", "messages", "Написать напрямую", "Telegram, почта или GitHub.")
        }
    }
    externalLink("$github/itzcast", "pinned-project content-width") {
        icon(theme, "pin", 18, "pin-mobile")
        div("pinned-copy") { p("pinned-title") { +"itzcast" }; p("muted pinned-description") { +"Быстрый launcher для macOS. Kotlin Multiplatform + Compose." } }
        span("mono pinned-action") { +"Открыть проект" }; icon(theme, "arrow", 16)
    }
    siteFooter(true)
}
private fun FlowContent.shortcut(theme: Theme, number: String, url: String, filename: String, image: String, label: String, description: String, mobileDescription: String = description) {
    a(url, classes = "shortcut") {
        if (url.startsWith("https://")) { target = "_blank"; rel = "noopener noreferrer" }
        div("shortcut-top") { icon(theme, image, 20); span("mono muted") { +filename } }
        span("shortcut-index mono") { +number }
        div("shortcut-copy") {
            h3 { +label }
            p("muted") {
                if (description == mobileDescription) +description
                else { span("desktop-only") { +description }; span("mobile-only") { +mobileDescription } }
            }
        }
        span("shortcut-chevron") { icon(theme, "chevron-right", 16) }
    }
}
private fun FlowContent.pageControls(theme: Theme, page: PortfolioPage) {
    div("page-controls") {
        a("/", classes = "back-link") { icon(theme, "back", 16); +"Назад" }
        themeToggle(theme, page, true)
    }
}
private fun FlowContent.projectList(theme: Theme, page: PortfolioPage) {
    div("page-content content-width") {
        section("page-introduction") {
            attributes["aria-labelledby"] = "projects-heading"; pageControls(theme, page)
            div("page-meta mono") { span { +"projects /" }; span("muted") { +"06 проектов" } }
            h1 { id = "projects-heading"; +"Мои проекты" }
            p("page-description muted") { +"Инструменты для себя, мобильные приложения и backend." }
        }
        featuredProject(theme)
        div("project-grid") {
            projects.forEach { project ->
                externalLink(project.url, "project") {
                    attributes["aria-label"] = "Открыть проект ${project.name}"
                    div("project-top") { h2 { +project.name }; icon(theme, "arrow-project", 18) }
                    p("project-description muted") { +project.description }; p("project-stack mono muted") { +project.stack }
                }
            }
            contactCard(theme)
        }
        siteFooter(false)
    }
}
private fun FlowContent.featuredProject(theme: Theme) {
    externalLink("$github/itzcast", "project featured-project") {
        attributes["aria-label"] = "Открыть проект itzcast"
        div("featured-copy") {
            p("label mono") { +"01 / DESKTOP TOOL" }; h2 { +"itzcast" }
            p("muted") { +"Быстрый расширяемый launcher для macOS. Хоткей, fuzzy search и независимые расширения." }
            p("mono muted featured-stack") { span("desktop-only") { +"Kotlin Multiplatform · Compose" }; span("mobile-only") { +"Kotlin · Compose · macOS" } }
            span("open-project") { +"Открыть проект"; icon(theme, "arrow-open", 16) }
        }
        div("launcher-preview") {
            attributes["aria-hidden"] = "true"
            div("launcher-heading mono muted") { span("label") { +"ITZCAST / LAUNCHER" }; span { +"macOS" } }
            div("launcher-query") { icon(theme, "search", 18); span { +"itz" }; span("query-cursor") }
            div("launcher-result") {
                icon(theme, "command", 20)
                div { p("result-title") { +"itzcast" }; p("muted result-description") { +"Быстрый доступ к приложениям" } }
                icon(theme, "return", 16)
            }
            div("launcher-hints mono muted") { span { +"fuzzy search" }; span { +"extensions" } }
        }
    }
}
private fun FlowContent.contactCard(theme: Theme) {
    section("contact-card") {
        attributes["aria-labelledby"] = "contact-heading"
        div("contact-top") { p("label mono muted") { +"ОБСУДИМ ИДЕЮ?" }; icon(theme, "messages-contact", 20, "messages-contact-mobile") }
        h2 { id = "contact-heading"; +"Давайте что-нибудь соберём." }
        externalLink(telegram, "open-project") { +"Написать в Telegram"; icon(theme, "arrow-open", 16) }
        a(email, classes = "contact-email muted") { +email.removePrefix("mailto:") }
    }
}
private fun FlowContent.profile(theme: Theme, page: PortfolioPage) {
    div("page-content profile-content content-width") {
        section("page-introduction") {
            pageControls(theme, page); p("page-meta mono") { +"about.md /" }; h1 { +"Обо мне" }
            p("page-description muted") { +"Инфраструктура и собственные проекты" }
        }
        section("profile-panel") {
            h2 { +"Дмитрий Дворянников" }
            p { +"Я Kotlin-разработчик из Москвы, работаю в Яндексе." }
            p { +"Сейчас мой фокус — инфраструктура и собственные проекты: от разработки до деплоя и поддержки." }
            p { +"Лектор ШМР Яндекса · 2026" }
            ul("stack-list mono") { listOf("Kotlin", "Ktor", "Docker", "Linux", "CI/CD").forEach { li { +it } } }
            a("/projects", classes = "text-link") { +"Мои проекты" }
        }
        contactCard(theme); siteFooter(false)
    }
}
private fun FlowContent.siteFooter(home: Boolean) {
    footer("site-footer content-width${if (home) " home-footer" else ""}") {
        div("footer-main") {
            a("/", classes = "mono muted footer-path") { +"~/itzephir" }
            if (home) span("mono muted footer-summary") { +"infrastructure / own projects" }
            nav("footer-links") {
                attributes["aria-label"] = "Контакты"
                externalLink(telegram, "contact-link") { +"Telegram" }; externalLink(github, "contact-link") { +"GitHub" }
                a(email, classes = "contact-link") { +"Email" }
            }
        }
        if (!home) p("footer-name muted") { +"Дмитрий Дворянников" }
    }
}
private fun FlowContent.icon(theme: Theme, name: String, size: Int, mobileName: String? = null) {
    span("icon icon-$size${if (mobileName != null) " responsive-icon" else ""}") {
        attributes["aria-hidden"] = "true"
        picture {
            if (mobileName != null) source { media = "(max-width: 719px)"; srcset = asset("design/${theme.value}/$mobileName.svg") }
            img(src = asset("design/${theme.value}/$name.svg"), alt = "") { attributes["draggable"] = "false" }
        }
    }
}
private fun FlowContent.externalLink(url: String, classes: String, block: A.() -> Unit) {
    a(url, classes = classes) { target = "_blank"; rel = "noopener noreferrer"; block() }
}
