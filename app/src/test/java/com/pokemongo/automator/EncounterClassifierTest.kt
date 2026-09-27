package com.pokemongo.automator

import com.pokemongo.automator.catch.EncounterClassifier
import com.pokemongo.automator.catch.ScreenRead
import org.junit.Assert.assertEquals
import org.junit.Test

class EncounterClassifierTest {
    @Test
    fun classifiesCatchMissFleeAndEncounter() {
        assertEquals(ScreenRead.CAUGHT, EncounterClassifier.classify("Gotcha!"))
        assertEquals(ScreenRead.CAUGHT, EncounterClassifier.classify("Pikachu was caught"))
        assertEquals(ScreenRead.WILD, EncounterClassifier.classify("Razz Nanab Pinap Cherubi CP???"))
        assertEquals(ScreenRead.BROKE_FREE, EncounterClassifier.classify("Oh no! The Pokémon broke free!"))
        assertEquals(ScreenRead.FLED, EncounterClassifier.classify("The wild Pokémon fled"))
        assertEquals(ScreenRead.FLED, EncounterClassifier.classify("It ran away"))
        assertEquals(ScreenRead.WILD, EncounterClassifier.classify("A wild Pikachu appeared!"))
        assertEquals(ScreenRead.WILD, EncounterClassifier.classify("A wild Pawmot appeared! Research Tasks Updated"))
        assertEquals(ScreenRead.UNKNOWN, EncounterClassifier.classify("PokéStop in range!"))
        assertEquals(
            ScreenRead.UNKNOWN,
            EncounterClassifier.classify("1/ S40+ FIRST POKÉSTOP OF THE DAY 2 DAY STREAK! POKE BALL PINAP BERRY POKÉ BALL"),
        )
        assertEquals(
            ScreenRead.UNKNOWN,
            EncounterClassifier.classify("25 ladbhupesh1369 NANAB BERRY Received 5 items from PokéStop. Discarded Nanab Berry2 GIFT"),
        )
        assertEquals(ScreenRead.POKESTOP, EncounterClassifier.classify("This PokéStop is too far away"))
        assertEquals(ScreenRead.BLOCKED, EncounterClassifier.classify("Team GO Rocket Grunt BATTLE"))
        assertEquals(ScreenRead.BLOCKED, EncounterClassifier.classify("This Gym is under attack"))
        assertEquals(ScreenRead.BLOCKED, EncounterClassifier.classify("You have no Pokémon ready to battle."))
        assertEquals(ScreenRead.BLOCKED, EncounterClassifier.classify("BATTLE using a Remote Raid Pass PRIVATE GROUP"))
        assertEquals(ScreenRead.UNKNOWN, EncounterClassifier.classify("nearby gym photo"))
        assertEquals(ScreenRead.UNKNOWN, EncounterClassifier.classify("CP 412"))
        assertEquals(ScreenRead.CAUGHT, EncounterClassifier.classify("Transferred IV48 Zigzagoon", afterThrow = true))
        assertEquals(ScreenRead.UNKNOWN, EncounterClassifier.classify("Transferred IV48 Zigzagoon"))
        assertEquals(ScreenRead.UNKNOWN, EncounterClassifier.classify(""))
        assertEquals(ScreenRead.UNKNOWN, EncounterClassifier.classify("Excellent!"))
        assertEquals(
            ScreenRead.WILD,
            EncounterClassifier.classify("Razz Nanab Pinap Silver Pinap L2 IV40 1/4/13 O Meowth / CP 26 182"),
        )
        assertEquals(ScreenRead.UNKNOWN, EncounterClassifier.classify("+5 Revive +3 Pinap Berry +10 Ultra Ball"))
        assertEquals(ScreenRead.UNKNOWN, EncounterClassifier.classify("Pinap Berry"))
    }
}
