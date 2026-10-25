package dev.cloudbound.game

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Procedural native audio engine using Android AudioTrack.
 * Synthesizes zero-asset, high-clarity sound effects and ambient atmospheric music
 * without external audio dependencies or licensing issues.
 */
class AudioEngine {
    var soundEnabled: Boolean = true
    var musicEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) {
                pauseMusic()
            } else if (isAppActive && shouldMusicPlay) {
                startMusic()
            }
        }

    private var isAppActive = true
    private var shouldMusicPlay = false

    private val sampleRate = 22050
    private val executor = Executors.newSingleThreadExecutor()

    // Pre-rendered sound effect PCM buffers
    private val coinPcm = generateCoinPcm()
    private val hitPcm = generateHitPcm()
    private val gameOverPcm = generateGameOverPcm()
    private val clickPcm = generateClickPcm()
    private val unlockPcm = generateUnlockPcm()

    private var musicTrack: AudioTrack? = null

    init {
        // Pre-build music track on background thread
        executor.execute {
            try {
                musicTrack = buildAmbientMusicTrack()
            } catch (_: Throwable) {
                musicTrack = null
            }
        }
    }

    fun playCoin() {
        if (!soundEnabled) return
        playStaticPcm(coinPcm)
    }

    fun playHit() {
        if (!soundEnabled) return
        playStaticPcm(hitPcm)
    }

    fun playGameOver() {
        if (!soundEnabled) return
        playStaticPcm(gameOverPcm)
    }

    fun playClick() {
        if (!soundEnabled) return
        playStaticPcm(clickPcm)
    }

    fun playUnlock() {
        if (!soundEnabled) return
        playStaticPcm(unlockPcm)
    }

    fun startMusic() {
        shouldMusicPlay = true
        if (!musicEnabled || !isAppActive) return
        executor.execute {
            try {
                val track = musicTrack ?: buildAmbientMusicTrack().also { musicTrack = it }
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                }
            } catch (_: Throwable) {}
        }
    }

    fun pauseMusic() {
        executor.execute {
            try {
                if (musicTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    musicTrack?.pause()
                }
            } catch (_: Throwable) {}
        }
    }

    fun resumeMusic() {
        if (shouldMusicPlay && musicEnabled && isAppActive) {
            startMusic()
        }
    }

    fun onAppResume() {
        isAppActive = true
        if (shouldMusicPlay && musicEnabled) {
            startMusic()
        }
    }

    fun onAppPause() {
        isAppActive = false
        pauseMusic()
    }

    fun release() {
        isAppActive = false
        shouldMusicPlay = false
        executor.execute {
            try {
                musicTrack?.let {
                    if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        it.stop()
                    }
                    it.release()
                }
                musicTrack = null
            } catch (_: Throwable) {}
        }
        executor.shutdown()
    }

    private fun playStaticPcm(pcm: ShortArray) {
        executor.execute {
            var track: AudioTrack? = null
            try {
                val bufferSize = pcm.size * 2
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(pcm, 0, pcm.size)
                track.play()
                // Auto cleanup when playback ends
                Thread.sleep((pcm.size * 1000L / sampleRate) + 60L)
            } catch (_: Throwable) {
            } finally {
                try {
                    track?.let {
                        if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop()
                        it.release()
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    private fun buildAmbientMusicTrack(): AudioTrack {
        val durationSeconds = 10
        val totalSamples = sampleRate * durationSeconds
        val pcm = ShortArray(totalSamples)

        // Gentle, soothing pentatonic chords: Cmaj9 -> Fmaj9 -> Am7 -> Gsus4
        // Base frequencies: C4 (261.63), E4 (329.63), G4 (392.00), B4 (493.88), D5 (587.33)
        val chordTimes = doubleArrayOf(0.0, 2.5, 5.0, 7.5)
        val chords = arrayOf(
            doubleArrayOf(261.63, 329.63, 392.00, 493.88),       // Cmaj7
            doubleArrayOf(349.23, 440.00, 523.25, 659.25),       // Fmaj7
            doubleArrayOf(220.00, 261.63, 329.63, 392.00),       // Am7
            doubleArrayOf(196.00, 293.66, 392.00, 523.25)        // Gsus4
        )

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val chordIndex = (t / 2.5).toInt().coerceIn(0, 3)
            val currentChord = chords[chordIndex]
            val chordProgress = (t % 2.5) / 2.5

            // Smooth envelope for each chord (soft attack, slow release)
            val env = sin(chordProgress * PI).coerceIn(0.0, 1.0)
            // Gentle atmospheric tremolo
            val tremolo = 0.85 + 0.15 * sin(t * 3.1)

            var sample = 0.0
            for (freq in currentChord) {
                // Warm sine with soft second harmonic
                val wave = sin(2.0 * PI * freq * t) + 0.25 * sin(4.0 * PI * freq * t)
                sample += wave
            }
            sample = (sample / currentChord.size) * env * tremolo * 0.18 // Low, ambient volume

            pcm[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(pcm, 0, pcm.size)
        track.setLoopPoints(0, totalSamples, -1)
        return track
    }

    private fun generateCoinPcm(): ShortArray {
        // Bright two-tone chime: 987.77 Hz (B5) -> 1318.51 Hz (E6)
        val durationSeconds = 0.22
        val totalSamples = (sampleRate * durationSeconds).toInt()
        val pcm = ShortArray(totalSamples)

        val splitSample = (totalSamples * 0.32).toInt()

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val freq = if (i < splitSample) 987.77 else 1318.51
            val decay = exp(-t * 14.0)
            val wave = sin(2.0 * PI * freq * t) + 0.35 * sin(4.0 * PI * freq * t)
            val sample = wave * decay * 0.45
            pcm[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return pcm
    }

    private fun generateHitPcm(): ShortArray {
        // Low impact thud with fast pitch drop and damped noise
        val durationSeconds = 0.20
        val totalSamples = (sampleRate * durationSeconds).toInt()
        val pcm = ShortArray(totalSamples)

        var noiseSeed = 42
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val freq = 140.0 * (1.0 - t * 3.5).coerceAtLeast(35.0)
            noiseSeed = (noiseSeed * 1103515245 + 12345) and 0x7fffffff
            val noise = (noiseSeed / 0x7fffffff.toDouble()) * 2.0 - 1.0
            val decay = exp(-t * 22.0)
            val wave = (sin(2.0 * PI * freq * t) * 0.7 + noise * 0.3) * decay * 0.7
            pcm[i] = (wave * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return pcm
    }

    private fun generateGameOverPcm(): ShortArray {
        // Descending three-note melancholy arpeggio: A4 (440) -> F4 (349.2) -> D4 (293.7)
        val durationSeconds = 0.65
        val totalSamples = (sampleRate * durationSeconds).toInt()
        val pcm = ShortArray(totalSamples)

        val noteDuration = durationSeconds / 3.0
        val notes = doubleArrayOf(440.0, 349.23, 293.66)

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val noteIdx = (t / noteDuration).toInt().coerceIn(0, 2)
            val noteT = t % noteDuration
            val freq = notes[noteIdx]
            val decay = exp(-noteT * 7.5)
            val wave = (sin(2.0 * PI * freq * t) + 0.2 * sin(4.0 * PI * freq * t)) * decay * 0.42
            pcm[i] = (wave * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return pcm
    }

    private fun generateClickPcm(): ShortArray {
        // Subtle tactile tap: 1200 Hz with sharp decay
        val durationSeconds = 0.025
        val totalSamples = (sampleRate * durationSeconds).toInt()
        val pcm = ShortArray(totalSamples)

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val decay = exp(-t * 180.0)
            val wave = sin(2.0 * PI * 1200.0 * t) * decay * 0.35
            pcm[i] = (wave * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return pcm
    }

    private fun generateUnlockPcm(): ShortArray {
        // Fanfare chord: C5 -> E5 -> G5 -> C6
        val durationSeconds = 0.45
        val totalSamples = (sampleRate * durationSeconds).toInt()
        val pcm = ShortArray(totalSamples)

        val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.50)
        val step = durationSeconds / 4.0

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val noteIdx = (t / step).toInt().coerceIn(0, 3)
            val noteT = t % step
            val freq = notes[noteIdx]
            val decay = exp(-noteT * 8.0)
            val wave = (sin(2.0 * PI * freq * t) + 0.25 * sin(4.0 * PI * freq * t)) * decay * 0.4
            pcm[i] = (wave * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return pcm
    }
}
