package com.sudokuarena.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GameSound { CLICK, SUCCESS, DANGER }

/**
 * Reproductor único de proceso. Conserva el mismo MediaPlayer al navegar o
 * recrear una Activity, por lo que la pista no vuelve al comienzo al rotar.
 *
 * La música es un loop synthwave original generado una sola vez en caché. Así
 * no dependemos de streaming, licencias externas ni memoria de recursos PCM.
 */
object GlobalAudioManager {
    private const val PREFS = "multi_arena_audio"
    private const val KEY_ENABLED = "enabled"
    private var appContext: Context? = null
    private var player: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var foreground = false
    private var preparing = false
    private val mutableEnabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        mutableEnabled.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 55)
    }

    @Synchronized
    fun onForeground() {
        foreground = true
        if (!mutableEnabled.value) return
        player?.start() ?: preparePlayer()
    }

    @Synchronized
    fun onBackground() {
        foreground = false
        player?.takeIf(MediaPlayer::isPlaying)?.pause()
    }

    @Synchronized
    fun toggle() {
        val next = !mutableEnabled.value
        mutableEnabled.value = next
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_ENABLED, next)?.apply()
        if (next && foreground) player?.start() ?: preparePlayer()
        else player?.pause()
    }

    fun play(sound: GameSound) {
        if (!mutableEnabled.value) return
        val tone = when (sound) {
            GameSound.CLICK -> ToneGenerator.TONE_PROP_BEEP
            GameSound.SUCCESS -> ToneGenerator.TONE_PROP_ACK
            GameSound.DANGER -> ToneGenerator.TONE_PROP_NACK
        }
        toneGenerator?.startTone(tone, if (sound == GameSound.DANGER) 220 else 75)
    }

    @Synchronized
    private fun preparePlayer() {
        val context = appContext ?: return
        if (preparing) return
        preparing = true
        Thread {
            runCatching {
                val loop = File(context.cacheDir, "multi_arena_ambient_v1.wav")
                if (!loop.exists() || loop.length() < 100_000) writeAmbientLoop(loop)
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    setDataSource(loop.absolutePath)
                    isLooping = true
                    setVolume(.28f, .28f)
                    prepare()
                }
            }.onSuccess { ready ->
                synchronized(this) {
                    player?.release()
                    player = ready
                    preparing = false
                    if (foreground && mutableEnabled.value) ready.start()
                }
            }.onFailure {
                synchronized(this) { preparing = false }
            }
        }.apply { name = "MultiArena-Audio"; isDaemon = true }.start()
    }

    private fun writeAmbientLoop(file: File) {
        val sampleRate = 22_050
        val seconds = 8
        val samples = sampleRate * seconds
        val pcmBytes = samples * 2
        BufferedOutputStream(FileOutputStream(file)).use { output ->
            output.write("RIFF".toByteArray())
            output.writeLeInt(36 + pcmBytes)
            output.write("WAVEfmt ".toByteArray())
            output.writeLeInt(16)
            output.writeLeShort(1)
            output.writeLeShort(1)
            output.writeLeInt(sampleRate)
            output.writeLeInt(sampleRate * 2)
            output.writeLeShort(2)
            output.writeLeShort(16)
            output.write("data".toByteArray())
            output.writeLeInt(pcmBytes)
            val chord = doubleArrayOf(110.0, 164.81, 220.0, 261.63)
            repeat(samples) { index ->
                val time = index.toDouble() / sampleRate
                val fade = minOf(1.0, time / .35, (seconds - time) / .35).coerceAtLeast(0.0)
                val slowPad = chord.sumOf { frequency ->
                    sin(2.0 * PI * frequency * time) * .12
                }
                val pulseEnvelope = ((sin(2.0 * PI * .5 * time) + 1.0) * .5) * .11
                val pulse = sin(2.0 * PI * 55.0 * time) * pulseEnvelope
                val shimmer = sin(2.0 * PI * 523.25 * time) * .025 * (sin(2.0 * PI * .125 * time) + 1.0)
                val sample = ((slowPad + pulse + shimmer) * fade * Short.MAX_VALUE)
                    .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output.write(sample and 0xFF)
                output.write((sample ushr 8) and 0xFF)
            }
        }
    }

    private fun BufferedOutputStream.writeLeInt(value: Int) {
        write(value and 0xFF); write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF); write((value ushr 24) and 0xFF)
    }

    private fun BufferedOutputStream.writeLeShort(value: Int) {
        write(value and 0xFF); write((value ushr 8) and 0xFF)
    }
}
