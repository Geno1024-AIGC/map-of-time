package com.geno1024.ai.mapoftime.osm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverpassParserTest {
    private val response = """
        {
          "version": 0.6,
          "elements": [
            {
              "type": "way",
              "id": 123,
              "nodes": [1, 2, 3],
              "geometry": [
                {"lat": 39.9000, "lon": 116.3000},
                {"lat": 39.9005, "lon": 116.3000},
                {"lat": 39.9010, "lon": 116.3000}
              ],
              "tags": {"highway": "residential", "name": "Example Road", "oneway": "yes"}
            },
            {
              "type": "way",
              "id": 124,
              "nodes": [4, 5],
              "geometry": [
                {"lat": 39.91, "lon": 116.31},
                {"lat": 39.92, "lon": 116.31}
              ],
              "tags": {"highway": "footway", "ref": "F1"}
            },
            {
              "type": "way",
              "id": 125,
              "nodes": [6, 7],
              "geometry": [
                {"lat": 39.93, "lon": 116.32},
                {"lat": 39.94, "lon": 116.32}
              ],
              "tags": {"highway": "construction"}
            },
            {
              "type": "node",
              "id": 9,
              "lat": 39.9,
              "lon": 116.3,
              "tags": {"highway": "crossing"}
            },
            {
              "type": "way",
              "id": 126,
              "nodes": [8, 9, 10],
              "geometry": [
                {"lat": 39.95, "lon": 116.33},
                {"lat": 39.96, "lon": 116.33}
              ],
              "tags": {"highway": "service"}
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesWaysWithNamesAndOneway() {
        val ways = OverpassParser.parse(response)
        val road = ways.first { it.id == 123L }
        assertEquals("Example Road", road.name)
        assertEquals("residential", road.highway)
        assertTrue(road.oneway)
        assertEquals(listOf(1L, 2L, 3L), road.nodeIds)
        assertEquals(3, road.points.size)
    }

    @Test
    fun fallsBackToRefWhenThereIsNoName() {
        val ways = OverpassParser.parse(response)
        val footway = ways.first { it.id == 124L }
        assertNull(footway.name)
        assertEquals("F1", footway.label)
    }

    @Test
    fun skipsUntravellableWaysAndMalformedElements() {
        val ids = OverpassParser.parse(response).map { it.id }
        assertTrue(125L !in ids)
        assertTrue(126L !in ids)
        assertTrue(9L !in ids)
    }

    @Test
    fun garbageInGarbageOutWithoutThrowing() {
        assertTrue(OverpassParser.parse("not json").isEmpty())
        assertTrue(OverpassParser.parse("{}").isEmpty())
        assertTrue(OverpassParser.parse("""{"elements":[{"type":"way"}]}""").isEmpty())
    }

    @Test
    fun emptyAnswerIsEmptyNotAnError() {
        assertTrue(OverpassParser.parse("""{"elements":[]}""").isEmpty())
    }
}
