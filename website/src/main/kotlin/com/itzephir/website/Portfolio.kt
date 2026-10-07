package com.itzephir.website

import kotlinx.html.*
import java.security.MessageDigest

enum class Theme(val value: String) {
    Dark("dark"), Light("light");

    val opposite: Theme get() = if (this == Dark) Light else Dark

    companion object {
        fun from(value: String?): Theme? = entries.find { it.value == value }
    }
}

private data class Project(
    val name: String,
    val description: String,
    val tags: List<String>,
    val url: String,
    val accent: String,
)

private val projects = listOf(
    Project("itzcast", "Быстрый расширяемый launcher для macOS: глобальный хоткей, fuzzy search и расширения через независимый JSONL-протокол.", listOf("Kotlin Multiplatform", "Compose", "macOS"), "https://github.com/itzephir/itzcast", "pink"),
    Project("Photorus TGApp Backend", "Ktor-микросервисы для образовательного Telegram WebApp: OCR, AI-разбор правил, SSE и MongoDB.", listOf("Kotlin", "Ktor", "Docker"), "https://github.com/itzephir/photorus-tgapp-backend", "blue"),
    Project("WhereRubles", "Android-приложение для учёта баланса личного счёта, сделанное во время Школы мобильной разработки Яндекса 2025.", listOf("Kotlin", "Android", "Yandex SMR"), "https://github.com/itzephir/WhereRubles", "amber"),
    Project("Coffee", "Небольшое Android-приложение, которое каждый день встречает пользователя новым комплиментом.", listOf("Kotlin", "Android", "Mobile"), "https://github.com/itzephir/Coffee", "neutral"),
    Project("T.Yurist", "Android-клиент продуктового учебного проекта T.Yurist.", listOf("Kotlin", "Android", "Product"), "https://github.com/itzephir/T.Yurist-android", "pink"),
    Project("Storyline", "Простое Android-приложение для писателей и работы с историями.", listOf("Kotlin", "Android", "Writing"), "https://github.com/itzephir/Storyline", "blue"),
)

private val assets by lazy {
    listOf("styles.css", "htmx-2.0.11.min.js", "avatar.jpg", "favicon.svg", "fonts/roboto.ttf", "fonts/roboto-mono.ttf").associateWith { name ->
        val bytes = checkNotNull(Theme::class.java.getResourceAsStream("/static/$name")) { "Missing asset: $name" }.use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).take(6).joinToString("") { "%02x".format(it) }
        "/assets/$name?v=$hash"
    }
}

private fun asset(name: String) = assets.getValue(name)

fun HTML.document(theme: Theme) {
    lang = "ru"
    head {
        meta { charset = "utf-8" }
        meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
        title { +"Дмитрий Дворянников — Kotlin Developer" }
        meta {
            name = "description"
            content = "Персональный сайт Дмитрия Дворянникова — Mobile & Backend Kotlin developer из Москвы. Kotlin Multiplatform, Compose, Ktor, Swift и UIKit."
        }
        meta { attributes["property"] = "og:type"; content = "website" }
        meta { attributes["property"] = "og:url"; content = "https://itzephir.com/" }
        meta { attributes["property"] = "og:title"; content = "Дмитрий Дворянников — Kotlin Developer" }
        meta { attributes["property"] = "og:description"; content = "Приложения, сервисы и инструменты на Kotlin, Compose Multiplatform, Ktor и Swift." }
        meta { attributes["property"] = "og:image"; content = "https://itzephir.com${asset("avatar.jpg")}" }
        meta { name = "htmx-config"; content = "{\"allowEval\":false,\"allowScriptTags\":false,\"selfRequestsOnly\":true}" }
        link { rel = "canonical"; href = "https://itzephir.com/" }
        link { rel = "icon"; href = asset("favicon.svg"); type = "image/svg+xml" }
        link { rel = "stylesheet"; href = asset("styles.css") }
        script { src = asset("htmx-2.0.11.min.js"); defer = true }
    }
    body {
        portfolio(theme)
    }
}

fun FlowContent.portfolio(theme: Theme) {
    div("portfolio") { portfolioContent(theme) }
}

fun TagConsumer<*>.portfolio(theme: Theme) {
    div("portfolio") { portfolioContent(theme) }
}

