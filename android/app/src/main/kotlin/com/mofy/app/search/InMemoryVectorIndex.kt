package com.mofy.app.search

import kotlin.math.sqrt

class InMemoryVectorIndex : VectorIndex {
    private val entries = mutableListOf<VectorEntry>()

    override fun insert(entry: VectorEntry) {
        entries += entry
    }

    override fun queryNearest(queryVector: FloatArray, k: Int): List<VectorMatch> {
        if (entries.isEmpty()) return emptyList()
        val qNorm = norm(queryVector)
        return entries
            .map { e -> VectorMatch(id = e.id, score = cosine(queryVector, qNorm, e.vector)) }
            .filter { it.score >= 0.0 }
            .sortedByDescending { it.score }
            .take(k)
    }

    private fun norm(v: FloatArray): Double = sqrt(v.sumOf { it.toDouble() * it })

    private fun cosine(q: FloatArray, qNorm: Double, d: FloatArray): Double {
        if (qNorm == 0.0) return 0.0
        val dNorm = norm(d)
        if (dNorm == 0.0) return 0.0
        var dot = 0.0
        for (i in q.indices) dot += q[i].toDouble() * d[i]
        return dot / (qNorm * dNorm)
    }
}
