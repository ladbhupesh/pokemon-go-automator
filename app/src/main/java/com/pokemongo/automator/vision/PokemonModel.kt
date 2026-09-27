package com.pokemongo.automator.vision

import java.io.Reader
import kotlin.math.exp

/**
 * Gradient-boosted trees exported from the training notebook (`assets/pokemon_gb.txt`).
 * Returns the probability that a map candidate is a wild Pokémon.
 */
class PokemonModel private constructor(
    private val learningRate: Double,
    private val init: Double,
    private val trees: List<Tree>,
) {
    private class Tree(
        val feature: IntArray,
        val threshold: DoubleArray,
        val left: IntArray,
        val right: IntArray,
        val value: DoubleArray,
    )

    fun predict(features: FloatArray): Double {
        var raw = init
        for (tree in trees) {
            var node = 0
            while (tree.left[node] != -1) {
                node = if (features[tree.feature[node]].toDouble() <= tree.threshold[node]) {
                    tree.left[node]
                } else {
                    tree.right[node]
                }
            }
            raw += learningRate * tree.value[node]
        }
        return 1.0 / (1.0 + exp(-raw))
    }

    companion object {
        const val ASSET = "pokemon_gb.txt"

        fun read(reader: Reader): PokemonModel {
            val lines = reader.readLines().iterator()
            val header = lines.next().split(' ')
            require(header[0] == "gb") { "not a gb model" }
            val count = header[2].toInt()
            val learningRate = header[3].toDouble()
            val init = header[4].toDouble()
            val trees = List(count) {
                val nodes = lines.next().split(' ')[1].toInt()
                val feature = IntArray(nodes)
                val threshold = DoubleArray(nodes)
                val left = IntArray(nodes)
                val right = IntArray(nodes)
                val value = DoubleArray(nodes)
                for (i in 0 until nodes) {
                    val parts = lines.next().split(' ')
                    feature[i] = parts[0].toInt()
                    threshold[i] = parts[1].toDouble()
                    left[i] = parts[2].toInt()
                    right[i] = parts[3].toInt()
                    value[i] = parts[4].toDouble()
                }
                Tree(feature, threshold, left, right, value)
            }
            return PokemonModel(learningRate, init, trees)
        }
    }
}
