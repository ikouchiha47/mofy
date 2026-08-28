package com.mofy.app.search

class VectorMoodPlotQueryResolver(
    private val embedder: (String) -> FloatArray,
    private val index: VectorIndex,
) : MoodPlotQueryResolver {
    override fun resolve(freeTextQuery: String, k: Int): List<VectorMatch> =
        index.queryNearest(embedder(freeTextQuery), k)
}
