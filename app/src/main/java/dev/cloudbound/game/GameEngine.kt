package dev.cloudbound.game

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class RunState { MENU, PLAYING, PAUSED, GAME_OVER }
enum class HazardType { BIRD, STORM, ROCK, LIGHTNING, PASSAGE, GUST }
enum class SkinPattern { STRIPES, AZURE, SUNBURST, STORM, AURORA }

data class BalloonSkin(
    val id: String,
    val name: String,
    val description: String,
    val cost: Int,
    val primaryColor: Int,
    val secondaryColor: Int,
    val accentColor: Int,
    val basketColor: Int,
    val pattern: SkinPattern = SkinPattern.STRIPES
)

data class MysteryReward(
    val coinsAwarded: Int,
    val skinAwarded: BalloonSkin?,
    val message: String
)

data class Hazard(
    val type: HazardType,
    var x: Float,
    var y: Float,
    val radius: Float,
    val seed: Float,
    var stateTime: Float = 0f,
    var telegraphed: Boolean = false
)

data class Coin(
    var x: Float,
    var y: Float,
    var collected: Boolean = false,
    val phase: Float = 0f
)

data class Puff(
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var life: Float,
    val maxLife: Float = life,
    val color: Int,
    val sizeScale: Float = 1f
)

data class GameSnapshot(
    val state: RunState = RunState.MENU,
    val score: Int = 0,
    val seconds: Int = 0,
    val coins: Int = 0,
    val lives: Int = 3,
    val bestScore: Int = 0,
    val totalCoins: Int = 0,
    val invulnerable: Boolean = false,
    val balloonX: Float = 0.5f,
    val balloonY: Float = 0.57f,
    val selectedSkinId: String = "sunrise",
    val showHint: Boolean = false,
    val difficulty: Float = 0f
)

/**
 * Core game engine for CloudBound.
 * Manages physics, procedural obstacle patterns, fair coin placement,
 * balloon skins, progression shop, and game lifecycle.
 */
class GameEngine(private val random: Random = Random.Default) {

    companion object {
        val ALL_SKINS = listOf(
            BalloonSkin(
                id = "sunrise",
                name = "Sunrise Classic",
                description = "The trusty original balloon for tranquil morning flights.",
                cost = 0,
                primaryColor = 0xffe06443.toInt(), // Coral
                secondaryColor = 0xfff39c38.toInt(), // Amber
                accentColor = 0xfffbd665.toInt(), // Gold
                basketColor = 0xffb76b42.toInt(),
                pattern = SkinPattern.STRIPES
            ),
            BalloonSkin(
                id = "azure",
                name = "Azure Aeronaut",
                description = "Lightweight cyan silk engineered to glide on jet streams.",
                cost = 30,
                primaryColor = 0xff287ea6.toInt(), // Deep sky
                secondaryColor = 0xff53bce4.toInt(), // Cyan
                accentColor = 0xffffffff.toInt(), // White star
                basketColor = 0xff486470.toInt(),
                pattern = SkinPattern.AZURE
            ),
            BalloonSkin(
                id = "zephyr",
                name = "Golden Zephyr",
                description = "Gilded canopy shimmering with sunlight and warm thermal drafts.",
                cost = 75,
                primaryColor = 0xffd99a18.toInt(), // Gold
                secondaryColor = 0xfff6cf55.toInt(), // Bright yellow
                accentColor = 0xfffff5c2.toInt(), // Ivory
                basketColor = 0xff8c5d2e.toInt(),
                pattern = SkinPattern.SUNBURST
            ),
            BalloonSkin(
                id = "storm",
                name = "Storm Chaser",
                description = "Charged charcoal canvas woven to dance right through thunderheads.",
                cost = 150,
                primaryColor = 0xff3b3b4d.toInt(), // Charcoal
                secondaryColor = 0xff7e57c2.toInt(), // Electric purple
                accentColor = 0xff4dd0e1.toInt(), // Cyan lightning
                basketColor = 0xff262633.toInt(),
                pattern = SkinPattern.STORM
            ),
            BalloonSkin(
                id = "aurora",
                name = "Aurora Dream",
                description = "Luminescent iridescent weave reflecting northern atmospheric glows.",
                cost = 250,
                primaryColor = 0xff1b695e.toInt(), // Emerald deep
                secondaryColor = 0xff26a69a.toInt(), // Mint teal
                accentColor = 0xfff06292.toInt(), // Aurora magenta
                basketColor = 0xff2d4f48.toInt(),
                pattern = SkinPattern.AURORA
            )
        )
    }

