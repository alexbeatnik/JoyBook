package com.local.joybook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat

/**
 * Audiobook playback. A "session" exists while a chapter is loaded (playing or paused); during a
 * session the service stays in the foreground so the lock-screen joystick can pause *and* resume.
 * Progress is stored per book every few seconds, on pause, seek and chapter change.
 */
class PlaybackService : Service() {

    companion object {
        private const val TAG = "JoyBook"
        const val CHANNEL_ID = "joybook_playback"
        const val NOTIF_ID = 42
        const val ACTION_PLAY = "com.local.joybook.PLAY"
        const val ACTION_PAUSE = "com.local.joybook.PAUSE"
        const val ACTION_TOGGLE = "com.local.joybook.TOGGLE"
        const val ACTION_REWIND = "com.local.joybook.REWIND"
        const val ACTION_FORWARD = "com.local.joybook.FORWARD"
        const val ACTION_NEXT_CHAPTER = "com.local.joybook.NEXT"
        const val ACTION_PREV_CHAPTER = "com.local.joybook.PREV"
        const val ACTION_CLOSE = "com.local.joybook.CLOSE"

        val SPEEDS = floatArrayOf(0.8f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f)
        val SKIP_STEPS = intArrayOf(10, 15, 30, 60)
        /** Sleep timer choices in minutes; -1 = end of chapter. */
        val SLEEP_STEPS = intArrayOf(0, 15, 30, 45, 60, -1)

        private const val RESTART_THRESHOLD_MS = 3000
        private const val SAVE_EVERY_MS = 5000L
        /** After a pause this long, rewind a little so the sentence makes sense again. */
        private const val SMART_REWIND_AFTER_MS = 60_000L
        private const val SMART_REWIND_MS = 5_000
        private const val FADE_STEPS = 10
        private const val FADE_STEP_MS = 400L

        private val AUDIO_ATTRS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        /** The open book (also without a service); persisted in [Prefs]. Main thread only. */
        var book: Book? = null
            private set
        var chapterIndex: Int = 0
            private set

        /** Running service instance. Main thread only. */
        var instance: PlaybackService? = null
            private set

        private val listeners = mutableSetOf<() -> Unit>()
        fun addListener(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
        fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
        fun notifyListeners() {
            val copy = synchronized(listeners) { listeners.toList() }
            copy.forEach { it.invoke() }
        }

        /** Restore the last open book after a cold start. */
        fun ensureLoaded(ctx: Context) {
            if (book != null) return
            val b = Prefs.loadCurrentBook(ctx) ?: return
            book = b
            chapterIndex = resumePoint(ctx, b).first
        }

        /** Where [b] continues: saved chapter/position, or the very start once finished. */
        fun resumePoint(ctx: Context, b: Book): Pair<Int, Int> {
            val (ch, pos) = Prefs.bookProgress(ctx, b.id)
            return if (ch >= b.chapters.size) 0 to 0 else ch.coerceAtLeast(0) to pos
        }

        /**
         * Make [b] the open book without starting playback (first run, rescan).
         * If a session is running for another book it keeps playing.
         */
        fun selectBook(ctx: Context, b: Book?) {
            val svc = instance
            if (svc != null && svc.hasSession()) {
                if (b != null && b.id == book?.id) svc.refreshBook(b)
                return
            }
            book = b
            chapterIndex = if (b == null) 0 else resumePoint(ctx, b).first
            Prefs.saveCurrentBook(ctx, b)
            notifyListeners()
        }
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequest

    private var player: MediaPlayer? = null
    private var prepared = false
    /** Position to report while the chapter is still preparing. */
    private var pendingPos = 0
    private var playWhenReady = false
    private var started = false
    private var hasFocus = false
    private var resumeOnFocusGain = false
    private var noisyRegistered = false
    private var consecutiveFailures = 0
    private var pausedAt = 0L
    private var lastMetaKey = ""

    private var sleepEndsAt = 0L
    var sleepEndOfChapter = false
        private set
    private var fadeStep = 0
    private var fading = false
    private val bookDataListener: () -> Unit = { onBookDataChanged() }

    /** Another app (call, alarm, navigation...) holds audio focus for a moment. */
    var focusLostTransiently = false
        private set

    inner class LocalBinder : Binder() {
        fun service(): PlaybackService = this@PlaybackService
    }

    private val saver = object : Runnable {
        override fun run() {
            if (playWhenReady) saveProgress()
            handler.postDelayed(this, SAVE_EVERY_MS)
        }
    }

    private val sleepTimer = Runnable { startFadeOut() }

    private val fader = object : Runnable {
        override fun run() {
            if (!fading) return
            fadeStep++
            if (fadeStep >= FADE_STEPS) {
                cancelSleep()
                pause()
                return
            }
            setVolume(1f - fadeStep.toFloat() / FADE_STEPS)
            handler.postDelayed(this, FADE_STEP_MS)
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                focusLostTransiently = false
                resumeOnFocusGain = false
                pauseInternal()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Ducking speech makes it unintelligible — pause instead.
                hasFocus = false
                focusLostTransiently = true
                if (playWhenReady) {
                    resumeOnFocusGain = true
                    pauseInternal()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                focusLostTransiently = false
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    play()
                }
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        A11yBootstrap.ensureEnabled(this)
        ensureLoaded(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AUDIO_ATTRS)
            .setOnAudioFocusChangeListener(focusListener, handler)
            .setWillPauseWhenDucked(true)
            .build()
        createChannel()

        val mediaButtonIntent = PendingIntent.getBroadcast(
            this, 0,
            Intent(Intent.ACTION_MEDIA_BUTTON).setClass(this, MediaKeyReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        session = MediaSessionCompat(this, "JoyBook").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onSkipToNext() = nextChapter()
                override fun onSkipToPrevious() = prevChapter()
                override fun onStop() = pause()
                override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
                override fun onFastForward() = skipForward()
                override fun onRewind() = skipBack()
                override fun onCustomAction(action: String?, extras: Bundle?) {
                    when (action) {
                        ACTION_REWIND -> skipBack()
                        ACTION_FORWARD -> skipForward()
                        ACTION_CLOSE -> endSession()
                    }
                }

                override fun onMediaButtonEvent(intent: Intent): Boolean {
                    // Chapter skip isn't advertised (the lock screen shows rewind/forward instead),
                    // so route the headset's next/previous keys by hand.
                    val ke = keyEvent(intent)
                    if (ke != null && (ke.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || ke.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS)) {
                        if (ke.action == KeyEvent.ACTION_DOWN && ke.repeatCount == 0) {
                            if (ke.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) nextChapter() else prevChapter()
                        }
                        return true
                    }
                    return super.onMediaButtonEvent(intent)
                }
            })
            setMediaButtonReceiver(mediaButtonIntent)
            isActive = false
        }
        updatePlaybackState()
        handler.post(saver)
        BookData.addListener(bookDataListener)
    }

    private fun keyEvent(intent: Intent): KeyEvent? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        started = true
        val action = intent?.action
        if (action == ACTION_CLOSE) {
            endSession()
            return START_NOT_STICKY
        }
        // Callers may have used startForegroundService(): honour that contract first.
        startInForeground()
        when (action) {
            Intent.ACTION_MEDIA_BUTTON -> androidx.media.session.MediaButtonReceiver.handleIntent(session, intent)
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_TOGGLE -> toggle()
            ACTION_REWIND -> skipBack()
            ACTION_FORWARD -> skipForward()
            ACTION_NEXT_CHAPTER -> nextChapter()
            ACTION_PREV_CHAPTER -> prevChapter()
        }
        if (!hasSession()) endSession()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!playWhenReady) endSession()
        super.onTaskRemoved(rootIntent)
    }

