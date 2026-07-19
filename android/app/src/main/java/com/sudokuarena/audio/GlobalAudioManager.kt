package com.sudokuarena.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import com.sudokuarena.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GameSound { CLICK, SUCCESS, DANGER }

/**
 * Música real incluida dentro del APK. Todas las pistas son CC0 y sus créditos
 * se encuentran en docs/music-credits.md.
 */
enum class MusicGenre(
    val label: String,
    val icon: String,
    val slug: String,
    val trackTitle: String,
    val artist: String,
    val rawResource: Int,
) {
    PHONK("Electrónica", "🚘", "electronic", "Electronic Music Loop", "Rami99", R.raw.music_phonk),
    POP("Pop", "✨", "pop", "High", "Pro Sensory", R.raw.music_pop),
    ROCK("Rock", "🎸", "rock", "Background Music Loop", "Pro Sensory", R.raw.music_rock),
    METAL("Metal", "🤘", "metal", "Heavy Dungeon", "Joth", R.raw.music_metal),
    CLASSICAL(
        "Clásica",
        "🎻",
        "classical",
        "Classical Pop (Instrumental)",
        "Pro Sensory",
        R.raw.music_classical,
    ),
}

data class AudioUiState(
    val enabled: Boolean = true,
    val genre: MusicGenre = MusicGenre.PHONK,
    val volume: Float = .35f,
    val preparing: Boolean = false,
)

/**
 * Reproductor único para todo el proceso de la aplicación.
 *
 * Las canciones están empaquetadas en res/raw, por lo que no dependen de
 * Internet, no se reinician al navegar entre Composables y sobreviven a la
 * recreación de la Activity.
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
            MusicGenre.valueOf(
                preferences.getString(KEY_GENRE, MusicGenre.PHONK.name).orEmpty(),
            )
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
        val enabled = !mutableState.value.enabled
        mutableState.value = mutableState.value.copy(enabled = enabled)
        persist()
        if (enabled && foreground) {
            player?.start() ?: prepareTrack(mutableState.value.genre)
        } else {
            player?.pause()
        }
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

    /** Posición monotónica de la pista usada por juegos sincronizados al BPM. */
    @Synchronized
    fun playbackPositionMs(): Long = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)

    fun beatPhase(bpm: Int): Float {
        val beatMs = 60_000f / bpm.coerceAtLeast(1)
        return (playbackPositionMs() % beatMs.toLong()) / beatMs
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
                val ready = MediaPlayer()
                context.resources.openRawResourceFd(genre.rawResource).use { audio ->
                    ready.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    ready.setDataSource(audio.fileDescriptor, audio.startOffset, audio.length)
                    ready.isLooping = true
                    val volume = mutableState.value.volume
                    ready.setVolume(volume, volume)
                    ready.prepare()
                }
                ready
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
                    if (token == preparationGeneration) {
                        mutableState.value = mutableState.value.copy(preparing = false)
                    }
                }
            }
        }.apply {
            name = "MultiArena-Audio-${genre.slug}"
            isDaemon = true
        }.start()
    }
}