    var state = RunState.MENU
        private set
    var balloonX = 0.5f
        private set
    var balloonY = 0.60f
        private set
    var targetX = balloonX
        private set
    var targetY = balloonY
        private set
    var score = 0
        private set
    var coins = 0
        private set
    var totalCoins = 0
        private set
    var bestScore = 0
        private set
    var lives = 3
        private set
    var elapsed = 0.0
        private set
    var worldDistance = 0f
        private set
    var invulnerability = 0f
        private set
    var tiltEnabled = false
    var tiltInput = 0f

    val hazards = mutableListOf<Hazard>()
    val collectibles = mutableListOf<Coin>()
    val particles = mutableListOf<Puff>()

    var selectedSkinId = "sunrise"
        private set
    val unlockedSkins = mutableSetOf("sunrise")

    var hintDismissed = false
    private var hintTimer = 4f
    private var flameParticleTimer = 0f

    // Event hooks for audio and haptics
    var onCoinCollected: (() -> Unit)? = null
    var onHazardHit: (() -> Unit)? = null
    var onGameOver: (() -> Unit)? = null
    var onStateChanged: ((RunState) -> Unit)? = null

    private var spawnTimer = 0f
    private var xScale = 1f
    private var yScale = 1f

    fun setViewport(width: Int, height: Int) {
        val unit = min(width, height).coerceAtLeast(1).toFloat()
        xScale = width / unit
        yScale = height / unit
    }

    fun setBestScore(value: Int) { bestScore = value.coerceAtLeast(0) }
    fun setTotalCoins(value: Int) { totalCoins = value.coerceAtLeast(0) }

    fun setUnlockedSkins(skinIds: Set<String>) {
        unlockedSkins.clear()
        unlockedSkins.add("sunrise")
        unlockedSkins.addAll(skinIds)
    }

    fun setSelectedSkin(skinId: String) {
        if (unlockedSkins.contains(skinId) && ALL_SKINS.any { it.id == skinId }) {
            selectedSkinId = skinId
        }
    }

    fun getSkin(skinId: String = selectedSkinId): BalloonSkin {
        return ALL_SKINS.find { it.id == skinId } ?: ALL_SKINS[0]
    }

    fun unlockSkin(skinId: String): Boolean {
        val skin = ALL_SKINS.find { it.id == skinId } ?: return false
        if (unlockedSkins.contains(skinId)) return true
        if (totalCoins < skin.cost) return false

        totalCoins -= skin.cost
        unlockedSkins.add(skinId)
        selectedSkinId = skinId
        return true
    }

    fun equipSkin(skinId: String): Boolean {
        if (unlockedSkins.contains(skinId) && ALL_SKINS.any { it.id == skinId }) {
            selectedSkinId = skinId
            return true
        }
        return false
    }

