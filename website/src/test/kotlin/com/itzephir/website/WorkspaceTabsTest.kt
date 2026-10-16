package com.itzephir.website

import kotlin.test.*

class WorkspaceTabsTest {
    private val welcome = PortfolioPage.Welcome
    private val projects = PortfolioPage.Projects
    private val profile = PortfolioPage.Profile

    @Test
    fun `open preserves arrival order without duplicates or unvisited pages`() {
        assertEquals(emptyList(), WorkspaceTabs.fromCookie(null).pages)
        val tabs = WorkspaceTabs().open(projects).open(welcome).open(profile).open(projects)
        assertEquals(listOf(projects, welcome, profile), tabs.pages)
        assertEquals("projects.welcome.profile", tabs.cookieValue)
        assertEquals(tabs, WorkspaceTabs.fromCookie(tabs.cookieValue))
        assertEquals(listOf(projects, profile), WorkspaceTabs.fromCookie("unknown.projects.projects.profile../elsewhere").pages)
    }

    @Test
    fun `closing inactive pages preserves the active page and closing active selects its neighbor`() {
        val tabs = WorkspaceTabs().open(welcome).open(projects).open(profile)
        assertEquals(TabCloseResult(WorkspaceTabs(listOf(welcome, profile)), profile), tabs.close(projects, profile))
        assertEquals(TabCloseResult(WorkspaceTabs(listOf(welcome, projects)), projects), tabs.close(profile, profile))
        assertEquals(TabCloseResult(WorkspaceTabs(listOf(projects, profile)), projects), tabs.close(welcome, welcome))
    }

    @Test
    fun `closing the final content tab opens welcome and a lone welcome has no no-op close control`() {
        val result = WorkspaceTabs().open(projects).close(projects, projects)
        assertEquals(WorkspaceTabs().open(welcome), result.tabs)
        assertEquals(welcome, result.page)
        assertFalse(result.tabs.canClose(welcome))
        assertEquals(result, result.tabs.close(welcome, welcome))
        assertTrue(result.tabs.open(profile).canClose(welcome))
    }
}
