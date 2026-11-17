package org.tasks.compose.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class BackupsDetailTest {

    @Test
    fun importRowStartsTheImport() = runComposeUiTest {
        var imports = 0
        setContent { BackupsDetail(onNavigateBack = {}, onImportBackup = { imports++ }) }

        waitUntil { onAllNodes(hasText("Import backup")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Import backup").performClick()

        assertEquals(1, imports)
    }
}
