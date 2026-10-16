package com.itzephir.website

data class WorkspaceTabs(val pages: List<PortfolioPage> = emptyList()) {
    val cookieValue: String get() = pages.joinToString(".") { it.tabKey }

    fun open(page: PortfolioPage): WorkspaceTabs =
        if (page in pages) this else WorkspaceTabs(pages + page)

    fun canClose(page: PortfolioPage): Boolean = pages.size > 1 || page != PortfolioPage.Welcome

    fun close(page: PortfolioPage, active: PortfolioPage): TabCloseResult {
        val index = pages.indexOf(page)
        if (index < 0 || !canClose(page)) return TabCloseResult(open(active), active)
        val remaining = pages.filterNot { it == page }
        val next = when {
            page != active -> active
            remaining.isEmpty() -> PortfolioPage.Welcome
            else -> remaining[(index - 1).coerceIn(0, remaining.lastIndex)]
        }
        return TabCloseResult(WorkspaceTabs(remaining).open(next), next)
    }

    companion object {
        fun fromCookie(value: String?): WorkspaceTabs = WorkspaceTabs(
            value.orEmpty().split('.').mapNotNull(PortfolioPage::fromTabKey).distinct(),
        )
    }
}

data class TabCloseResult(val tabs: WorkspaceTabs, val page: PortfolioPage)
