package com.geno1024.ai.mapoftime.analysis

import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.data.Edge
import com.geno1024.ai.mapoftime.data.EdgeShapeRow

/**
 * Drawing shapes for a network snapshot. Pure so the OSM-first, GPS-fallback
 * priority can be tested without a database or a UI.
 */
object RoadShapes {
    fun build(
        edges: List<Edge>,
        recorded: Map<Long, List<EdgeShapeRow>>,
        osmPaths: OsmPathIndex,
    ): Map<Long, List<LatLon>> = edges.associate { edge ->
        val shape = OsmEdgeGeometry.shape(osmPaths, edge)
            ?.let(EdgeGeometry::clean)
            ?: recorded[edge.id]?.let { EdgeGeometry.displayShape(edge, it) }.orEmpty()
        edge.id to shape
    }
}
