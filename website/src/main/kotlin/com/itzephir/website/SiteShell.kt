package com.itzephir.website

import kotlinx.html.*

private const val ring = "https://webring.otomir23.me/"

/** Shared shell contract v1. Pages supply main content, never header markup or layout. */
internal fun DIV.siteShell(
    theme: Theme,
    page: PortfolioPage,
    neighbors: WebringNeighbors,
    tabs: WorkspaceTabs,
    content: MAIN.() -> Unit,
) {
    id = "portfolio"; attributes["data-theme"] = theme.value
    attributes["data-shell-contract"] = "1"
    a("#content", classes = "skip-link") { +"Перейти к содержимому" }
    header("app-header") {
        a("/", classes = "brand") {
            attributes["aria-label"] = "itzephir — главная"
            span("logo") { img(src = asset("design/${theme.value}/wordmark.svg"), alt = "", classes = "logo-artwork") }
            span("mono muted workspace-label") { +page.path }
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
        content()
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
internal fun FlowContent.themeToggle(theme: Theme, page: PortfolioPage, mobile: Boolean) {
    a("${page.path}?theme=${theme.opposite.value}", classes = "theme-toggle icon-button ${if (mobile) "mobile-theme" else "desktop-theme"}") {
        id = if (mobile) "theme-toggle-mobile" else "theme-toggle"
        attributes["aria-label"] = if (theme == Theme.Dark) "Включить светлую тему" else "Включить тёмную тему"
        title = if (theme == Theme.Dark) "Светлая тема" else "Тёмная тема"
        attributes["hx-get"] = href; attributes["hx-target"] = "#portfolio"
        attributes["hx-swap"] = "outerHTML show:none"; attributes["hx-sync"] = "#portfolio:drop"
        icon(theme, "theme", 18)
    }
}