    fun openMysteryCrate(): MysteryReward? {
        val crateCost = 40
        if (totalCoins < crateCost) return null
        totalCoins -= crateCost

        val roll = random.nextFloat()
        return if (roll < 0.15f) {
            // 15% Chance: Unlocked a new skin
            val unowned = ALL_SKINS.filter { !unlockedSkins.contains(it.id) }
            if (unowned.isNotEmpty()) {
                val awarded = unowned.random(random)
                unlockedSkins.add(awarded.id)
                MysteryReward(
                    coinsAwarded = 0,
                    skinAwarded = awarded,
                    message = "Incredible! You discovered the ${awarded.name} skin!"
                )
            } else {
                // If player already owns all skins, award compensation jackpot!
                totalCoins += 100
                MysteryReward(
                    coinsAwarded = 100,
                    skinAwarded = null,
                    message = "All skins already mastered! Awarded 100 Treasures bonus!"
                )
            }
        } else if (roll < 0.40f) {
            // 25% Chance: Lucky Sky Jackpot (75 to 125 coins)
            val bonus = 75 + random.nextInt(51)
            totalCoins += bonus
            MysteryReward(
                coinsAwarded = bonus,
                skinAwarded = null,
                message = "Jackpot! The clouds rained $bonus Treasures upon you!"
            )
        } else {
            // 60% Chance: Standard Pouch (25 to 65 coins)
            val reward = 25 + random.nextInt(41)
            totalCoins += reward
            MysteryReward(
                coinsAwarded = reward,
                skinAwarded = null,
                message = "You retrieved a pouch containing $reward Treasures!"
            )
        }
    }

    fun setTarget(x: Float, y: Float) {
        targetX = x.coerceIn(0.08f, 0.92f)
        targetY = y.coerceIn(0.16f, 0.86f)
        if (!hintDismissed) {
            hintTimer -= 0.5f
            if (hintTimer <= 0f) hintDismissed = true
        }
    }

    fun start() {
        state = RunState.PLAYING
        balloonX = 0.5f
        balloonY = 0.62f
        targetX = balloonX
        targetY = balloonY
        score = 0
        coins = 0
        lives = 3
        elapsed = 0.0
        worldDistance = 0f
        invulnerability = 1.25f
        spawnTimer = 1.0f
        hintTimer = 3.5f
        flameParticleTimer = 0f
        hazards.clear()
        collectibles.clear()
        particles.clear()

        // Gentle initial collectible creates readable first flight milestone
        collectibles += Coin(0.58f, 0.38f, phase = random.nextFloat() * 6f)
        onStateChanged?.invoke(RunState.PLAYING)
    }

    fun pause() {
        if (state == RunState.PLAYING) {
            state = RunState.PAUSED
            onStateChanged?.invoke(RunState.PAUSED)
        }
    }

    fun resume() {
        if (state == RunState.PAUSED) {
            state = RunState.PLAYING
            onStateChanged?.invoke(RunState.PLAYING)
        }
    }

    fun menu() {
        state = RunState.MENU
        onStateChanged?.invoke(RunState.MENU)
    }

