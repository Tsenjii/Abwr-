package dev.local.aibrowser

import org.junit.Assert.*
import org.junit.Test

class TabGroupsTest {
    @Test fun deletedGroupIdsAreNotReusedAfterRestart() {
        val first = TabGroups()
        val deleted = first.create("G001", "old", 0, emptyList(), emptySet())
        first.ungroup("G001", deleted.id)
        val restored = TabGroups(first.nextSequence)
        assertNotEquals(deleted.id, restored.create("G001", "new", 0, emptyList(), emptySet()).id)
    }
    @Test fun movingWithinContainerPreservesTabsAndExclusiveMembership() {
        val groups = TabGroups(); val valid = setOf("main", "T1", "T2")
        val a = groups.create("G001", "閱讀", 0, listOf("main", "T1"), valid)
        val b = groups.create("G001", "工作", 1, listOf("T2"), valid)
        groups.move("G001", listOf("T1"), b.id, valid)
        assertEquals(setOf("main"), a.tabs)
        assertEquals(setOf("T2", "T1"), b.tabs)
        groups.ungroup("G001", b.id)
        assertNull(groups.membership("G001", "T1"))
        assertEquals(setOf("main", "T1", "T2"), valid)
    }
    @Test fun invalidMoveCannotRemoveExistingMembershipOrCrossContainers() {
        val groups = TabGroups(); val valid = setOf("main", "T1")
        val a = groups.create("G001", "A", 0, listOf("T1"), valid)
        val b = groups.create("G002", "B", 1, listOf("main"), valid)
        assertThrows(IllegalArgumentException::class.java) { groups.move("G001", listOf("T1"), b.id, valid) }
        assertThrows(IllegalArgumentException::class.java) { groups.move("G001", listOf("T1", "gone"), null, valid) }
        assertEquals(setOf("T1"), a.tabs)
        assertEquals(setOf("main"), b.tabs)
    }
    @Test fun restoredGroupsKeepNamesColorsAndDiscardClosedOrDuplicateMembers() {
        val groups = TabGroups()
        groups.restore(TabGroups.Group("group9", "G001", " 公益 ", 2, linkedSetOf("main", "gone")), setOf("main", "T1"))
        groups.restore(TabGroups.Group("group10", "G001", "閱讀", 4, linkedSetOf("main", "T1")), setOf("main", "T1"))
        assertEquals(setOf("main"), groups.group("G001", "group9").tabs)
        assertEquals(setOf("T1"), groups.group("G001", "group10").tabs)
        assertEquals("公益", groups.group("G001", "group9").name)
        assertEquals("group11", groups.create("G001", "新群組", 5, emptyList(), emptySet()).id)
        groups.removeTab("G001", "T1")
        assertTrue(groups.group("G001", "group10").tabs.isEmpty())
    }
}
