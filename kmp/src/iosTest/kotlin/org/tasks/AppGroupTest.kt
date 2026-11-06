package org.tasks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppGroupTest {
    @Test
    fun testBinaryHasNoAppGroup() {
        assertNull(appGroupId)
    }

    @Test
    fun fallsBackToDocumentsWithoutAppGroup() {
        assertEquals(documentsPath(), appGroupPath())
        assertEquals(documentsPath(), appGroupPath(null))
    }
}
