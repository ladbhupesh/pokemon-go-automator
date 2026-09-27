package com.pokemongo.automator.catch

interface CatchGestures {
    suspend fun tap(x: Int, y: Int)

    suspend fun straightThrow(screenWidth: Int, screenHeight: Int)

    suspend fun flee(screenWidth: Int, screenHeight: Int)

    suspend fun back()
}
