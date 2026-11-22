package com.geno1024.ai.mapoftime.data

/**
 * A name spelled "A × B" is a crossing of roads, not the name of one point and
 * never the name of one segment. Keeping the split here means the database
 * migration, the UI and the naming rules all agree on what a composite name
 * means, instead of each growing its own string parser.
 */
object RoadNames {
    const val SEPARATOR = " × "

    /** `null`, blank and one-part names are not crossings. */
    fun split(text: String?): List<String> =
        text?.split('×')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()

    fun isComposite(text: String?): Boolean = split(text).size > 1

    fun join(roads: Collection<String>): String? =
        roads.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .joinToString(SEPARATOR)
            .takeIf { it.isNotEmpty() }
}