    fun update(deltaSeconds: Float) {
        if (state != RunState.PLAYING) return
        val dt = deltaSeconds.coerceIn(0f, 0.05f)
        elapsed += dt
        worldDistance += (0.15f + min(0.22f, (elapsed * 0.0010).toFloat())) * dt
        invulnerability = max(0f, invulnerability - dt)

        if (!hintDismissed) {
            hintTimer -= dt
            if (hintTimer <= 0f) hintDismissed = true
        }

        // Steer target smoothly
        val desiredX = if (tiltEnabled) (0.5f + tiltInput * 0.36f) else targetX
        targetX = desiredX.coerceIn(0.08f, 0.92f)
        balloonX += (targetX - balloonX) * min(1f, dt * 8.5f)
        balloonY += (targetY - balloonY) * min(1f, dt * 7.5f)

        // Spawn gentle burner exhaust particles
        flameParticleTimer += dt
        if (flameParticleTimer >= 0.07f) {
            flameParticleTimer = 0f
            val skin = getSkin()
            particles += Puff(
                x = balloonX + (random.nextFloat() - 0.5f) * 0.02f,
                y = balloonY + 0.065f,
                vx = (random.nextFloat() - 0.5f) * 0.02f,
                vy = 0.08f + random.nextFloat() * 0.05f,
                life = 0.35f + random.nextFloat() * 0.2f,
                color = if (random.nextFloat() < 0.6f) 0xfffcd34d.toInt() else skin.accentColor,
                sizeScale = 0.8f
            )
        }

        val difficulty = min(1f, (elapsed / 180.0).toFloat())
        spawnTimer -= dt
        if (spawnTimer <= 0f) {
            spawnPattern(difficulty)
            spawnTimer = max(0.95f, 2.20f - difficulty * 0.9f + random.nextFloat() * 0.45f)
        }

        val riseSpeed = 0.23f + difficulty * 0.13f
        hazards.forEach { hazard ->
            hazard.stateTime += dt
            hazard.y -= riseSpeed * dt
            when (hazard.type) {
                HazardType.BIRD -> {
                    hazard.x += sin(elapsed.toFloat() * 2.8f + hazard.seed) * dt * 0.14f
                }
                HazardType.LIGHTNING -> {
                    // Telegraph warning phase before full discharge
                    if (hazard.stateTime > 0.6f) hazard.telegraphed = true
                }
                HazardType.GUST -> {
                    hazard.x += dt * 0.22f
                }
                else -> Unit
            }
        }

        // Collectibles move and have gentle magnetic attraction to balloon
        collectibles.forEach { coin ->
            coin.y -= riseSpeed * dt
            val dx = balloonX - coin.x
            val dy = balloonY - coin.y
            val dist = sqrt(dx * dx + dy * dy)
            // Magnetic attraction within proximity
            if (dist < 0.13f && dist > 0.001f) {
                val pull = (1f - dist / 0.13f) * dt * 0.9f
                coin.x += (dx / dist) * pull
                coin.y += (dy / dist) * pull
            }
        }

        hazards.removeAll { it.y < -0.3f || it.x > 1.3f || it.x < -0.3f }

        // Particles update
        particles.forEach { p ->
            p.life -= dt
            p.x += p.vx * dt
            p.y += p.vy * dt - dt * 0.08f
        }
        particles.removeAll { p -> p.life <= 0f }

        checkCollisions()

        // Collected coins are reaped here so they leave the world in the same frame
        collectibles.removeAll { it.y < -0.2f || it.collected }

        score = (elapsed * 10.0 + coins * 75).toInt()
        if (state == RunState.GAME_OVER) {
            bestScore = max(bestScore, score)
        }
    }

    private fun spawnPattern(difficulty: Float) {
        val lane = (0.18f + random.nextFloat() * 0.64f)
        val y = 1.18f
        val roll = random.nextInt(100)

        val kind = when {
            roll < 22 -> HazardType.BIRD
            roll < 44 -> HazardType.STORM
            roll < 66 -> HazardType.ROCK
            roll < 80 -> HazardType.LIGHTNING
            roll < 93 -> HazardType.PASSAGE
            else -> HazardType.GUST
        }

        when (kind) {
            HazardType.PASSAGE -> {
                val gapCenter = lane
                // Generous passage shoulder
                hazards += Hazard(HazardType.ROCK, gapCenter - 0.28f, y, 0.11f, random.nextFloat())
                hazards += Hazard(HazardType.ROCK, gapCenter + 0.28f, y, 0.11f, random.nextFloat())
                // Always place reward in middle of passage!
                collectibles += Coin(gapCenter, y + 0.02f, phase = random.nextFloat() * 6f)
            }
            HazardType.GUST -> {
                hazards += Hazard(HazardType.GUST, 0.05f, y, 0.15f, random.nextFloat())
                collectibles += Coin(0.45f + random.nextFloat() * 0.25f, y + 0.15f, phase = random.nextFloat() * 6f)
            }
            HazardType.LIGHTNING -> {
                hazards += Hazard(HazardType.LIGHTNING, lane, y, 0.075f, random.nextFloat())
                // Ensure adjacent safe lane has a coin
                val safeX = if (lane > 0.5f) lane - 0.32f else lane + 0.32f
                collectibles += Coin(safeX.coerceIn(0.12f, 0.88f), y + 0.18f, phase = random.nextFloat() * 6f)
            }
            else -> {
                val radius = when (kind) {
                    HazardType.STORM -> 0.125f
                    HazardType.ROCK -> 0.11f
                    else -> 0.085f
                }
                hazards += Hazard(kind, lane, y, radius, random.nextFloat())
                // Fair coin placement away from obstacle lane
                if (random.nextFloat() < 0.75f) {
                    val offset = if (lane < 0.5f) 0.26f else -0.26f
                    collectibles += Coin((lane + offset).coerceIn(0.12f, 0.88f), y + 0.20f, phase = random.nextFloat() * 6f)
                }
            }
        }
    }

