package com.geno1024.ai.mapoftime.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripImportTest {
    private val document = """
        {
          "format": "map-of-time/3",
          "segments": [
            {
              "key": "geo:4-7",
              "mode": "walk",
              "name": "Main Street",
              "userName": "My short cut",
              "fromKey": "geo:4",
              "toKey": "geo:7",
              "identity": "GEOMETRIC",
              "lengthM": 120.5,
              "fromLat": 39.9, "fromLon": 116.3,
              "toLat": 39.901, "toLon": 116.301
            }
          ],
          "points": [
            {"kind": "geo", "nodeId": 4, "name": "north gate"},
            {"kind": "osm", "nodeId": 99, "name": "metro"},
            {"kind": "osm", "nodeId": 0, "name": "not a point"},
            {"kind": "nope", "nodeId": 5, "name": "also not"}
          ],
          "pointRoads": [
            {"kind": "geo", "nodeId": 4, "roads": ["科创路", "学府路", "科创路", " "]},
            {"kind": "nope", "nodeId": 6, "roads": ["ignored"]},
            {"kind": "geo", "nodeId": 0, "roads": ["ignored"]}
          ],
          "trips": [
            {
              "id": 3,
              "mode": "ebike",
              "startedAt": 1700000000000,
              "endedAt": 1700000600000,
              "distanceM": 1500.0,
              "status": "FINISHED",
              "note": "raining",
              "legs": [
                {
                  "startMs": 1700000000000,
                  "endMs": 1700000030000,
                  "distanceM": 120.5,
                  "segmentKey": "geo:4-7",
                  "direction": -1,
                  "partial": false,
                  "polyline": "abc",
                  "movingMs": 25000,
                  "stoppedMs": 5000,
                  "stopCount": 1,
                  "longestStopMs": 5000,
                  "signalStopCount": 1,
                  "signalWaitMs": 5000
                },
                {
                  "startMs": 1700000030000
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun readsSegmentsTripsAndTheirLegs() {
        val bundle = TripImport.parse(document)
        assertNotNull(bundle)
        bundle!!

        assertEquals(1, bundle.segments.size)
        assertEquals("Main Street", bundle.segments.first().name)
        assertEquals("My short cut", bundle.segments.first().userName)
        assertEquals("geo:4", bundle.segments.first().fromKey)
        assertEquals("geo:7", bundle.segments.first().toKey)
        assertEquals(120.5, bundle.segments.first().lengthM, 1e-9)

        // Importing a point whose kind or id makes no sense would create a name
        // nothing can ever point at, so those rows are dropped.
        assertEquals(2, bundle.points.size)
        assertEquals(PointRef("geo", 4), bundle.points.first().ref)
        assertEquals("north gate", bundle.points.first().name)

        // Roads at a point travel as a list, never as a composite point name.
        // Invalid refs, blank and duplicate roads are dropped on read.
        assertEquals(1, bundle.pointRoads.size)
        assertEquals(PointRef("geo", 4), bundle.pointRoads.first().ref)
        assertEquals(listOf("科创路", "学府路"), bundle.pointRoads.first().roads)

        assertEquals(1, bundle.trips.size)
        val trip = bundle.trips.first()
        assertEquals("ebike", trip.mode.id)
        assertEquals("raining", trip.note)
        assertEquals(TripStatus.FINISHED, trip.status)
        // The second leg has no end and is dropped instead of inventing one.
        assertEquals(1, trip.legs.size)
        assertEquals(1, bundle.legCount)

        val leg = trip.legs.first()
        assertEquals("geo:4-7", leg.segmentKey)
        assertEquals(-1, leg.direction)
        assertEquals(1, leg.signalStopCount)
        assertEquals(5_000L, leg.signalWaitMs)
    }

    @Test
    fun anUnknownFormatIsRejectedAsAWhole() {
        assertNull(TripImport.parse("""{"format":"something-else","trips":[]}"""))
        assertNull(TripImport.parse("not json"))
    }

    @Test
    fun aVersionTwoDocumentWithoutPointRoadsStillImports() {
        val bundle = TripImport.parse(
            """
            {"format":"map-of-time/2","trips":[
              {"mode":"walk","startedAt":1700000000000,"legs":[]}
            ]}
            """.trimIndent(),
        )
        assertNotNull(bundle)
        assertTrue(bundle!!.pointRoads.isEmpty())
    }

    @Test
    fun anOlderDocumentStillImportsItsTrips() {
        // Version 1 had no segmentKey on legs; the trip is still worth keeping.
        val bundle = TripImport.parse(
            """
            {
              "format": "map-of-time/1",
              "trips": [
                {
                  "mode": "walk",
                  "startedAt": 1700000000000,
                  "endedAt": 1700000600000,
                  "legs": [
                    {"startMs": 1700000000000, "endMs": 1700000060000}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        assertNotNull(bundle)
        assertEquals(1, bundle!!.trips.size)
        assertNull(bundle.trips.first().legs.first().segmentKey)
    }

    @Test
    fun aTripWithoutAStartTimeIsSkipped() {
        val bundle = TripImport.parse(
            """
            {"format":"map-of-time/2","trips":[
              {"mode":"walk","startedAt":0,"legs":[]},
              {"mode":"unknown","startedAt":1700000000000,"legs":[]}
            ]}
            """.trimIndent(),
        )
        assertNotNull(bundle)
        assertTrue(bundle!!.trips.isEmpty())
    }
}