    // ---- State -------------------------------------------------------------------------

    fun hasSession(): Boolean = player != null
    fun isPlaying(): Boolean = playWhenReady
    fun holdsAudioFocus(): Boolean = hasFocus
    fun position(): Int = if (prepared) try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 } else pendingPos
    fun duration(): Int {
        if (prepared) try { player?.duration?.takeIf { it > 0 }?.let { return it } } catch (_: Exception) {}
        val c = book?.chapters?.getOrNull(chapterIndex) ?: return 0
        return BookData.duration(this, c.uri).toInt()
    }

    fun speed(): Float = Prefs.speed(this)
    fun skipMs(): Int = Prefs.skipSec(this) * 1000

    /** Remaining sleep time in ms (0 = off or end-of-chapter mode). */
    fun sleepRemainingMs(): Long = if (sleepEndsAt > 0) (sleepEndsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0) else 0

    // ---- Controls ----------------------------------------------------------------------

    /** Switch to [b] and start playing where it was left. */
    fun openBook(b: Book) {
        if (b.id == book?.id && hasSession()) {
            refreshBook(b)
            if (!playWhenReady) play()
            return
        }
        saveProgress()
        releasePlayer()
        book = b
        Prefs.saveCurrentBook(this, b)
        lastMetaKey = ""
        val (ch, pos) = resumePoint(this, b)
        startChapter(ch.coerceIn(0, b.chapters.lastIndex), pos, autoPlay = true)
    }

    /** Library was rescanned: take the fresh chapter list of the open book. */
    internal fun refreshBook(b: Book) {
        val old = book ?: return
        val curUri = old.chapters.getOrNull(chapterIndex)?.uri
        val idx = b.chapters.indexOfFirst { it.uri == curUri }
        if (idx < 0) {
            endSession()
            book = b
            chapterIndex = resumePoint(this, b).first
        } else {
            book = b
            chapterIndex = idx
        }
        Prefs.saveCurrentBook(this, b)
        notifyListeners()
    }

    fun jumpToChapter(index: Int) {
        val b = book ?: return
        startChapter(index.coerceIn(0, b.chapters.lastIndex), 0, autoPlay = true)
    }

    fun play() {
        val b = book ?: return
        if (b.chapters.isEmpty()) return
        val p = player
        if (p == null) {
            val (ch, pos) = resumePoint(this, b)
            val idx = chapterIndex.coerceIn(0, b.chapters.lastIndex)
            startChapter(idx, if (ch == idx) smartRewind(pos) else 0, autoPlay = true)
            return
        }
        if (!requestFocus()) return
        cancelFade()
        playWhenReady = true
        resumeOnFocusGain = false
        if (prepared) {
            val pos = position()
            val rewound = smartRewind(pos)
            if (rewound != pos) try { p.seekTo(rewound) } catch (_: Exception) {}
            startPlayer(p)
        }
        pausedAt = 0L
        onStateChanged()
    }

    fun pause() {
        resumeOnFocusGain = false
        pauseInternal()
    }

    private fun pauseInternal() {
        playWhenReady = false
        if (prepared) {
            try { if (player?.isPlaying == true) player?.pause() } catch (_: Exception) {}
        }
        cancelFade() // after pausing, so the restored volume isn't heard
        pausedAt = SystemClock.elapsedRealtime()
        saveProgress()
        onStateChanged()
    }

    fun toggle() {
        if (playWhenReady) pause() else play()
    }

    fun skipBack() = seekBy(-skipMs())
    fun skipForward() = seekBy(skipMs())

    /** Seek within the book: crosses into the previous / next chapter when needed. */
    fun seekBy(delta: Int) {
        val b = book ?: return
        if (!hasSession()) {
            // Nothing loaded yet: load the resume point paused, then seek.
            val (ch, pos) = resumePoint(this, b)
            val idx = chapterIndex.coerceIn(0, b.chapters.lastIndex)
            startChapter(idx, ((if (ch == idx) pos else 0) + delta).coerceAtLeast(0), autoPlay = false)
            return
        }
        val target = position() + delta
        val dur = duration()
        when {
            target < 0 && chapterIndex > 0 -> {
                val prevDur = BookData.duration(this, b.chapters[chapterIndex - 1].uri).toInt()
                startChapter(chapterIndex - 1, if (prevDur > 0) (prevDur + target).coerceAtLeast(0) else 0, playWhenReady)
            }
            dur > 0 && target >= dur && chapterIndex < b.chapters.lastIndex ->
                startChapter(chapterIndex + 1, (target - dur).coerceAtLeast(0), playWhenReady)
            else -> seekTo(target)
        }
    }

    fun seekTo(ms: Int) {
        if (!prepared) {
            pendingPos = ms.coerceAtLeast(0)
            return
        }
        val dur = duration()
        val target = if (dur > 0) ms.coerceIn(0, (dur - 500).coerceAtLeast(0)) else ms.coerceAtLeast(0)
        try { player?.seekTo(target) } catch (_: Exception) {}
        saveProgress(target)
        onStateChanged()
    }

    fun nextChapter() {
        val b = book ?: return
        if (chapterIndex >= b.chapters.lastIndex) return
        startChapter(chapterIndex + 1, 0, autoPlay = playWhenReady)
    }

    fun prevChapter() {
        if (book == null) return
        if (hasSession() && position() > RESTART_THRESHOLD_MS) {
            seekTo(0)
            return
        }
        if (chapterIndex <= 0) {
            seekTo(0)
            return
        }
        startChapter(chapterIndex - 1, 0, autoPlay = playWhenReady)
    }

    fun setSpeed(v: Float) {
        Prefs.setSpeed(this, v)
        val p = player
        if (p != null && prepared && playWhenReady) applySpeed(p)
        onStateChanged()
    }

    fun cycleSpeed(dir: Int) {
        val cur = SPEEDS.indexOfFirst { it >= speed() - 0.001f }.let { if (it < 0) SPEEDS.lastIndex else it }
        setSpeed(SPEEDS[(cur + dir).coerceIn(0, SPEEDS.lastIndex)])
    }

    /** Off → 15 → 30 → 45 → 60 min → end of chapter → off. Returns the new choice. */
    fun cycleSleep(): Int {
        val cur = when {
            sleepEndOfChapter -> -1
            sleepEndsAt > 0 -> SLEEP_STEPS.firstOrNull { it > 0 && it * 60_000L >= sleepRemainingMs() } ?: 60
            else -> 0
        }
        val next = SLEEP_STEPS[(SLEEP_STEPS.indexOf(cur) + 1) % SLEEP_STEPS.size]
        setSleep(next)
        return next
    }

    fun setSleep(minutes: Int) {
        cancelSleep()
        when {
            minutes < 0 -> sleepEndOfChapter = true
            minutes > 0 -> {
                sleepEndsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
                handler.postDelayed(sleepTimer, minutes * 60_000L)
            }
        }
        onStateChanged()
    }

    private fun cancelSleep() {
        handler.removeCallbacks(sleepTimer)
        sleepEndsAt = 0L
        sleepEndOfChapter = false
    }

    private fun startFadeOut() {
        sleepEndsAt = 0L
        if (!playWhenReady) {
            cancelSleep()
            onStateChanged()
            return
        }
        fadeStep = 0
        fading = true
        handler.post(fader)
    }

    private fun cancelFade() {
        if (!fading) return
        fading = false
        handler.removeCallbacks(fader)
        fadeStep = 0
        setVolume(1f)
    }

    /** Stop playback, drop the notification and let the service die once unbound. */
    fun endSession() {
        saveProgress()
        playWhenReady = false
        resumeOnFocusGain = false
        cancelSleep()
        cancelFade()
        releasePlayer()
        abandonFocus()
        updateNoisyReceiver()
        session.isActive = false
        updatePlaybackState()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (started) {
            started = false
            stopSelf()
        }
        notifyListeners()
    }

    // ---- Chapter handling --------------------------------------------------------------

    private fun smartRewind(pos: Int): Int {
        val longPause = pausedAt > 0 && SystemClock.elapsedRealtime() - pausedAt >= SMART_REWIND_AFTER_MS
        return if (longPause) (pos - SMART_REWIND_MS).coerceAtLeast(0) else pos
    }

    private fun startChapter(index: Int, resumePos: Int, autoPlay: Boolean) {
        val b = book ?: return
        val chapter = b.chapters.getOrNull(index) ?: return
        chapterIndex = index
        Prefs.setBookProgress(this, b.id, index, resumePos)
        if (!autoPlay && !hasSession() && resumePos == 0) {
            // Just move the bookmark; nothing to load until the user presses play.
            onStateChanged()
            return
        }
        releasePlayer()
        cancelFade()
        playWhenReady = autoPlay && requestFocus()
        resumeOnFocusGain = false
        pendingPos = resumePos
        pausedAt = 0L
        beginSession()

        val mp = MediaPlayer()
        player = mp
        try {
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setAudioAttributes(AUDIO_ATTRS)
            mp.setOnPreparedListener { p ->
                if (p !== player) return@setOnPreparedListener
                prepared = true
                consecutiveFailures = 0
                BookData.putDuration(this, chapter.uri, p.duration.toLong())
                if (pendingPos > 0) p.seekTo(pendingPos)
                if (playWhenReady) startPlayer(p)
                onStateChanged()
            }
            mp.setOnCompletionListener { p ->
                if (p === player) chapterFinished()
            }
            mp.setOnErrorListener { p, what, extra ->
                Log.w(TAG, "MediaPlayer error $what/$extra in chapter ${index + 1}")
                if (p === player) {
                    prepared = false
                    handler.post { if (player === p) skipFailedChapter() }
                }
                true
            }
            mp.setDataSource(this, chapter.uri)
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open chapter ${index + 1}", e)
            handler.post { if (player === mp) skipFailedChapter() }
        }
        onStateChanged()
    }

    private fun startPlayer(p: MediaPlayer) {
        try {
            p.start()
            applySpeed(p)
        } catch (_: Exception) {
        }
    }

    private fun applySpeed(p: MediaPlayer) {
        try {
            p.playbackParams = p.playbackParams.setSpeed(speed())
        } catch (e: Exception) {
            Log.w(TAG, "Speed ${speed()} not supported", e)
        }
    }

    private fun chapterFinished() {
        val b = book ?: return
        if (sleepEndOfChapter) {
            cancelSleep()
            if (chapterIndex < b.chapters.lastIndex) {
                // Stop here; the next chapter is waiting when the user wakes up.
                startChapter(chapterIndex + 1, 0, autoPlay = false)
                pausedAt = SystemClock.elapsedRealtime()
            } else {
                finishBook()
            }
            return
        }
        if (chapterIndex < b.chapters.lastIndex) startChapter(chapterIndex + 1, 0, autoPlay = true) else finishBook()
    }

    private fun finishBook() {
        val b = book ?: return
        releasePlayer()
        Prefs.setBookProgress(this, b.id, b.chapters.size, 0) // marks "finished"
        chapterIndex = 0
        endSession()
    }

    private fun skipFailedChapter() {
        val b = book ?: return
        consecutiveFailures++
        if (consecutiveFailures >= 3 || chapterIndex >= b.chapters.lastIndex) {
            consecutiveFailures = 0
            endSession()
        } else {
            startChapter(chapterIndex + 1, 0, autoPlay = true)
        }
    }

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        prepared = false
        try {
            p.setOnPreparedListener(null)
            p.setOnCompletionListener(null)
            p.setOnErrorListener(null)
            p.reset()
        } catch (_: Exception) {
        }
        try { p.release() } catch (_: Exception) {}
    }

    private fun setVolume(v: Float) {
        try { player?.setVolume(v, v) } catch (_: Exception) {}
    }

    private fun saveProgress(pos: Int = position()) {
        val b = book ?: return
        if (!hasSession()) return
        Prefs.setBookProgress(this, b.id, chapterIndex, pos)
    }

    // ---- Audio focus / noisy -----------------------------------------------------------

    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        hasFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hasFocus) focusLostTransiently = false
        return hasFocus
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        audioManager.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
    }

    private fun updateNoisyReceiver() {
        if (playWhenReady && !noisyRegistered) {
            ContextCompat.registerReceiver(
                this, noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyRegistered = true
        } else if (!playWhenReady && noisyRegistered) {
            try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
            noisyRegistered = false
        }
    }

    // ---- Session / notification --------------------------------------------------------

    private fun beginSession() {
        if (!started) {
            started = true
            try {
                ContextCompat.startForegroundService(this, Intent(this, PlaybackService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "startForegroundService failed", e)
            }
        }
        if (!session.isActive) session.isActive = true
        startInForeground()
        book?.let { BookData.load(this, it); BookData.fillDurations(this, it) }
    }

    private fun onStateChanged() {
        updateMetadata()
        updatePlaybackState()
        updateNoisyReceiver()
        if (hasSession() && started) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
        notifyListeners()
    }

    /** Cover / tags arrived in the background. */
    internal fun onBookDataChanged() {
        lastMetaKey = ""
        if (hasSession()) onStateChanged()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun startInForeground() {
        try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
        }
    }

    private fun chapterLine(): String = book?.chapterLabel(this, chapterIndex) ?: ""

    private fun buildNotification(): Notification {
        val b = book
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playing = playWhenReady
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(b?.title ?: getString(R.string.app_name))
            .setContentText(chapterLine())
            .setLargeIcon(b?.let { BookData.cover(it) })
            .setContentIntent(open)
            .setDeleteIntent(actionPending(ACTION_CLOSE, 5))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .addAction(R.drawable.ic_replay, getString(R.string.rewind), actionPending(ACTION_REWIND, 1))
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (playing) R.string.pause else R.string.play),
                actionPending(ACTION_TOGGLE, 2)
            )
            .addAction(R.drawable.ic_forward, getString(R.string.forward), actionPending(ACTION_FORWARD, 3))
            .addAction(R.drawable.ic_close, getString(R.string.close), actionPending(ACTION_CLOSE, 4))
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun actionPending(action: String, req: Int): PendingIntent {
        val i = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(this, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun updateMetadata() {
        val b = book ?: return
        val dur = duration().toLong()
        val art = BookData.cover(b)
        val author = BookData.info(this, b)?.author
        val key = "${b.id}|$chapterIndex|$dur|${art != null}|$author"
        if (key == lastMetaKey) return
        lastMetaKey = key
        val m = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, b.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, chapterLine())
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, author ?: "")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur)
        art?.let { m.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(m.build())
    }

    private fun updatePlaybackState() {
        val state = when {
            !hasSession() -> PlaybackStateCompat.STATE_STOPPED
            playWhenReady && !prepared -> PlaybackStateCompat.STATE_BUFFERING
            playWhenReady -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        // Chapter skip is left out on purpose so the lock screen shows rewind / forward
        // (custom actions take the previous / next slots) plus close.
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_FAST_FORWARD or
            PlaybackStateCompat.ACTION_REWIND or
            PlaybackStateCompat.ACTION_STOP
        val step = Prefs.skipSec(this)
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(ACTION_REWIND, getString(R.string.rewind_n, step), R.drawable.ic_replay).build()
                )
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(ACTION_FORWARD, getString(R.string.forward_n, step), R.drawable.ic_forward).build()
                )
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(ACTION_CLOSE, getString(R.string.close), R.drawable.ic_close).build()
                )
                .setState(state, position().toLong(), if (state == PlaybackStateCompat.STATE_PLAYING) speed() else 0f)
                .build()
        )
    }

    override fun onDestroy() {
        saveProgress()
        playWhenReady = false
        releasePlayer()
        abandonFocus()
        updateNoisyReceiver()
        session.release()
        BookData.removeListener(bookDataListener)
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        notifyListeners()
        super.onDestroy()
    }
}
