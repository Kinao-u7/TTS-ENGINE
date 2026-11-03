package com.novelreader.tts.android.service

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
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import com.novelreader.tts.android.engine.SystemTtsEngine
import com.novelreader.tts.android.store.SharedPreferencesPositionStore
import com.novelreader.tts.core.playback.PlaybackController
import com.novelreader.tts.core.playback.ReaderState
import com.novelreader.tts.core.store.PositionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service managing TTS playback, audio focus, lock-screen/notification controls,
 * and background persistence.
 */
class TtsPlaybackService : Service() {

    inner class LocalBinder : Binder() {
        val service: TtsPlaybackService get() = this@TtsPlaybackService
        val controller: PlaybackController get() = this@TtsPlaybackService.controller
        val ttsEngine: SystemTtsEngine get() = this@TtsPlaybackService.ttsEngine
        val positionStore: PositionStore get() = this@TtsPlaybackService.positionStore
        val readerState: StateFlow<ReaderState> get() = this@TtsPlaybackService.controller.readerState

        fun setBookAndChapter(bookId: String, chapterId: String, chapterTitle: String = "Chapter") {
            this@TtsPlaybackService.setBookAndChapter(bookId, chapterId, chapterTitle)
        }
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var ttsEngine: SystemTtsEngine
        private set
    lateinit var controller: PlaybackController
        private set
    lateinit var positionStore: PositionStore
        private set

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager
    private lateinit var audioFocusRequest: AudioFocusRequestCompat
    private lateinit var audioFocusStateMachine: TtsAudioFocusStateMachine

    private var currentBookId: String? = null
    private var currentChapterId: String? = null
    private var currentChapterTitle: String = "Chapter"

    private var isForegroundService: Boolean = false
    private var isReceiverRegistered: Boolean = false

    private var autoStopJob: Job? = null
    private var abandonFocusJob: Job? = null
    private var throttledSaveJob: Job? = null

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                audioFocusStateMachine.onBecomingNoisy()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        ttsEngine = SystemTtsEngine(applicationContext)
        controller = PlaybackController(ttsEngine, scope = serviceScope)
        positionStore = SharedPreferencesPositionStore(applicationContext)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
        setupMediaSession()
        setupAudioFocus()
        registerBecomingNoisyReceiver()

        observePlaybackState()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                if (audioFocusStateMachine.onPlayRequested()) {
                    controller.play()
                }
            }
            ACTION_PAUSE -> {
                audioFocusStateMachine.onPauseRequested()
                controller.pause()
            }
            ACTION_NEXT -> controller.next()
            ACTION_PREV -> controller.previous()
            ACTION_STOP -> {
                audioFocusStateMachine.onStopRequested()
                controller.stop()
                stopForegroundAndSelf()
            }
            ACTION_START_FOREGROUND -> {
                startForegroundIfNeeded()
            }
        }
        return START_NOT_STICKY
    }

    fun setBookAndChapter(bookId: String, chapterId: String, chapterTitle: String = "Chapter") {
        currentBookId = bookId
        currentChapterId = chapterId
        currentChapterTitle = chapterTitle
        updateNotification(controller.readerState.value)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Novel Reader Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Playback notifications for TTS novel reading"
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, MEDIA_SESSION_TAG).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (audioFocusStateMachine.onPlayRequested()) {
                        controller.play()
                    }
                }

                override fun onPause() {
                    audioFocusStateMachine.onPauseRequested()
                    controller.pause()
                }

                override fun onSkipToNext() {
                    controller.next()
                }

                override fun onSkipToPrevious() {
                    controller.previous()
                }

                override fun onStop() {
                    audioFocusStateMachine.onStopRequested()
                    controller.stop()
                    stopForegroundAndSelf()
                }
            })
            isActive = true
        }
    }

    private fun setupAudioFocus() {
        val audioAttributes = AudioAttributesCompat.Builder()
            .setUsage(AudioAttributesCompat.USAGE_MEDIA)
            .setContentType(AudioAttributesCompat.CONTENT_TYPE_SPEECH)
            .build()

        audioFocusRequest = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener { focusChange ->
                audioFocusStateMachine.onAudioFocusChange(focusChange)
            }
            .setWillPauseWhenDucked(true)
            .build()

        audioFocusStateMachine = TtsAudioFocusStateMachine(object : TtsAudioFocusStateMachine.Listener {
            override fun onPausePlayback() {
                controller.pause()
            }

            override fun onResumePlayback() {
                controller.play()
            }

            override fun onStopPlayback() {
                controller.stop()
            }

            override fun onRequestSystemFocus(): Boolean {
                val res = AudioManagerCompat.requestAudioFocus(audioManager, audioFocusRequest)
                return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }

            override fun onAbandonSystemFocus() {
                AudioManagerCompat.abandonAudioFocusRequest(audioManager, audioFocusRequest)
            }
        })
    }

    private fun registerBecomingNoisyReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(becomingNoisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(becomingNoisyReceiver, filter)
            }
            isReceiverRegistered = true
        }
    }

    private fun observePlaybackState() {
        var lastSentenceIndex = -1
        serviceScope.launch {
            controller.readerState.collect { readerState ->
                handleStateChange(readerState)

                // Track and persist reading position when sentence index changes
                if (readerState.currentSentenceIndex != lastSentenceIndex) {
                    lastSentenceIndex = readerState.currentSentenceIndex
                    throttleSavePosition(readerState.currentSentenceIndex)
                }
            }
        }
    }

    private fun handleStateChange(readerState: ReaderState) {
        if (readerState.isPlaying) {
            cancelAutoStopTimer()
            cancelAbandonFocusTimer()

            // Ensure service is in foreground when playing
            startForegroundIfNeeded()
            updateNotification(readerState)
            updateMediaSessionPlaybackState(true)
        } else {
            // Paused or stopped
            updateMediaSessionPlaybackState(false)
            if (isForegroundService) {
                updateNotification(readerState)
                scheduleAbandonFocusTimer()
                scheduleAutoStopTimer()
            }
        }
    }

    private fun startForegroundIfNeeded() {
        val notification = buildNotification(controller.readerState.value)
        if (!isForegroundService) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForegroundService = true
        } else {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(readerState: ReaderState) {
        if (isForegroundService) {
            val notification = buildNotification(readerState)
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(readerState: ReaderState): Notification {
        val speakerName = readerState.currentSpeaker?.displayName ?: "Narrator"
        val title = currentChapterTitle.ifBlank { "Chapter" }

        val isPlaying = readerState.isPlaying

        // PendingIntents for actions
        val prevPendingIntent = buildActionPendingIntent(ACTION_PREV, REQUEST_CODE_PREV)
        val playPausePendingIntent = buildActionPendingIntent(
            if (isPlaying) ACTION_PAUSE else ACTION_PLAY,
            REQUEST_CODE_PLAY_PAUSE
        )
        val nextPendingIntent = buildActionPendingIntent(ACTION_NEXT, REQUEST_CODE_NEXT)
        val stopPendingIntent = buildActionPendingIntent(ACTION_STOP, REQUEST_CODE_STOP)

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseText = if (isPlaying) "Pause" else "Play"

        // App launch intent
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                REQUEST_CODE_CONTENT,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText("Speaker: $speakerName")
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevPendingIntent)
            .addAction(playPauseIcon, playPauseText, playPausePendingIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(stopPendingIntent)
            )

        if (readerState.sentenceCount > 0) {
            builder.setSubText("Sentence ${readerState.currentSentenceIndex + 1} of ${readerState.sentenceCount}")
        }

        return builder.build()
    }

    private fun buildActionPendingIntent(actionName: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, TtsPlaybackService::class.java).apply {
            action = actionName
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateMediaSessionPlaybackState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    private fun throttleSavePosition(sentenceIndex: Int) {
        val bookId = currentBookId ?: return
        val chapterId = currentChapterId ?: return

        throttledSaveJob?.cancel()
        throttledSaveJob = serviceScope.launch {
            delay(THROTTLE_SAVE_DELAY_MS)
            positionStore.savePosition(bookId, chapterId, sentenceIndex)
        }
    }

    private fun scheduleAbandonFocusTimer() {
        abandonFocusJob?.cancel()
        abandonFocusJob = serviceScope.launch {
            delay(ABANDON_FOCUS_TIMEOUT_MS)
            audioFocusStateMachine.onAbandonTimeout()
        }
    }

    private fun cancelAbandonFocusTimer() {
        abandonFocusJob?.cancel()
        abandonFocusJob = null
    }

    private fun scheduleAutoStopTimer() {
        autoStopJob?.cancel()
        autoStopJob = serviceScope.launch {
            delay(AUTO_STOP_PAUSE_TIMEOUT_MS)
            stopForegroundAndSelf()
        }
    }

    private fun cancelAutoStopTimer() {
        autoStopJob?.cancel()
        autoStopJob = null
    }

    private fun stopForegroundAndSelf() {
        cancelAutoStopTimer()
        cancelAbandonFocusTimer()

        // Flush any pending position save immediately
        val bookId = currentBookId
        val chapterId = currentChapterId
        if (bookId != null && chapterId != null) {
            throttledSaveJob?.cancel()
            val currentIdx = controller.readerState.value.currentSentenceIndex
            serviceScope.launch {
                positionStore.savePosition(bookId, chapterId, currentIdx)
            }
        }

        if (isForegroundService) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForegroundService = false
        }
        notificationManager.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(becomingNoisyReceiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }

        mediaSession.isActive = false
        mediaSession.release()

        audioFocusStateMachine.onStopRequested()
        controller.release()
        serviceScope.cancel()
    }

    companion object {
        const val CHANNEL_ID = "com.novelreader.tts.playback_channel"
        const val MEDIA_SESSION_TAG = "com.novelreader.tts.media_session"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.novelreader.tts.action.PLAY"
        const val ACTION_PAUSE = "com.novelreader.tts.action.PAUSE"
        const val ACTION_NEXT = "com.novelreader.tts.action.NEXT"
        const val ACTION_PREV = "com.novelreader.tts.action.PREV"
        const val ACTION_STOP = "com.novelreader.tts.action.STOP"
        const val ACTION_START_FOREGROUND = "com.novelreader.tts.action.START_FOREGROUND"

        private const val REQUEST_CODE_CONTENT = 100
        private const val REQUEST_CODE_PREV = 101
        private const val REQUEST_CODE_PLAY_PAUSE = 102
        private const val REQUEST_CODE_NEXT = 103
        private const val REQUEST_CODE_STOP = 104

        private const val THROTTLE_SAVE_DELAY_MS = 500L
        private const val ABANDON_FOCUS_TIMEOUT_MS = 5000L // 5 seconds
        private const val AUTO_STOP_PAUSE_TIMEOUT_MS = 10 * 60 * 1000L // 10 minutes
    }
}
