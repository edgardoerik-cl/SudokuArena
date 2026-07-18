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

enum class MusicGenre(val label: String, val icon: String, val slug: String) {
    PHONK("Phonk", "🚘", "phonk"),
    POP("Pop", "✨", "pop"),
    ROCK("Rock", "🎸", "rock"),
    METAL("Metal", "🤘", "metal"),
    CLASSICAL("Clásica", "🎻", "classical"),
}

data class AudioUiState(
    val enabled: Boolean = true,
    val genre: MusicGenre = MusicGenre.PHONK,
    val volume: Float = .35f,
    val preparing: Boolean = false,
)

/**
 * Reproductor único de proceso con playlist procedural sin material protegido.
 * Cada género genera una composición breve original y perfectamente enlazable.
 * El MediaPlayer sobrevive a navegación y recreación de Activity.
 */
object GlobalAudioManager {
    private const val PREFS = "multi_arena_audio"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_GENRE = "genre"
    private const val KEY_VOLUME = "volume"
    private var appContext: Context? = null
    private var player: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var foreground = false
    private var preparationGeneration = 0
    private val mutableState = MutableStateFlow(AudioUiState())
    val state: StateFlow<AudioUiState> = mutableState.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val genre = runCatching {
            MusicGenre.valueOf(preferences.getString(KEY_GENRE, MusicGenre.PHONK.name).orEmpty())
        }.getOrDefault(MusicGenre.PHONK)
        mutableState.value = AudioUiState(
            enabled = preferences.getBoolean(KEY_ENABLED, true),
            genre = genre,
            volume = preferences.getFloat(KEY_VOLUME, .35f).coerceIn(0f, 1f),
        )
        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 55)
    }

    @Synchronized
    fun onForeground() {
        foreground = true
        if (!mutableState.value.enabled) return
        player?.start() ?: prepareTrack(mutableState.value.genre)
    }

    @Synchronized
    fun onBackground() {
        foreground = false
        player?.takeIf { it.isPlaying }?.pause()
    }

    @Synchronized
    fun toggle() {
        val next = !mutableState.value.enabled
        mutableState.value = mutableState.value.copy(enabled = next)
        persist()
        if (next && foreground) player?.start() ?: prepareTrack(mutableState.value.genre)
        else player?.pause()
    }

    @Synchronized
    fun nextTrack() {
        val genres = MusicGenre.entries
        val current = genres.indexOf(mutableState.value.genre)
        selectGenre(genres[(current + 1) % genres.size])
    }

    @Synchronized
    fun selectGenre(genre: MusicGenre) {
        if (genre == mutableState.value.genre && player != null) return
        mutableState.value = mutableState.value.copy(genre = genre)
        persist()
        player?.release()
        player = null
        preparationGeneration += 1
        if (mutableState.value.enabled && foreground) prepareTrack(genre)
    }

    @Synchronized
    fun setVolume(volume: Float) {
        val safe = volume.coerceIn(0f, 1f)
        mutableState.value = mutableState.value.copy(volume = safe)
        player?.setVolume(safe, safe)
        persist()
    }

    fun play(sound: GameSound) {
        if (!mutableState.value.enabled) return
        val tone = when (sound) {
            GameSound.CLICK -> ToneGenerator.TONE_PROP_BEEP
            GameSound.SUCCESS -> ToneGenerator.TONE_PROP_ACK
            GameSound.DANGER -> ToneGenerator.TONE_PROP_NACK
        }
        toneGenerator?.startTone(tone, if (sound == GameSound.DANGER) 220 else 75)
    }

    private fun persist() {
        val current = mutableState.value
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean(KEY_ENABLED, current.enabled)
            ?.putString(KEY_GENRE, current.genre.name)
            ?.putFloat(KEY_VOLUME, current.volume)
            ?.apply()
    }

    @Synchronized
    private fun prepareTrack(genre: MusicGenre) {
        val context = appContext ?: return
        val token = ++preparationGeneration
        mutableState.value = mutableState.value.copy(preparing = true)
        Thread {
            runCatching {
                val loop = File(context.cacheDir, "multi_arena_${genre.slug}_v2.wav")
                if (!loop.exists() || loop.length() < 100_000) writeGenreLoop(loop, genre)
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    setDataSource(loop.absolutePath)
                    isLooping = true
                    val volume = mutableState.value.volume
                    setVolume(volume, volume)
                    prepare()
                }
            }.onSuccess { ready ->
                synchronized(this) {
                    if (token != preparationGeneration || mutableState.value.genre != genre) {
                        ready.release()
                        return@synchronized
                    }
                    player?.release()
                    player = ready
                    mutableState.value = mutableState.value.copy(preparing = false)
                    if (foreground && mutableState.value.enabled) ready.start()
                }
            }.onFailure {
                synchronized(this) {
                    if (token == preparationGeneration) mutableState.value = mutableState.value.copy(preparing = false)
                }
            }
        }.apply { name = "MultiArena-Audio-${genre.slug}"; isDaemon = true }.start()
    }

    private fun writeGenreLoop(file: File, genre: MusicGenre) {
        val sampleRate = 22_050
        val seconds = 8
        val samples = sampleRate * seconds
        val pcmBytes = samples * 2
        BufferedOutputStream(FileOutputStream(file)).use { output ->
            output.write("RIFF".toByteArray()); output.writeLeInt(36 + pcmBytes)
            output.write("WAVEfmt ".toByteArray()); output.writeLeInt(16)
            output.writeLeShort(1); output.writeLeShort(1)
            output.writeLeInt(sampleRate); output.writeLeInt(sampleRate * 2)
            output.writeLeShort(2); output.writeLeShort(16)
            output.write("data".toByteArray()); output.writeLeInt(pcmBytes)
            repeat(samples) { index ->
                val time = index.toDouble() / sampleRate
                val edgeFade = minOf(1.0, time / .22, (seconds - time) / .22).coerceAtLeast(0.0)
                val raw = genreSample(genre, time)
                val sample = (raw.coerceIn(-.92, .92) * edgeFade * Short.MAX_VALUE).toInt()
                output.write(sample and 0xFF)
                output.write((sample ushr 8) and 0xFF)
            }
        }
    }

    private fun genreSample(genre: MusicGenre, time: Double): Double {
        fun wave(frequency: Double, amplitude: Double = 1.0) =
            sin(2.0 * PI * frequency * time) * amplitude
        fun kick(rate: Double): Double {
            val phase = (time * rate) % 1.0
            return wave(48.0 + 55.0 * (1.0 - phase), .28 * (1.0 - phase).coerceAtLeast(0.0))
        }
        return when (genre) {
            MusicGenre.PHONK -> {
                val cowbell = if ((time * 4).toInt() % 4 in 0..1) wave(690.0, .10) + wave(920.0, .05) else 0.0
                wave(55.0, .34) + wave(82.41, .13) + kick(2.0) + cowbell
            }
            MusicGenre.POP -> {
                val melody = doubleArrayOf(261.63, 329.63, 392.0, 329.63, 293.66, 349.23, 440.0, 349.23)
                val note = melody[((time * 2).toInt()) % melody.size]
                wave(130.81, .16) + wave(164.81, .12) + wave(note, .16) + kick(2.0)
            }
            MusicGenre.ROCK -> {
                val root = if ((time * 2).toInt() % 2 == 0) 110.0 else 98.0
                wave(root, .26) + wave(root * 2, .12) + wave(root * 3, .07) + wave(root * 1.5, .20) + kick(2.0)
            }
            MusicGenre.METAL -> {
                val root = if ((time * 4).toInt() % 4 < 2) 73.42 else 82.41
                val distorted = (wave(root, .55) + wave(root * 2, .28) + wave(root * 3, .18)).coerceIn(-.55, .55)
                distorted + kick(4.0) + wave(1_100.0, if ((time * 8).toInt() % 2 == 0) .04 else 0.0)
            }
            MusicGenre.CLASSICAL -> {
                val notes = doubleArrayOf(261.63, 329.63, 392.0, 523.25, 392.0, 329.63, 293.66, 349.23)
                val note = notes[((time * 2).toInt()) % notes.size]
                wave(note, .22) + wave(note * 2, .05) + wave(130.81, .12) + wave(196.0, .08)
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
