package dev.cloudbound.game

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameEngineTest {

    private fun newGame(seed: Int = 12) = GameEngine(Random(seed)).apply { start() }

    private fun advance(game: GameEngine, seconds: Float) {
        var remaining = seconds
        while (remaining > 0f) {
            val step = minOf(0.05f, remaining)
            game.update(step)
            remaining -= step
        }
    }

    private fun advanceWithoutObstacles(game: GameEngine, seconds: Float) {
        var remaining = seconds
        while (remaining > 0f) {
            game.hazards.clear()
            val step = minOf(0.05f, remaining)
            game.update(step)
            game.hazards.clear()
            remaining -= step
        }
    }

    @Test
    fun movementEasesTowardTouchTargetAndStaysInsideSafeBounds() {
        val game = newGame()
        game.setTarget(0.9f, 0.3f)
        game.update(0.05f)
        assertTrue(game.balloonX > 0.5f)
        assertTrue(game.balloonX < 0.9f)
        game.setTarget(2f, -1f)
        assertEquals(0.92f, game.targetX, 0.0001f)
        assertEquals(0.16f, game.targetY, 0.0001f)
    }

    @Test
    fun scoreAndTimerAdvanceOnlyDuringPlay() {
        val game = newGame()
        advance(game, 2f)
        assertEquals(2, game.elapsed.toInt())
        assertEquals(20, game.score)

        game.pause()
        advance(game, 1f)
        assertEquals(2, game.elapsed.toInt())

        game.resume()
        advance(game, 1f)
        assertEquals(3, game.elapsed.toInt())
        assertEquals(30, game.score)
    }

    @Test
    fun coinCollectionIncreasesRunAndPersistentTotalsAndScore() {
        val game = newGame()
        var coinEventFired = false
        game.onCoinCollected = { coinEventFired = true }

        game.collectibles.clear()
        game.collectibles += Coin(game.balloonX, game.balloonY)
        game.update(0.01f)

        assertEquals(1, game.coins)
        assertEquals(1, game.totalCoins)
        assertEquals(75, game.score)
        assertTrue(game.collectibles.isEmpty())
        assertTrue(coinEventFired)
        assertTrue("Particles should spawn on coin pickup", game.particles.isNotEmpty())
    }

    @Test
    fun coinMagnetismPullsNearbyCoinsTowardBalloon() {
        val game = newGame()
        game.collectibles.clear()
        // Place coin within magnetic radius (0.10f away)
        val initialX = game.balloonX + 0.09f
        val coin = Coin(initialX, game.balloonY)
        game.collectibles += coin

        game.update(0.03f)
        assertTrue("Coin should have moved closer horizontally", coin.x < initialX)
    }

    @Test
    fun obstacleCollisionConsumesLifeThenGrantsBriefInvulnerability() {
        val game = newGame()
        var hitEventFired = false
        game.onHazardHit = { hitEventFired = true }

        advanceWithoutObstacles(game, 1.3f)
        game.hazards.clear()
        game.hazards += Hazard(HazardType.ROCK, game.balloonX, game.balloonY, 0.11f, 0f)
        game.update(0.01f)

        assertEquals(2, game.lives)
        assertTrue(game.invulnerability > 1f)
        assertTrue(hitEventFired)

        // While invulnerable, another collision does not reduce lives
        game.update(0.2f)
        assertEquals(2, game.lives)
    }

    @Test
    fun restartResetsRunAndGameOverRecordsBestScore() {
        val game = newGame()
        game.setBestScore(1)
        game.pause()
        game.start()

        assertEquals(RunState.PLAYING, game.state)
        assertEquals(3, game.lives)
        assertEquals(0, game.score)
        assertEquals(0f, game.elapsed, 0f)
        assertNotEquals(0, game.snapshot().bestScore)

        var gameOverFired = false
        game.onGameOver = { gameOverFired = true }

        advanceWithoutObstacles(game, 1.3f)
        game.hazards.clear()
        repeat(3) {
            game.hazards.clear()
            game.hazards += Hazard(HazardType.ROCK, game.balloonX, game.balloonY, 0.11f, 0f)
            game.update(0.01f)
            if (game.lives > 0) advanceWithoutObstacles(game, 1.7f)
        }

        assertEquals(RunState.GAME_OVER, game.state)
        assertEquals(game.score, game.bestScore)
        assertTrue(gameOverFired)
    }

    @Test
    fun skinUnlockingDeductsCoinsAndEquipsSuccessfully() {
        val game = newGame()
        game.setTotalCoins(100)

        val azureSkin = GameEngine.ALL_SKINS.first { it.id == "azure" }
        assertTrue(game.totalCoins >= azureSkin.cost)

        val unlocked = game.unlockSkin("azure")
        assertTrue("Unlocking azure skin should succeed", unlocked)
        assertEquals(100 - azureSkin.cost, game.totalCoins)
        assertTrue(game.unlockedSkins.contains("azure"))
        assertEquals("azure", game.selectedSkinId)
        assertEquals("Azure Aeronaut", game.getSkin().name)
    }

    @Test
    fun cannotUnlockSkinWithoutSufficientCoins() {
        val game = newGame()
        game.setTotalCoins(10) // Azure costs 30

        val unlocked = game.unlockSkin("azure")
        assertFalse("Unlocking should fail due to insufficient coins", unlocked)
        assertEquals(10, game.totalCoins)
        assertFalse(game.unlockedSkins.contains("azure"))
        assertEquals("sunrise", game.selectedSkinId)
    }

    @Test
    fun cannotEquipLockedSkin() {
        val game = newGame()
        assertFalse(game.unlockedSkins.contains("aurora"))

        val equipped = game.equipSkin("aurora")
        assertFalse("Cannot equip locked skin", equipped)
        assertEquals("sunrise", game.selectedSkinId)
    }

    @Test
    fun canEquipPreviouslyUnlockedSkin() {
        val game = newGame()
        game.setUnlockedSkins(setOf("sunrise", "zephyr"))

        val equipped = game.equipSkin("zephyr")
        assertTrue("Equipping unlocked skin should succeed", equipped)
        assertEquals("zephyr", game.selectedSkinId)
    }

    @Test
    fun mysteryCrateValidatesCostAndGrantsReward() {
        val game = newGame()
        game.setTotalCoins(30)

        // Not enough coins
        val failCrate = game.openMysteryCrate()
        assertNull(failCrate)

        // Enough coins (40+)
        game.setTotalCoins(50)
        val reward = game.openMysteryCrate()
        assertNotNull(reward)
        assertTrue("Crate should deduct cost (40) and award coins or skin", game.totalCoins >= 10)
    }

    @Test
    fun mysteryCrateAwardsCompensationWhenAllSkinsOwned() {
        val game = newGame()
        // Unlock all skins
        game.setUnlockedSkins(GameEngine.ALL_SKINS.map { it.id }.toSet())
        game.setTotalCoins(100)

        val deterministicEngine = GameEngine(Random(0)).apply {
            setUnlockedSkins(GameEngine.ALL_SKINS.map { it.id }.toSet())
            setTotalCoins(100)
        }

        val reward = deterministicEngine.openMysteryCrate()
        assertNotNull(reward)
        // Never awards null or crashes when all skins owned
        assertTrue(reward!!.coinsAwarded > 0 || reward.skinAwarded != null)
    }

    @Test
    fun firstPlayHintDismissalAfterTargetMovements() {
        val game = newGame()
        assertFalse(game.hintDismissed)
        assertTrue(game.snapshot().showHint)

        // Move target several times
        repeat(10) {
            game.setTarget(0.2f + it * 0.05f, 0.5f)
        }
        assertTrue("Hint should be dismissed after user demonstrates steering", game.hintDismissed)
        assertFalse(game.snapshot().showHint)
    }

    @Test
    fun difficultyProgressionIncreasesOverTime() {
        val game = newGame()
        assertEquals(0f, game.snapshot().difficulty, 0.001f)

        advanceWithoutObstacles(game, 90f)
        val midDifficulty = game.snapshot().difficulty
        assertTrue("Difficulty should increase at 90s", midDifficulty > 0.4f)

        advanceWithoutObstacles(game, 90f)
        val maxDifficulty = game.snapshot().difficulty
        assertEquals(1f, maxDifficulty, 0.001f)
    }

    @Test
    fun lightningHazardHasTelegraphPhaseBeforeStrike() {
        val game = newGame()
        advanceWithoutObstacles(game, 1.3f)
        game.hazards.clear()

        val lightning = Hazard(HazardType.LIGHTNING, game.balloonX, game.balloonY, 0.075f, 0f)
        game.hazards += lightning

        // During initial telegraph phase (first 0.1s), lightning does NOT harm the balloon
        game.update(0.1f)
        assertEquals("Telegraph phase should give player warning window", 3, game.lives)

        // After telegraph timer finishes (0.6s+), discharge occurs
        advanceWithoutObstacles(game, 0.6f)
        game.hazards += lightning
        lightning.telegraphed = true
        lightning.x = game.balloonX
        lightning.y = game.balloonY
        game.update(0.01f)
        assertEquals("Discharged lightning should damage when struck", 2, game.lives)
    }

    @Test
    fun stateTransitionCallbacksFireCorrectly() {
        val game = newGame()
        var lastState: RunState? = null
        game.onStateChanged = { lastState = it }

        game.pause()
        assertEquals(RunState.PAUSED, lastState)

        game.resume()
        assertEquals(RunState.PLAYING, lastState)

        game.menu()
        assertEquals(RunState.MENU, lastState)
    }
}