    private fun checkCollisions() {
        val bx = balloonX
        val by = balloonY

        // Collectibles check
        collectibles.forEach { coin ->
            if (coin.collected) return@forEach
            val dx = (coin.x - bx) * xScale
            val dy = (coin.y - by) * yScale
            if (dx * dx + dy * dy < 0.065f * 0.065f) {
                coin.collected = true
                coins++
                totalCoins++
                onCoinCollected?.invoke()

                // Particle burst on coin collection
                val skin = getSkin()
                repeat(8) {
                    val angle = random.nextFloat() * 2f * Math.PI.toFloat()
                    val speed = 0.08f + random.nextFloat() * 0.12f
                    particles += Puff(
                        x = coin.x,
                        y = coin.y,
                        vx = kotlin.math.cos(angle) * speed,
                        vy = kotlin.math.sin(angle) * speed,
                        life = 0.45f + random.nextFloat() * 0.3f,
                        color = if (random.nextFloat() < 0.7f) 0xffffd16c.toInt() else skin.accentColor,
                        sizeScale = 1.2f
                    )
                }
            }
        }

        if (invulnerability > 0f) return

        val collision = hazards.any { hazard ->
            val dx = abs(hazard.x - bx) * xScale
            val dy = abs(hazard.y - by) * yScale
            when (hazard.type) {
                HazardType.LIGHTNING -> {
                    // Only damages once fully discharged after telegraph period
                    hazard.telegraphed && dx < 0.052f && dy < 0.18f
                }
                HazardType.STORM -> dx < 0.10f && dy < 0.09f
                HazardType.PASSAGE -> dx < 0.07f && dy < 0.095f
                HazardType.GUST -> false // Gust gently nudges rather than damages
                else -> dx * dx + dy * dy < (hazard.radius + 0.042f) * (hazard.radius + 0.042f)
            }
        }

        if (collision) {
            lives--
            invulnerability = 1.6f
            onHazardHit?.invoke()

            // Impact debris particles
            repeat(14) {
                val angle = random.nextFloat() * 2f * Math.PI.toFloat()
                val speed = 0.10f + random.nextFloat() * 0.18f
                particles += Puff(
                    x = bx,
                    y = by,
                    vx = kotlin.math.cos(angle) * speed,
                    vy = kotlin.math.sin(angle) * speed,
                    life = 0.4f + random.nextFloat() * 0.4f,
                    color = 0xffff7a59.toInt(),
                    sizeScale = 1.4f
                )
            }

            if (lives <= 0) {
                lives = 0
                state = RunState.GAME_OVER
                score = (elapsed * 10.0 + coins * 75).toInt()
                bestScore = max(bestScore, score)
                onGameOver?.invoke()
                onStateChanged?.invoke(RunState.GAME_OVER)
            }
        }
    }

    fun snapshot(): GameSnapshot = GameSnapshot(
        state = state,
        score = score,
        seconds = elapsed.toInt(),
        coins = coins,
        lives = lives,
        bestScore = bestScore,
        totalCoins = totalCoins,
        invulnerable = invulnerability > 0f,
        balloonX = balloonX,
        balloonY = balloonY,
        selectedSkinId = selectedSkinId,
        showHint = !hintDismissed && state == RunState.PLAYING,
        difficulty = min(1f, (elapsed / 180.0).toFloat())
    )
}
