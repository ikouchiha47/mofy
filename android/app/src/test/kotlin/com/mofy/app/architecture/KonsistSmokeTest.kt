package com.mofy.app.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withNameEndingWith
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

/**
 * Integration smoke test, not a real rubric yet - confirms Konsist can
 * actually parse this project's source and enforce a rule against it
 * before any broader SOLID/architecture ruleset is designed. See the
 * "Search screen with filters" work this rule is checking: Compose screen
 * functions should live under ui/, not scattered into data/ alongside the
 * DAOs/repositories they call.
 */
class KonsistSmokeTest {

    @Test
    fun `every top-level Screen function lives under the ui package`() {
        Konsist
            .scopeFromProject()
            .functions()
            .withNameEndingWith("Screen")
            .assertTrue { it.packagee?.name?.startsWith("com.mofy.app.ui") == true }
    }
}
