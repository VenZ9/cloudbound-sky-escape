package dev.cloudbound.game

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private val Ink = Color(0xFF132B37)
private val Cream = Color(0xFFFFF9ED)
private val Coral = Color(0xFFE06443)
private val Gold = Color(0xFFF59E0B)
private val Mint = Color(0xFF4E8E6B)
private val CardBg = Color(0xFFF7F2E7)

enum class ScreenView { MAIN_MENU, FLIGHT, SHOP, SETTINGS, HOW_TO_PLAY }

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var audioEngine: AudioEngine
    private lateinit var hapticEngine: HapticEngine
    private lateinit var engine: GameEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("cloudbound_progress", MODE_PRIVATE)
        audioEngine = AudioEngine().apply {
            soundEnabled = prefs.getBoolean("sound", true)
            musicEnabled = prefs.getBoolean("music", true)
        }
        hapticEngine = HapticEngine(this).apply {
            hapticsEnabled = prefs.getBoolean("haptics", true)
        }

        engine = GameEngine().apply {
            setBestScore(prefs.getInt("best_score", 0))
            setTotalCoins(prefs.getInt("total_coins", 0))
            setUnlockedSkins(prefs.getStringSet("unlocked_skins", setOf("sunrise")) ?: setOf("sunrise"))
            setSelectedSkin(prefs.getString("selected_skin", "sunrise") ?: "sunrise")
        }

        val tiltAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER)

        setContent {
            MaterialTheme {
                CloudBoundRoot(
                    engine = engine,
                    audioEngine = audioEngine,
                    hapticEngine = hapticEngine,
                    tiltAvailable = tiltAvailable,
                    prefs = prefs
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioEngine.release()
    }
}

@Composable
fun CloudBoundRoot(
    engine: GameEngine,
    audioEngine: AudioEngine,
    hapticEngine: HapticEngine,
    tiltAvailable: Boolean,
    prefs: SharedPreferences
) {
    var snapshot by remember { mutableStateOf(engine.snapshot()) }
    var currentScreen by remember { mutableStateOf(ScreenView.MAIN_MENU) }
    var tilt by remember { mutableStateOf(prefs.getBoolean("tilt", false) && tiltAvailable) }
    var sound by remember { mutableStateOf(audioEngine.soundEnabled) }
    var music by remember { mutableStateOf(audioEngine.musicEnabled) }
    var haptics by remember { mutableStateOf(hapticEngine.hapticsEnabled) }
    var totalFlights by remember { mutableStateOf(prefs.getInt("total_flights", 0)) }
    var activeCrateReward by remember { mutableStateOf<MysteryReward?>(null) }
    var showResetConfirmation by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val gameView = remember(context) {
        CloudGameView(context, engine, audioEngine, hapticEngine)
    }

    // Persist helper
    fun persistAll() {
        prefs.edit()
            .putInt("best_score", engine.bestScore)
            .putInt("total_coins", engine.totalCoins)
            .putStringSet("unlocked_skins", engine.unlockedSkins)
            .putString("selected_skin", engine.selectedSkinId)
            .putBoolean("tilt", tilt)
            .putBoolean("sound", sound)
            .putBoolean("music", music)
            .putBoolean("haptics", haptics)
            .putInt("total_flights", totalFlights)
            .apply()
    }

    gameView.setTiltEnabled(tilt)
    gameView.onSnapshot = { s ->
        snapshot = s
        val persistedCoins = prefs.getInt("total_coins", 0)
        val persistedBest = prefs.getInt("best_score", 0)
        if (s.totalCoins != persistedCoins || s.bestScore != persistedBest) {
            persistAll()
        }
    }

    DisposableEffect(lifecycleOwner, gameView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    gameView.pauseForLifecycle()
                    audioEngine.onAppPause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    audioEngine.onAppResume()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF8BCFE0))) {
        // Native Android Canvas game view
        AndroidView(
            factory = { gameView },
            modifier = Modifier.fillMaxSize(),
            update = { it.setTiltEnabled(tilt) }
        )

        // Overlay Navigation & HUD
        when (snapshot.state) {
            RunState.MENU -> {
                when (currentScreen) {
                    ScreenView.MAIN_MENU -> {
                        MainMenuScreen(
                            snapshot = snapshot,
                            totalFlights = totalFlights,
                            tilt = tilt,
                            tiltAvailable = tiltAvailable,
                            onTiltToggle = {
                                tilt = it
                                gameView.setTiltEnabled(it)
                                persistAll()
                                hapticEngine.vibrateButton()
                                audioEngine.playClick()
                            },
                            onPlay = {
                                totalFlights++
                                persistAll()
                                engine.start()
                                snapshot = engine.snapshot()
                                currentScreen = ScreenView.FLIGHT
                                gameView.invalidate()
                                audioEngine.startMusic()
                                audioEngine.playClick()
                                hapticEngine.vibrateButton()
                            },
                            onOpenShop = {
                                currentScreen = ScreenView.SHOP
                                audioEngine.playClick()
                                hapticEngine.vibrateButton()
                            },
                            onOpenSettings = {
                                currentScreen = ScreenView.SETTINGS
                                audioEngine.playClick()
                                hapticEngine.vibrateButton()
                            },
                            onOpenHowToPlay = {
                                currentScreen = ScreenView.HOW_TO_PLAY
                                audioEngine.playClick()
                                hapticEngine.vibrateButton()
                            }
                        )
                    }
                    ScreenView.SHOP -> {
                        ShopHangarScreen(
                            snapshot = snapshot,
                            engine = engine,
                            onEquip = { skinId ->
                                engine.equipSkin(skinId)
                                snapshot = engine.snapshot()
                                persistAll()
                                audioEngine.playClick()
                                hapticEngine.vibrateButton()
                            },
                            onPurchase = { skinId ->
                                val success = engine.unlockSkin(skinId)
                                if (success) {
                                    snapshot = engine.snapshot()
                                    persistAll()
                                    audioEngine.playUnlock()
                                    hapticEngine.vibrateCoin()
                                }
                            },
                            onOpenCrate = {
                                val reward = engine.openMysteryCrate()
                                if (reward != null) {
                                    activeCrateReward = reward
                                    snapshot = engine.snapshot()
                                    persistAll()
                                    audioEngine.playUnlock()
                                    hapticEngine.vibrateCoin()
                                }
                            },
                            onBack = {
                                currentScreen = ScreenView.MAIN_MENU
                                audioEngine.playClick()
                                hapticEngine.vibrateButton()
                            }
                        )
                    }
                    ScreenView.SETTINGS -> {
                        SettingsScreen(
                            sound = sound,
                            music = music,
                            haptics = haptics,
                            tilt = tilt,
                            tiltAvailable = tiltAvailable,
                            onSoundToggle = {
                                sound = it
                                audioEngine.soundEnabled = it
                                persistAll()
                                hapticEngine.vibrateButton()
                            },
                            onMusicToggle = {
                                music = it
                                audioEngine.musicEnabled = it
                                persistAll()
                                hapticEngine.vibrateButton()
                            },
                            onHapticsToggle = {
                                haptics = it
                                hapticEngine.hapticsEnabled = it
                                persistAll()
                                hapticEngine.vibrateButton()
                            },
                            onTiltToggle = {
                                tilt = it
                                gameView.setTiltEnabled(it)
                                persistAll()
                                hapticEngine.vibrateButton()
                            },
                            onResetRequest = { showResetConfirmation = true },
                            onBack = {
                                currentScreen = ScreenView.MAIN_MENU
                                audioEngine.playClick()
                                hapticEngine.vibrateButton()
                            }
                        )
                    }
                    ScreenView.HOW_TO_PLAY -> {
                        HowToPlayScreen(
                            tiltAvailable = tiltAvailable,
                            onBack = {
                                currentScreen = ScreenView.MAIN_MENU
                                audioEngine.playClick()
                                hapticEngine.vibrateButton()
                            }
                        )
                    }
                    ScreenView.FLIGHT -> {
                        currentScreen = ScreenView.MAIN_MENU
                    }
                }
            }
            RunState.PLAYING -> {
                FlightHud(
                    snapshot = snapshot,
                    onPause = {
                        engine.pause()
                        snapshot = engine.snapshot()
                        gameView.invalidate()
                        audioEngine.pauseMusic()
                        audioEngine.playClick()
                        hapticEngine.vibrateButton()
                    }
                )
            }
            RunState.PAUSED -> {
                PauseScreen(
                    snapshot = snapshot,
                    onResume = {
                        engine.resume()
                        snapshot = engine.snapshot()
                        gameView.invalidate()
                        audioEngine.resumeMusic()
                        audioEngine.playClick()
                        hapticEngine.vibrateButton()
                    },
                    onRestart = {
                        totalFlights++
                        persistAll()
                        engine.start()
                        snapshot = engine.snapshot()
                        gameView.invalidate()
                        audioEngine.startMusic()
                        audioEngine.playClick()
                        hapticEngine.vibrateButton()
                    },
                    onMenu = {
                        engine.menu()
                        currentScreen = ScreenView.MAIN_MENU
                        snapshot = engine.snapshot()
                        gameView.invalidate()
                        audioEngine.pauseMusic()
                        audioEngine.playClick()
                        hapticEngine.vibrateButton()
                    }
                )
            }
            RunState.GAME_OVER -> {
                GameOverScreen(
                    snapshot = snapshot,
                    onRestart = {
                        totalFlights++
                        persistAll()
                        engine.start()
                        snapshot = engine.snapshot()
                        gameView.invalidate()
                        audioEngine.startMusic()
                        audioEngine.playClick()
                        hapticEngine.vibrateButton()
                    },
                    onMenu = {
                        engine.menu()
                        currentScreen = ScreenView.MAIN_MENU
                        snapshot = engine.snapshot()
                        gameView.invalidate()
                        audioEngine.pauseMusic()
                        audioEngine.playClick()
                        hapticEngine.vibrateButton()
                    },
                    onHangar = {
                        engine.menu()
                        currentScreen = ScreenView.SHOP
                        snapshot = engine.snapshot()
                        gameView.invalidate()
                        audioEngine.pauseMusic()
                        audioEngine.playClick()
                        hapticEngine.vibrateButton()
                    }
                )
            }
        }

        // Mystery Crate Result Modal
        activeCrateReward?.let { reward ->
            AlertDialog(
                onDismissRequest = { activeCrateReward = null },
                containerColor = Cream,
                title = {
                    Text(
                        if (reward.skinAwarded != null) "✨ NEW BALLOON UNLOCKED!" else "✦ SKY TREASURES!",
                        color = Ink,
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            reward.message,
                            color = Ink,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(14.dp))
                        if (reward.skinAwarded != null) {
                            SkinPreviewCanvas(reward.skinAwarded, Modifier.size(90.dp))
                        } else {
                            Text("+${reward.coinsAwarded} ✦", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Gold)
                        }
                    }
                },
                confirmButton = {
                    PrimaryButton("CLAIM REWARD", "✓") {
                        activeCrateReward = null
                        audioEngine.playClick()
                        hapticEngine.vibrateButton()
                    }
                }
            )
        }

        // Reset Data Confirmation Modal
        if (showResetConfirmation) {
            AlertDialog(
                onDismissRequest = { showResetConfirmation = false },
                containerColor = Cream,
                title = { Text("RESET PROGRESS?", color = Ink, fontWeight = FontWeight.Black) },
                text = {
                    Text(
                        "This will reset your high score, flight stats, and locked skins. This action cannot be undone.",
                        color = Ink.copy(alpha = 0.8f),
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            prefs.edit().clear().apply()
                            engine.setBestScore(0)
                            engine.setTotalCoins(0)
                            engine.setUnlockedSkins(setOf("sunrise"))
                            engine.setSelectedSkin("sunrise")
                            totalFlights = 0
                            snapshot = engine.snapshot()
                            showResetConfirmation = false
                            audioEngine.playClick()
                            hapticEngine.vibrateHazard()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Coral)
                    ) {
                        Text("RESET EVERYTHING", color = Cream, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirmation = false }) {
                        Text("CANCEL", color = Ink, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}

@Composable
fun MainMenuScreen(
    snapshot: GameSnapshot,
    totalFlights: Int,
    tilt: Boolean,
    tiltAvailable: Boolean,
    onTiltToggle: (Boolean) -> Unit,
    onPlay: () -> Unit,
    onOpenShop: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHowToPlay: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(WindowInsets.safeDrawing.asPaddingValues())
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CloudBoundLogo(Modifier.size(86.dp))
            Spacer(Modifier.height(10.dp))
            Text(
                "CLOUDBOUND",
                color = Ink,
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.5.sp
            )
            Text(
                "A little higher, every day.",
                color = Ink.copy(alpha = 0.75f),
                fontSize = 14.sp
            )

            Spacer(Modifier.height(24.dp))

            // Stats Card
            Surface(
                color = Cream.copy(alpha = 0.90f),
                shape = RoundedCornerShape(22.dp),
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        StatItem("BEST ALTITUDE", snapshot.bestScore.toString().padStart(5, '0'))
                        StatItem("TREASURES", "${snapshot.totalCoins} ✦")
                        StatItem("FLIGHTS", totalFlights.toString())
                    }
                    Spacer(Modifier.height(20.dp))
                    PrimaryButton("TAKE FLIGHT", "↑", onPlay)
                    Spacer(Modifier.height(12.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SecondaryButton("HANGAR", Modifier.weight(1f), onOpenShop)
                        SecondaryButton("SETTINGS", Modifier.weight(1f), onOpenSettings)
                    }

                    Spacer(Modifier.height(14.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            if (tiltAvailable) "Steering: ${if (tilt) "Tilt Sensor" else "Touch Drag"}" else "Touch Drag Steering",
                            color = Ink.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (tiltAvailable) {
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = tilt,
                                onCheckedChange = onTiltToggle,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Cream,
                                    checkedTrackColor = Mint
                                )
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            TextButton(onClick = onOpenHowToPlay) {
                Text("HOW TO PLAY & CONTROLS", color = Ink.copy(alpha = 0.65f), fontSize = 11.sp, letterSpacing = 1.sp)
            }
        }

        Text(
            "CLOUDBOUND AERO ATLAS  ·  EDITION 2.0",
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
            color = Ink.copy(alpha = 0.50f),
            fontSize = 9.sp,
            letterSpacing = 1.8.sp
        )
    }
}

@Composable
fun FlightHud(snapshot: GameSnapshot, onPause: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(WindowInsets.safeDrawing.asPaddingValues())
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                color = Cream.copy(alpha = 0.92f),
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 4.dp
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        "ALTITUDE SCORE",
                        fontSize = 9.sp,
                        color = Ink.copy(alpha = 0.55f),
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        snapshot.score.toString().padStart(5, '0'),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        color = Ink
                    )
                    Text(
                        "${snapshot.seconds / 60}:${(snapshot.seconds % 60).toString().padStart(2, '0')}  ·  ${snapshot.coins} ✦",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink.copy(alpha = 0.72f)
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(3) { i ->
                        Text(
                            text = if (i < snapshot.lives) "♥" else "·",
                            color = if (i < snapshot.lives) Coral else Ink.copy(alpha = 0.3f),
                            fontSize = 24.sp
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onPause,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cream.copy(alpha = 0.85f), contentColor = Ink)
                ) {
                    Text("Ⅱ PAUSE", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // First play control hint banner
        AnimatedVisibility(
            visible = snapshot.showHint,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Surface(
                color = Ink.copy(alpha = 0.65f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    "DRAG ANYWHERE TO STEER  ·  COLLECT ✦ TREASURES",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Cream,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(14.dp))
    }
}

@Composable
fun PauseScreen(
    snapshot: GameSnapshot,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onMenu: () -> Unit
) {
    OverlayCard(
        eyebrow = "A MOMENT TO BREATHE",
        title = "FLIGHT PAUSED",
        subtitle = "Your position in the winds is resting safely."
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatItem("CURRENT ALTITUDE", snapshot.score.toString().padStart(5, '0'))
            StatItem("TREASURES FOUND", "${snapshot.coins} ✦")
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton("RESUME FLIGHT", "▶", onResume)
        Spacer(Modifier.height(10.dp))
        SecondaryButton("START NEW FLIGHT", Modifier.fillMaxWidth(), onRestart)
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onMenu) {
            Text("RETURN TO HANGAR", color = Ink, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun GameOverScreen(
    snapshot: GameSnapshot,
    onRestart: () -> Unit,
    onMenu: () -> Unit,
    onHangar: () -> Unit
) {
    val isNewBest = snapshot.score >= snapshot.bestScore && snapshot.score > 0
    OverlayCard(
        eyebrow = if (isNewBest) "★ NEW FLIGHT RECORD! ★" else "FLIGHT COMPLETE",
        title = "THE SKY CAN WAIT",
        subtitle = "You soared for ${snapshot.seconds / 60}:${(snapshot.seconds % 60).toString().padStart(2, '0')} and retrieved ${snapshot.coins} treasures."
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatItem("FINAL ALTITUDE", snapshot.score.toString().padStart(5, '0'))
            StatItem("BEST FLIGHT", snapshot.bestScore.toString().padStart(5, '0'))
            StatItem("TOTAL TREASURES", "${snapshot.totalCoins} ✦")
        }
        Spacer(Modifier.height(22.dp))
        PrimaryButton("FLY AGAIN", "↻", onRestart)
        Spacer(Modifier.height(10.dp))
        SecondaryButton("VISIT HANGAR SHOP", Modifier.fillMaxWidth(), onHangar)
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onMenu) {
            Text("RETURN TO MAIN MENU", color = Ink, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun ShopHangarScreen(
    snapshot: GameSnapshot,
    engine: GameEngine,
    onEquip: (String) -> Unit,
    onPurchase: (String) -> Unit,
    onOpenCrate: () -> Unit,
    onBack: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(WindowInsets.safeDrawing.asPaddingValues())
            .padding(16.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            // Header bar
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = onBack,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cream, contentColor = Ink)
                ) {
                    Text("← BACK", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Surface(
                    color = Cream,
                    shape = RoundedCornerShape(16.dp),
                    shadowElevation = 2.dp
                ) {
                    Text(
                        "TREASURES: ${snapshot.totalCoins} ✦",
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        color = Ink,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "BALLOON HANGAR",
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                color = Ink
            )
            Text(
                "Unlock hand-crafted aerodynamic canopies using collected treasures.",
                fontSize = 12.sp,
                color = Ink.copy(alpha = 0.7f)
            )

            Spacer(Modifier.height(14.dp))

            // Balloon Skins List
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Sky Mystery Crate Card
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFBF4D8)),
                        shape = RoundedCornerShape(18.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("SKY MYSTERY CRATE", fontWeight = FontWeight.Black, fontSize = 14.sp, color = Ink)
                                    Text("Cost: 40 ✦  ·  Chances: 60% Pouch, 25% Jackpot, 15% Rare Skin", fontSize = 11.sp, color = Ink.copy(0.7f))
                                }
                                Button(
                                    onClick = onOpenCrate,
                                    enabled = snapshot.totalCoins >= 40,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)
                                ) {
                                    Text("OPEN (40 ✦)", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                items(GameEngine.ALL_SKINS) { skin ->
                    val isUnlocked = engine.unlockedSkins.contains(skin.id)
                    val isEquipped = snapshot.selectedSkinId == skin.id

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Cream),
                        shape = RoundedCornerShape(18.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SkinPreviewCanvas(skin, Modifier.size(64.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(skin.name, fontWeight = FontWeight.Black, fontSize = 15.sp, color = Ink)
                                Text(skin.description, fontSize = 11.sp, color = Ink.copy(alpha = 0.68f))
                                Spacer(Modifier.height(4.dp))
                                if (!isUnlocked) {
                                    Text("Cost: ${skin.cost} ✦", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Gold)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            when {
                                isEquipped -> {
                                    Surface(
                                        color = Mint.copy(alpha = 0.18f),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text(
                                            "EQUIPPED",
                                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            color = Mint,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                                isUnlocked -> {
                                    Button(
                                        onClick = { onEquip(skin.id) },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD6EDE0), contentColor = Ink)
                                    ) {
                                        Text("EQUIP", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }
                                else -> {
                                    Button(
                                        onClick = { onPurchase(skin.id) },
                                        enabled = snapshot.totalCoins >= skin.cost,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Cream)
                                    ) {
                                        Text("UNLOCK", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    sound: Boolean,
    music: Boolean,
    haptics: Boolean,
    tilt: Boolean,
    tiltAvailable: Boolean,
    onSoundToggle: (Boolean) -> Unit,
    onMusicToggle: (Boolean) -> Unit,
    onHapticsToggle: (Boolean) -> Unit,
    onTiltToggle: (Boolean) -> Unit,
    onResetRequest: () -> Unit,
    onBack: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(WindowInsets.safeDrawing.asPaddingValues())
            .padding(18.dp)
    ) {
        Surface(
            color = Cream,
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth().align(Alignment.Center)
        ) {
            Column(Modifier.padding(22.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("SETTINGS", fontWeight = FontWeight.Black, fontSize = 22.sp, color = Ink)
                    TextButton(onClick = onBack) {
                        Text("DONE", fontWeight = FontWeight.Bold, color = Ink)
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Ink.copy(alpha = 0.12f))

                SettingRow("Sound Effects", "Chimes and collision feedback", sound, onSoundToggle)
                SettingRow("Ambient Background Music", "Calm procedural wind melodies", music, onMusicToggle)
                SettingRow("Haptic Feedback", "Vibrations for coins and collisions", haptics, onHapticsToggle)

                SettingRow(
                    title = if (tiltAvailable) "Tilt Accelerometer Controls" else "Tilt Sensor (Hardware Unavailable)",
                    subtitle = if (tiltAvailable) "Tilt phone to steer instead of dragging" else "Your device has no accelerometer",
                    checked = tilt && tiltAvailable,
                    enabled = tiltAvailable,
                    onCheckedChange = onTiltToggle
                )

                HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Ink.copy(alpha = 0.12f))

                Button(
                    onClick = onResetRequest,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFBE4DC), contentColor = Coral)
                ) {
                    Text("RESET PROGRESS DATA", fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp)
                }
            }
        }
    }
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Ink)
            Text(subtitle, fontSize = 11.sp, color = Ink.copy(alpha = 0.65f))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Cream,
                checkedTrackColor = Mint
            )
        )
    }
}

@Composable
fun HowToPlayScreen(tiltAvailable: Boolean, onBack: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(WindowInsets.safeDrawing.asPaddingValues())
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Cream,
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("HOW TO PLAY", fontWeight = FontWeight.Black, fontSize = 22.sp, color = Ink, letterSpacing = 2.sp)
                Spacer(Modifier.height(14.dp))
                InstructionItem("1. STEERING", "Drag anywhere on the screen to smoothly guide the balloon. Finger offset keeps your view completely clear.")
                if (tiltAvailable) {
                    InstructionItem("2. TILT OPTION", "You can switch on Accelerometer Tilt controls in Settings to steer by gently tilting your phone.")
                }
                InstructionItem("3. COLLECT TREASURES", "Gather floating ✦ coins to increase your run score and unlock new balloon skins in the Hangar.")
                InstructionItem("4. AVOID HAZARDS", "Dodge birds, floating rocks, and storm clouds. Lightning telegraphed warning beams give you time to reposition.")
                InstructionItem("5. PASSAGES", "Navigate between floating rock corridors to discover rich treasure clusters in the middle.")
                Spacer(Modifier.height(20.dp))
                PrimaryButton("UNDERSTOOD", "✓", onBack)
            }
        }
    }
}

@Composable
fun InstructionItem(title: String, desc: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Coral, letterSpacing = 1.sp)
        Text(desc, fontSize = 12.sp, color = Ink.copy(alpha = 0.75f), lineHeight = 16.sp)
    }
}

@Composable
fun OverlayCard(
    eyebrow: String,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x770E2834))
            .padding(WindowInsets.safeDrawing.asPaddingValues())
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Cream,
            shape = RoundedCornerShape(28.dp),
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    eyebrow,
                    fontSize = 11.sp,
                    letterSpacing = 1.8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Coral
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    title,
                    fontSize = 26.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    color = Ink
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    subtitle,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    color = Ink.copy(alpha = 0.68f)
                )
                Spacer(Modifier.height(22.dp))
                content()
            }
        }
    }
}

@Composable
fun PrimaryButton(label: String, icon: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Cream)
    ) {
        Text(icon, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Text(label, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
fun SecondaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE2EEE5), contentColor = Ink)
    ) {
        Text(label, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            fontSize = 9.sp,
            letterSpacing = 1.15.sp,
            color = Ink.copy(alpha = 0.58f),
            fontWeight = FontWeight.Bold
        )
        Text(
            value,
            fontSize = 19.sp,
            color = Ink,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
fun CloudBoundLogo(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawCircle(Color(0x227DABAD), radius = size.minDimension * 0.48f)

        // Hot air balloon icon
        drawOval(
            Color(0xFFE06443),
            topLeft = Offset(cx - size.width * 0.28f, size.height * 0.08f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.56f, size.height * 0.60f)
        )
        drawArc(
            Color(0xFFF39C38),
            15f,
            150f,
            false,
            Offset(cx - size.width * 0.28f, size.height * 0.08f),
            androidx.compose.ui.geometry.Size(size.width * 0.56f, size.height * 0.60f),
            style = Stroke(width = size.width * 0.22f, cap = StrokeCap.Butt)
        )
        drawOval(
            Color(0xFFFBD665),
            topLeft = Offset(cx - size.width * 0.10f, size.height * 0.12f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.20f, size.height * 0.52f)
        )

        // Ropes & Basket
        drawLine(
            Color(0xFF733930),
            Offset(cx - size.width * 0.14f, size.height * 0.64f),
            Offset(cx - size.width * 0.08f, size.height * 0.80f),
            strokeWidth = 2.5f
        )
        drawLine(
            Color(0xFF733930),
            Offset(cx + size.width * 0.14f, size.height * 0.64f),
            Offset(cx + size.width * 0.08f, size.height * 0.80f),
            strokeWidth = 2.5f
        )
        drawRoundRect(
            Color(0xFFB76B42),
            Offset(cx - size.width * 0.12f, size.height * 0.78f),
            androidx.compose.ui.geometry.Size(size.width * 0.24f, size.height * 0.15f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f)
        )
    }
}

@Composable
fun SkinPreviewCanvas(skin: BalloonSkin, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height * 0.42f
        val w = size.width * 0.60f
        val h = size.height * 0.66f

        drawCircle(Color(0x18000000), radius = size.minDimension * 0.48f)

        // Rigging
        drawLine(
            Color(skin.basketColor),
            Offset(cx - w * 0.32f, cy + h * 0.40f),
            Offset(cx - w * 0.14f, size.height * 0.82f),
            strokeWidth = 2f
        )
        drawLine(
            Color(skin.basketColor),
            Offset(cx + w * 0.32f, cy + h * 0.40f),
            Offset(cx + w * 0.14f, size.height * 0.82f),
            strokeWidth = 2f
        )

        // Basket
        drawRoundRect(
            Color(skin.basketColor),
            Offset(cx - w * 0.18f, size.height * 0.80f),
            androidx.compose.ui.geometry.Size(w * 0.36f, size.height * 0.16f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f)
        )

        // Envelope
        drawOval(
            Color(skin.primaryColor),
            topLeft = Offset(cx - w / 2, cy - h / 2),
            size = androidx.compose.ui.geometry.Size(w, h)
        )
        drawOval(
            Color(skin.secondaryColor),
            topLeft = Offset(cx - w * 0.34f, cy - h / 2 + 2f),
            size = androidx.compose.ui.geometry.Size(w * 0.68f, h - 4f)
        )
        drawOval(
            Color(skin.accentColor),
            topLeft = Offset(cx - w * 0.14f, cy - h / 2 + 4f),
            size = androidx.compose.ui.geometry.Size(w * 0.28f, h - 8f)
        )
    }
}