private fun DIV.portfolioContent(theme: Theme) {
    id = "portfolio"
    attributes["data-theme"] = theme.value
    a("#content", classes = "skip-link") { +"Перейти к содержимому" }
    div("page") {
        header {
            div("brand mono") {
                p { +"ITZEPHIR / PORTFOLIO" }
                p("small muted") { +"MOSCOW · 2026" }
            }
            div("header-actions") {
                nav("webring mono small") {
                    attributes["aria-label"] = "Webring navigation"
                    a("https://webring.otomir23.me/itzephir/prev") { rel = "prev"; attributes["aria-label"] = "Предыдущий сайт в webring"; +"← PREV" }
                    a("https://webring.otomir23.me/") { attributes["aria-label"] = "Открыть список сайтов webring"; +"WEBRING" }
                    a("https://webring.otomir23.me/itzephir/next") { rel = "next"; attributes["aria-label"] = "Следующий сайт в webring"; +"NEXT →" }
                }
                a("/?theme=${theme.opposite.value}", classes = "theme-toggle mono small") {
                    id = "theme-toggle"
                    attributes["aria-label"] = if (theme == Theme.Dark) "Включить светлую тему" else "Включить тёмную тему"
                    attributes["hx-get"] = href
                    attributes["hx-target"] = "#portfolio"
                    attributes["hx-swap"] = "outerHTML show:none"
                    attributes["hx-sync"] = "this:drop"
                    span("theme-dot") { attributes["aria-hidden"] = "true" }
                    +if (theme == Theme.Dark) "LIGHT" else "DARK"
                }
            }
        }
        main {
            id = "content"
            section("hero") {
                attributes["aria-labelledby"] = "name"
                div("hero-copy") {
                    h1 { id = "name"; +"Дмитрий"; br(); +"Дворянников" }
                    p("role") { +"Mobile & Backend Kotlin Developer" }
                    p("position mono") { +"ЯНДЕКС / ЛЕКТОР ШМР 2026" }
                    p("lead muted") { +"Делаю продукты, которыми хочется пользоваться: от мобильных интерфейсов до надёжных серверных систем." }
                    div("hero-actions") {
                        externalLink("https://github.com/itzephir", "button primary") { +"Смотреть GitHub"; span { attributes["aria-hidden"] = "true"; +" >" } }
                        externalLink("https://t.me/ItzEphir", "button secondary") { +"Написать мне" }
                    }
                }
                img(src = asset("avatar.jpg"), alt = "Дмитрий Дворянников", classes = "portrait") {
                    width = "320"; height = "320"; attributes["fetchpriority"] = "high"
                }
            }
            section("about section") {
                id = "about"
                attributes["aria-labelledby"] = "about-heading"
                sectionHeading("01", "Обо мне", "about-heading")
                div("about-grid") {
                    article("panel about-copy") {
                        span("eyebrow mono small") { +"README.MD" }
                        p("lead") { +"Я Kotlin-разработчик из Москвы, работаю в Яндексе. В 2026 году — лектор Школы мобильной разработки. Люблю задачи на стыке инженерии и продукта: продумывать архитектуру, собирать выразительный UI и доводить идею до работающего релиза." }
                        p("muted") { +"В фокусе — Kotlin Multiplatform, Compose, Android и backend на Ktor. Иногда ухожу в Swift, инфраструктуру и инструменты для разработчиков." }
                    }
                    aside("panel skills") {
                        h3("mono") { +"STACK / NOW" }
                        ul("tags skill-tags") {
                            listOf("KOTLIN", "KMP", "COMPOSE", "KTOR", "ANDROID", "SWIFT", "DOCKER").forEach { li("mono small") { +it } }
                        }
                        p("mono small") { +"STATUS" }
                        p("status") { +"Строю, исследую, выпускаю." }
                    }
                }
            }
            section("projects section") {
                id = "projects"
                attributes["aria-labelledby"] = "projects-heading"
                sectionHeading("02", "Избранные проекты", "projects-heading")
                div("project-grid") {
                    projects.forEachIndexed { index, project ->
                        externalLink(project.url, "project ${project.accent}") {
                            attributes["aria-label"] = "Открыть проект ${project.name}"
                            div("project-top") {
                                span("mono") { +"PROJECT / ${"%02d".format(index + 1)}" }
                                span("project-arrow") { attributes["aria-hidden"] = "true"; +">" }
                            }
                            h3 { +project.name }
                            p { +project.description }
                            ul("tags") { project.tags.forEach { li("mono small") { +it.uppercase() } } }
                        }
                    }
                }
            }
            section("contact section") {
                id = "contact"
                attributes["aria-labelledby"] = "contact-heading"
                div {
                    p("mono") { +"03 / CONTACT" }
                    h2 { id = "contact-heading"; +"Давайте сделаем"; br(); +"что-нибудь классное." }
                }
                div("contact-links") {
                    contactLink("TELEGRAM", "@ItzEphir", "https://t.me/ItzEphir")
                    contactLink("EMAIL", "d.y.dvoryannikov@mail.ru", "mailto:d.y.dvoryannikov@mail.ru")
                    contactLink("GITHUB", "itzephir", "https://github.com/itzephir")
                }
            }
        }
        footer("mono small muted") {
            p { +"BUILT WITH KOTLIN / KTOR / KOTLINX.HTML / HTMX" }
            p { +"ITZEPHIR.COM · MOSCOW · 2026" }
        }
    }
}

private fun FlowContent.sectionHeading(number: String, title: String, headingId: String) {
    div("section-heading") {
        span("eyebrow mono") { +number }
        h2 { id = headingId; +title }
        span("section-rule") { attributes["aria-hidden"] = "true" }
    }
}

private fun FlowContent.contactLink(label: String, value: String, url: String) {
    externalLink(url, "contact-link") {
        div { span("mono small") { +label }; span("contact-value") { +value } }
        span { attributes["aria-hidden"] = "true"; +">" }
    }
}

private fun FlowContent.externalLink(url: String, classes: String, block: A.() -> Unit) {
    a(url, classes = classes) {
        if (url.startsWith("https://")) { target = "_blank"; rel = "noopener noreferrer" }
        block()
    }
}
