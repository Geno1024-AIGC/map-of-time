package com.geno1024.ai.mapoftime.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadNamesTest {
    @Test
    fun aCompositeNameSplitsIntoDistinctRoads() {
        assertEquals(listOf("科创路", "学府路"), RoadNames.split("科创路 × 学府路"))
        assertEquals(listOf("A", "B", "C"), RoadNames.split("A×B × C"))
        // Duplicates from a name like "人民路 × 人民路" collapse.
        assertEquals(listOf("人民路"), RoadNames.split("人民路 × 人民路"))
    }

    @Test
    fun anAtomicOrBlankNameIsNotACrossing() {
        // split still returns the single road; isComposite is what decides whether it is a crossing.
        assertEquals(listOf("科创路"), RoadNames.split("科创路"))
        assertEquals(emptyList<String>(), RoadNames.split(null))
        assertEquals(emptyList<String>(), RoadNames.split("  ×   "))
        assertFalse(RoadNames.isComposite("科创路"))
        assertFalse(RoadNames.isComposite(null))
        assertTrue(RoadNames.isComposite("科创路 × 学府路"))
    }

    @Test
    fun joiningUsesOneSeparatorAndDropsDuplicates() {
        assertEquals("科创路 × 学府路", RoadNames.join(listOf("科创路", " ", "学府路", "科创路")))
        assertEquals("科创路", RoadNames.join(listOf(" 科创路 ")))
        assertNull(RoadNames.join(emptyList()))
        assertNull(RoadNames.join(listOf("  ", "")))
    }
}
