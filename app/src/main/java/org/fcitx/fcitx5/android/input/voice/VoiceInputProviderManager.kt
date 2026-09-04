/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputCallback
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputProvider
import org.fcitx.fcitx5.android.common.ipc.VoiceInputIpc
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber

data class VoiceInputProviderInfo(
    val id: String,
    val label: CharSequence,
    val packageName: String,
    val serviceName: String,
    val action: String,
)

object VoiceInputProviderManager {
    const val ID_PREFIX = "plugin:"
    private const val TAG = "FcitxVoiceInput"

    // 3-second post-speech silence ⇒ provider emits a final segment.
    private const val DEFAULT_SILENCE_MS = 3000L
    private const val KEEPALIVE_MIN_INTERVAL_MS = 10_000L
    private const val KEEPALIVE_BIND_TIMEOUT_MS = 2_000L
    /** Hard deadline for `onServiceConnected` to fire after `bindService` returns
     *  true for an active voice session. If the plugin process hangs during startup
     *  (e.g. model init deadlocks), the IME must not stay stuck in "connecting". */
    private const val ACTIVE_BIND_TIMEOUT_MS = 8_000L
    /** Hard deadline for `onReady` to fire after the provider connected. Providers
     *  load their model asynchronously (the binder `startSession` returns before the
     *  model is ready), so `onServiceConnected` firing does not mean the session is
     *  usable. Without this guard the IME stays stuck in "connecting"/"loading" forever
     *  when a cold-start model load hangs or never emits onReady. On-device model loads
     *  are normally well under this; the retry below covers a one-off stall. */
    private const val ACTIVE_READY_TIMEOUT_MS = 12_000L
    /** Total bind attempts for one voice session (initial + auto-retries). On a ready
     *  timeout the IME rebinds once, which makes the provider bump its session
     *  generation and reload — often recovering a one-off stuck cold start. */
    private const val MAX_BIND_ATTEMPTS = 2
    private const val WARM_CONNECTION_HOLD_MS = 5 * 60 * 1000L

    // Upper bound for draining the in-flight PCM queue before sending endStream.
    // Why: avoid tail-end word loss if the provider hangs in feedAudio.
    private const val FEED_DRAIN_TIMEOUT_MS = 1_000L

    // Pre-roll window: capture starts immediately on toggle so audio spoken
    // before the provider reports onReady is replayed once the session opens.
    // Eliminates head-end word loss from cold-bind + model-load latency.
    private const val PRE_ROLL_MAX_MS = 3_000L

    private val actions = buildSet {
        val appId = BuildConfig.APPLICATION_ID
        add(appId)
        val releaseLikeId = appId.removeSuffix(".debug")
        add(releaseLikeId)
        add("$releaseLikeId.debug")
        add("org.fcitx.fcitx5.android")
        add("org.fcitx.fcitx5.android.debug")
    }.map { it + VoiceInputIpc.SERVICE_ACTION_SUFFIX }

    private var activeConnection: ServiceConnection? = null
    private var activeProvider: IVoiceInputProvider? = null
    private var activeProviderBinder: IBinder? = null
    private var activeProviderDeathRecipient: IBinder.DeathRecipient? = null
    private var activeCallback: IVoiceInputCallback? = null
    private var activeCapture: VoiceInputAudioCapture? = null
    private var activeAudioFeedJob: Job? = null
    private var activeAudioFeedQueue: Channel<QueuedAudio>? = null
    private var activeSessionConfig = SessionConfig()
    private var activeProviderId: String? = null
    private var finishing = false
    private var sessionActive = false
    @Volatile private var sessionReady = false
    private var floatingFallbackActive = false
    private var floatingFallbackComponent: ComponentName? = null
    private var warmConnectionReleaseGeneration = 0
    private var keepAliveConnection: ServiceConnection? = null
    private var keepAliveLastAttemptElapsed = 0L
    private var voiceSessionTerminalized = false
    @Volatile private var preRollActive: Boolean = false
    private val preRollBuffer = ArrayDeque<QueuedAudio>()
    private val preRollLock = Any()
    var floatingCommitListener: ((String) -> Unit)? = null
    // Default UI callbacks for voice status, shared across all toggle() callers.
    var voiceStatusCallback: ((String) -> Unit)? = null    // show/hide/update bar text
    var voiceLevelCallback: ((Int) -> Unit)? = null        // volume level
    var voiceReadyCallback: (() -> Unit)? = null           // set "listening" status
    var voiceFinishedCallback: (() -> Unit)? = null        // hide status on finish
    var voiceErrorCallback: ((String) -> Unit)? = null     // show error toast
    // Layout preservation around a voice session (see KeyboardWindow.onVoiceInputStarted/Finished).
    var sessionStartedCallback: (() -> Unit)? = null
    var sessionEndedCallback: (() -> Unit)? = null

    private data class QueuedAudio(
        val pcm: ByteArray,
        val ptsMs: Long,
    )

    // Verbose tracing is debug-only. Timber still records important warnings
    // in release, so user-reported issues remain debuggable via the file log.
    // R8 folds away the call in release builds where BuildConfig.DEBUG is false.
    private fun logI(msg: String) {
        if (BuildConfig.DEBUG) android.util.Log.i(TAG, msg)
    }

    private fun logW(msg: String) {
        if (BuildConfig.DEBUG) android.util.Log.w(TAG, msg)
    }

    private fun logW(msg: String, e: Throwable) {
        if (BuildConfig.DEBUG) android.util.Log.w(TAG, msg, e)
    }

    // Timber.i is verbose tracing of normal flow; gate it like logI so release
    // builds don't pay the string-template + tree-iteration cost. Timber.w
    // stays unconditional — release file logs need warnings for bug reports.
    private fun tlogI(msg: String) {
        if (BuildConfig.DEBUG) Timber.i(msg)
    }

    fun isActive(): Boolean =
        floatingFallbackActive || sessionActive || activeCallback != null || activeCapture != null

    // Toggle helper for UI buttons that should "stop if running, start otherwise".
    // Returns true if a new session was started, false if the previous one was stopped.
    fun toggle(
        service: FcitxInputMethodService,
        id: String,
        onReady: () -> Unit,
        onPartialResult: (String) -> Unit,
        onError: (String) -> Unit,
        onLevel: (Int) -> Unit = {},
        onFinished: () -> Unit = {},
        onStatus: (String) -> Unit = {},
    ): Boolean {
        if (isActive()) {
            if (floatingFallbackActive) {
                stopFloatingFallback(service)
                return false
            }
            if (finishing) {
                logI("toggle ignored: voice input is finishing")
                return false
            }
            finish(service)
            return false
        }
        start(service, id, onReady, onPartialResult, onError, onLevel, onFinished, onStatus)
        return true
    }

    fun listProviders(context: Context = appContext): List<VoiceInputProviderInfo> {
        val registered = VoiceInputProviderRegistry.all().map {
            VoiceInputProviderInfo(
                id = it.id,
                label = it.label,
                packageName = it.packageName,
                serviceName = "",
                action = "registered",
            )
        }
        val services = actions.flatMap { action ->
            val intent = Intent(action)
            val results = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentServices(
                    intent,
                    android.content.pm.PackageManager.ResolveInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentServices(intent, 0)
            }
            results.map { action to it }
        }
        val queried = services.mapNotNull { (action, info) ->
            val serviceInfo = info.serviceInfo ?: return@mapNotNull null
            val component = ComponentName(serviceInfo.packageName, serviceInfo.name)
            VoiceInputProviderInfo(
                id = ID_PREFIX + component.flattenToShortString(),
                label = serviceInfo.loadLabel(context.packageManager),
                packageName = serviceInfo.packageName,
                serviceName = serviceInfo.name,
                action = action,
            )
        }
        return (registered + queried).distinctBy { it.id }
            .sortedBy { it.label.toString() }
            .also {
                logI(
                    "Detected voice input providers: " +
                        it.joinToString { p ->
                            "${p.id}@${p.action}[${p.packageName}/${p.serviceName}]"
                        }
                )
                tlogI(
                    "Detected voice input providers: " +
                        it.joinToString { p -> "${p.id}@${p.action}" }
                )
            }
    }

    fun isProviderId(id: String) = id.startsWith(ID_PREFIX)

    fun hasProvider(id: String, context: Context = appContext): Boolean =
        listProviders(context).any { it.id == id }

    /**
     * Best-effort prewarm for external voice input plugins when the virtual keyboard is shown.
     *
     * Binding with BIND_AUTO_CREATE recreates the plugin service if Android killed its process in
     * the background. We immediately call isAvailable() so heavy providers can lazily load their
     * model before the user taps the mic button, then unbind after a short delay.
     */
    fun ensureProviderAvailable(service: FcitxInputMethodService, id: String) {
        if (!isProviderId(id)) return
        if (isActive()) return
        val now = SystemClock.elapsedRealtime()
        if (now - keepAliveLastAttemptElapsed < KEEPALIVE_MIN_INTERVAL_MS) return
        keepAliveLastAttemptElapsed = now

        val providerComponent = ComponentName.unflattenFromString(id.removePrefix(ID_PREFIX))
        if (providerComponent == null) {
            logW("keepalive invalid provider id=$id")
            return
        }
        val action = listProviders(service).firstOrNull { it.id == id }?.action
        if (action == null) {
            logI("keepalive skipped: provider unavailable id=$id")
            return
        }

        keepAliveConnection?.let {
            runCatching { service.unbindService(it) }
                .onFailure { Timber.w(it, "keepalive unbind previous") }
        }
        keepAliveConnection = null

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                logI("keepalive provider connected: $name")
                val provider = IVoiceInputProvider.Stub.asInterface(binder)
                val connection = this
                service.lifecycleScope.launch(Dispatchers.IO) {
                    val available = runCatching { provider.isAvailable() }
                        .onFailure { Timber.w(it, "keepalive isAvailable failed") }
                        .getOrDefault(false)
                    logI("keepalive provider available=$available name=$name")
                    delay(KEEPALIVE_BIND_TIMEOUT_MS)
                    withContext(Dispatchers.Main.immediate) {
                        releaseKeepAlive(service, connection)
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                logI("keepalive provider disconnected: $name")
                if (keepAliveConnection === this) {
                    keepAliveConnection = null
                }
            }

            override fun onBindingDied(name: ComponentName) {
                logW("keepalive binding died: $name")
                releaseKeepAlive(service, this)
            }

            override fun onNullBinding(name: ComponentName) {
                logW("keepalive null binding: $name")
                releaseKeepAlive(service, this)
            }
        }

        keepAliveConnection = connection
        logI("keepalive binding provider component=$providerComponent action=$action")
        val bound = try {
            service.bindService(
                Intent(action).apply {
                    component = providerComponent
                    // Allow waking the provider even if the user swiped it away or
                    // force-stopped it (package in stopped state). Without this flag
                    // Android rejects the bind and the provider can't be prewarmed.
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                },
                connection,
                Context.BIND_AUTO_CREATE,
            )
        } catch (e: SecurityException) {
            Timber.w(e, "keepalive bind denied"); false
        } catch (e: RuntimeException) {
            Timber.w(e, "keepalive bind failed"); false
        }
        if (!bound) {
            logW("keepalive bindService returned false")
            // Even when bindService returns false the ServiceConnection is
            // registered; it must be unbound or it leaks (ServiceConnectionLeaked).
            // This is the common case on OEMs that force-stopped the plugin
            // process: the bind is rejected but the connection still lingers.
            runCatching { service.unbindService(connection) }
                .onFailure { Timber.w(it, "keepalive unbind after failed bind") }
            keepAliveConnection = null
            return
        }
        service.lifecycleScope.launch {
            delay(KEEPALIVE_BIND_TIMEOUT_MS)
            if (keepAliveConnection === connection) {
                logW("keepalive bind timed out without onServiceConnected")
                releaseKeepAlive(service, connection)
            }
        }
    }

    // MVP integration point. Caller wires this to whatever UI starts voice
    // input (long-press of a key, dedicated mic button, etc.). For the MVP
    // segment text is committed via service.commitText; later this can be
    // replaced with a candidate-bar push.
    fun start(
        service: FcitxInputMethodService,
        id: String,
        onReady: () -> Unit,
        onPartialResult: (String) -> Unit,
        onError: (String) -> Unit,
        onLevel: (Int) -> Unit = {},
        onFinished: () -> Unit = {},
        onStatus: (String) -> Unit = {},
    ) {
        logI("start requested id=$id")
        onStatus(appContext.getString(org.fcitx.fcitx5.android.R.string.voice_status_connecting))
        tlogI("Starting voice input provider: $id")

        if (!VoiceInputAudioCapture.hasPermission(service)) {
            logW("RECORD_AUDIO permission not granted")
            onError(appContext.getString(org.fcitx.fcitx5.android.R.string.voice_error_no_permission))
            return
        }

        cancelWarmConnectionRelease()
        floatingFallbackActive = false
        floatingFallbackComponent = null
        if (activeProvider != null && activeProviderId != id) {
            releaseProviderConnection(service, callStopSession = true)
        }

        val callback = object : IVoiceInputCallback.Stub() {
            override fun onReady() {
                logI("provider ready")
                sessionReady = true
                service.lifecycleScope.launch {
                    onProviderReady(service, onLevel, onError)
                    onReady()
                }
            }

            override fun onVolumeLevel(rms: Int) {
                service.lifecycleScope.launch { onLevel(rms) }
            }

            override fun onPartialResult(text: String?) {
                val t = text.orEmpty()
                if (t.isNotBlank()) {
                    logI("partial result len=${t.length}: $t")
                    service.lifecycleScope.launch {
                        service.setVoiceComposingText(t)
                        onPartialResult(t)
                    }
                }
            }

            override fun onSegmentFinal(text: String?) {
                val t = text.orEmpty()
                if (t.isNotBlank()) {
                    logI("segment final len=${t.length}: $t")
                    tlogI("Voice segment final: $t")
                    service.lifecycleScope.launch { service.commitText(t) }
                } else {
                    logI("segment final blank")
                    service.lifecycleScope.launch { service.clearVoiceComposingText() }
                }
            }

            override fun onSessionEnded() {
                logI("provider session ended")
                tlogI("Voice provider session ended")
                // Any partial that wasn't followed by a final (e.g. provider
                // discarded it via speaker filter / VAD silence / cancel) is
                // now an orphan — clear it instead of leaving stale composing.
                service.lifecycleScope.launch {
                    service.clearVoiceComposingText()
                    onFinished()
                }
                stopSession(service, keepConnectionWarm = true)
            }

            override fun onError(code: Int, message: String?) {
                logW("provider error $code: $message")
                Timber.w("Voice provider error $code: $message")
                service.lifecycleScope.launch {
                    service.clearVoiceComposingText()
                    onFinished()
                    onError(message ?: appContext.getString(org.fcitx.fcitx5.android.R.string.voice_error_failed))
                }
                stopSession(service, keepConnectionWarm = false)
            }
        }

        activeCallback = callback
        finishing = false
        voiceSessionTerminalized = false
        sessionReady = false
        sessionActive = true
        ContextCompat.getMainExecutor(appContext).execute { sessionStartedCallback?.invoke() }

        startPreRollCapture(service, onLevel, onError)

        VoiceInputProviderRegistry.get(id)?.let { entry ->
            tlogI("Starting registered voice input provider: $id")
            activeProviderId = id
            activeProvider = entry.provider
            activeSessionConfig = getPreferredSessionConfig(entry.provider)
            if (!callProviderSafely(onError) {
                    configureProvider(entry.provider, activeSessionConfig)
                    entry.provider.startSession(callback)
                }) {
                stopSession(service, keepConnectionWarm = false)
                return
            }
            return
        }

        activeProvider?.let { provider ->
            logI("reusing warm provider connection id=$id")
            activeSessionConfig = getPreferredSessionConfig(provider)
            if (!callProviderSafely(onError) {
                    configureProvider(provider, activeSessionConfig)
                    provider.startSession(callback)
                }) {
                stopSession(service, keepConnectionWarm = false)
                return
            }
            // A warm connection can still stall on onReady if the provider had
            // idle-released its model and the reload hangs. Fall back to a clean fresh
            // bind (which starts a new provider session generation) instead of leaving
            // the IME stuck in "connecting".
            service.lifecycleScope.launch {
                delay(ACTIVE_READY_TIMEOUT_MS)
                if (!voiceSessionTerminalized && activeProvider === provider && !sessionReady) {
                    logW("warm provider ready timed out; falling back to fresh bind")
                    releaseProviderConnection(service, callStopSession = true)
                    // activeProvider is now null, so this re-entry takes the cold-bind
                    // path (with its own bounded retry), not the warm-reuse path.
                    start(service, id, onReady, onPartialResult, onError, onLevel, onFinished, onStatus)
                }
            }
            return
        }

        val providerComponent = ComponentName.unflattenFromString(id.removePrefix(ID_PREFIX))
        if (providerComponent == null) {
            logW("invalid provider id=$id")
            onError(appContext.getString(org.fcitx.fcitx5.android.R.string.voice_error_invalid_provider))
            stopSession(service, keepConnectionWarm = false)
            return
        }
        val action = listProviders(service).firstOrNull { it.id == id }?.action
        if (action == null) {
            logW("provider unavailable id=$id")
            onError(appContext.getString(org.fcitx.fcitx5.android.R.string.voice_error_not_available))
            stopSession(service, keepConnectionWarm = false)
            return
        }

        lateinit var connectionRef: ServiceConnection
        var bindAttempt = 0

        fun abortActiveVoiceSession(message: String) {
            if (voiceSessionTerminalized || activeConnection !== connectionRef) return
            voiceSessionTerminalized = true
            service.lifecycleScope.launch {
                service.clearVoiceComposingText()
                onStatus("")
                onFinished()
                onError(message)
            }
            stopSession(service, keepConnectionWarm = false)
        }

        // Rebind to the (already running) provider after a ready timeout. Keeps the
        // pre-roll capture and session state intact; only the AIDL connection is
        // recreated so the provider starts a fresh session generation.
        fun rebindForRetry(previous: ServiceConnection) {
            runCatching { service.unbindService(previous) }
                .onFailure { Timber.w(it, "unbind before rebind") }
            activeConnection = null
            activeProvider = null
            activeProviderBinder = null
            activeProviderDeathRecipient = null
        }

        lateinit var attemptBind: () -> Unit
        attemptBind = fun() {
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    logI("provider connected: $name")
                    tlogI("Voice input provider connected: $name")
                    activeProviderBinder = binder
                    val deathRecipient = IBinder.DeathRecipient {
                        logW("provider binder died: $name")
                        service.lifecycleScope.launch { abortActiveVoiceSession(appContext.getString(org.fcitx.fcitx5.android.R.string.voice_error_disconnected)) }
                    }
                    activeProviderDeathRecipient = deathRecipient
                    try {
                        binder.linkToDeath(deathRecipient, 0)
                    } catch (e: RemoteException) {
                        Timber.w(e, "provider binder already dead")
                        abortActiveVoiceSession(appContext.getString(org.fcitx.fcitx5.android.R.string.voice_error_disconnected))
                        return
                    }
                    val provider = IVoiceInputProvider.Stub.asInterface(binder)
                    activeProvider = provider
                    activeProviderId = id
                    activeSessionConfig = getPreferredSessionConfig(provider)
                    val stillActiveSession = activeConnection === this &&
                        activeCallback === callback &&
                        sessionActive &&
                        !voiceSessionTerminalized
                    if (!stillActiveSession) {
                        logI("provider connected while no active voice session; keeping warm only")
                        return
                    }
                    // The provider is connected, but the session is not usable until it
                    // reports onReady (model loads asynchronously). Reflect that in the UI
                    // so the user isn't left staring at "connecting".
                    if (!sessionReady) {
                        onStatus(appContext.getString(org.fcitx.fcitx5.android.R.string.voice_status_loading))
                    }
                    if (!callProviderSafely(onError) {
                            configureProvider(provider, activeSessionConfig)
                            provider.startSession(callback)
                        }) {
                        stopSession(service, keepConnectionWarm = false)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    logI("provider disconnected: $name")
                    abortActiveVoiceSession(appContext.getString(org.fcitx.fcitx5.android.R.string.voice_error_disconnected))
                }

                override fun onBindingDied(name: ComponentName) {
                    logW("provider binding died: $name")
                    abortActiveVoiceSession(appContext.getString(org.fcitx.fcitx5.android.R.string.voice_error_disconnected))
                }

                override fun onNullBinding(name: ComponentName) {
                    logW("provider null binding: $name")
                    abortActiveVoiceSession(appContext.getString(org.fcitx.fcitx5.android.R.string.voice_error_null_binding))
                }
            }
            connectionRef = connection
            activeConnection = connection
            bindAttempt++
            logI("binding provider component=$providerComponent action=$action attempt=$bindAttempt")
            onStatus(appContext.getString(org.fcitx.fcitx5.android.R.string.voice_status_connecting))
            tlogI("Binding voice input provider: component=$providerComponent action=$action")
            val bound = try {
                service.bindService(
                    Intent(action).apply {
                        component = providerComponent
                        // Wake the provider even from a stopped state (user swiped it
                        // away or force-stopped it), otherwise the bind is rejected.
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    },
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            } catch (e: SecurityException) {
                Timber.w(e, "bind denied"); false
            } catch (e: RuntimeException) {
                Timber.w(e, "bind failed"); false
            }
            logI("bindService returned $bound")
            if (!bound) {
                // Even when bindService returns false the ServiceConnection is
                // registered and must be unbound, or it leaks (ServiceConnectionLeaked
                // at IMS destroy). This is the common OEM case where the plugin process
                // was force-stopped: the bind is rejected but the connection lingers.
                runCatching { service.unbindService(connection) }
                    .onFailure { Timber.w(it, "unbind after failed bind") }
                activeConnection = null
                activeCallback = null
                activeProviderBinder = null
                activeProviderDeathRecipient = null
                logW("bindService returned false")
                onStatus(appContext.getString(org.fcitx.fcitx5.android.R.string.voice_status_opening))
                // Pre-roll is no longer useful (no AIDL provider to feed); release the mic.
                activeCapture?.let {
                    runCatching { it.stop() }.onFailure { Timber.w(it, "stop pre-roll on bind-failed") }
                }
                activeCapture = null
                resetPreRoll()
                val floatingComponent = startFloatingFallback(service, providerComponent)
                if (floatingComponent != null) {
                    sessionActive = false
                    finishing = false
                    floatingFallbackActive = true
                    floatingFallbackComponent = floatingComponent
                    onStatus(appContext.getString(org.fcitx.fcitx5.android.R.string.voice_status_loading))
                    service.lifecycleScope.launch {
                        delay(1200)
                        onStatus(appContext.getString(org.fcitx.fcitx5.android.R.string.voice_status_floating_ready))
                    }
                } else {
                    onStatus("")
                    onError(appContext.getString(org.fcitx.fcitx5.android.R.string.voice_error_cannot_bind))
                    stopSession(service, keepConnectionWarm = false)
                }
            } else {
                service.lifecycleScope.launch {
                    delay(ACTIVE_BIND_TIMEOUT_MS)
                    if (!voiceSessionTerminalized && activeConnection === connection && activeProvider == null) {
                        logW("provider bind timed out without onServiceConnected")
                        abortActiveVoiceSession(appContext.getString(org.fcitx.fcitx5.android.R.string.voice_error_bind_timeout))
                    }
                }
                // Second guard: the provider may connect quickly but never emit onReady
                // (e.g. a cold-start model load hangs). Retry the bind once before giving
                // up so the IME never stays stuck in "connecting"/"loading" indefinitely.
                service.lifecycleScope.launch {
                    delay(ACTIVE_READY_TIMEOUT_MS)
                    if (!voiceSessionTerminalized && activeConnection === connection && !sessionReady) {
                        if (bindAttempt < MAX_BIND_ATTEMPTS) {
                            logW("provider ready timed out; rebinding (attempt $bindAttempt)")
                            onStatus(appContext.getString(org.fcitx.fcitx5.android.R.string.voice_status_connecting))
                            rebindForRetry(connection)
                            attemptBind()
                        } else {
                            logW("provider ready timed out without onReady after retry")
                            abortActiveVoiceSession(appContext.getString(org.fcitx.fcitx5.android.R.string.voice_error_bind_timeout))
                        }
                    }
                }
            }
        }
        attemptBind()
    }

    private fun startFloatingFallback(
        service: FcitxInputMethodService,
        providerComponent: ComponentName,
    ): ComponentName? {
        val action = BuildConfig.APPLICATION_ID + VoiceInputIpc.START_FLOATING_ACTION_SUFFIX
        val serviceClass = providerComponent.className.substringBeforeLast('.') + ".FloatingService"
        val component = ComponentName(providerComponent.packageName, serviceClass)
        val intent = Intent(action).apply {
            this.component = component
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        return try {
            ContextCompat.startForegroundService(service, intent)
            logI("started floating fallback component=${intent.component} action=$action")
            component
        } catch (e: Throwable) {
            logW("floating fallback failed", e)
            null
        }
    }

    private data class SessionConfig(
        val audio: VoiceInputAudioCapture.Config = VoiceInputAudioCapture.Config(),
        val silenceMs: Long = DEFAULT_SILENCE_MS,
    )

    private fun getPreferredSessionConfig(provider: IVoiceInputProvider): SessionConfig {
        val params = runCatching { provider.preferredConfig }
            .onFailure { Timber.w(it, "getPreferredConfig failed; using default voice input config") }
            .getOrNull()
        params?.classLoader = VoiceInputProviderManager::class.java.classLoader
        val defaults = VoiceInputAudioCapture.Config()
        val audio = VoiceInputAudioCapture.Config(
            sampleRate = params?.getInt(VoiceInputIpc.ConfigKeys.SAMPLE_RATE, defaults.sampleRate)
                ?.takeIf { it > 0 } ?: defaults.sampleRate,
            bitsPerSample = params?.getInt(VoiceInputIpc.ConfigKeys.BITS_PER_SAMPLE, defaults.bitsPerSample)
                ?.takeIf { it == 16 } ?: defaults.bitsPerSample,
            channels = params?.getInt(VoiceInputIpc.ConfigKeys.CHANNELS, defaults.channels)
                ?.takeIf { it == 1 } ?: defaults.channels,
        )
        return SessionConfig(
            audio = audio,
            silenceMs = params?.getLong(VoiceInputIpc.ConfigKeys.SILENCE_MS, DEFAULT_SILENCE_MS)
                ?.takeIf { it > 0 } ?: DEFAULT_SILENCE_MS,
        ).also {
            logI("provider preferred session config: $it")
        }
    }

    private fun configureProvider(provider: IVoiceInputProvider, sessionConfig: SessionConfig) {
        val params = Bundle().apply {
            putInt(VoiceInputIpc.ConfigKeys.SAMPLE_RATE, sessionConfig.audio.sampleRate)
            putInt(VoiceInputIpc.ConfigKeys.BITS_PER_SAMPLE, sessionConfig.audio.bitsPerSample)
            putInt(VoiceInputIpc.ConfigKeys.CHANNELS, sessionConfig.audio.channels)
            putLong(VoiceInputIpc.ConfigKeys.SILENCE_MS, sessionConfig.silenceMs)
        }
        provider.configure(params)
    }

    private fun releaseKeepAlive(context: Context, connection: ServiceConnection) {
        if (keepAliveConnection !== connection) return
        runCatching { context.unbindService(connection) }
            .onFailure { Timber.w(it, "keepalive unbind") }
        keepAliveConnection = null
    }

    private inline fun callProviderSafely(
        onError: (String) -> Unit,
        block: () -> Unit,
    ): Boolean = try {
        block(); true
    } catch (e: Throwable) {
        Timber.w(e, "provider call failed")
        onError(e.message ?: appContext.getString(org.fcitx.fcitx5.android.R.string.voice_error_call_failed))
        false
    }

    private fun startPreRollCapture(
        service: FcitxInputMethodService,
        onLevel: (Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (activeCapture != null) return
        synchronized(preRollLock) {
            preRollBuffer.clear()
            preRollActive = true
        }
        val preRollConfig = VoiceInputAudioCapture.Config()
        val capture = VoiceInputAudioCapture(
            context = service,
            config = preRollConfig,
            onPcm = { buf, off, len, pts ->
                val packet = QueuedAudio(buf.copyOfRange(off, off + len), pts)
                val pushDirect = synchronized(preRollLock) {
                    if (preRollActive) {
                        preRollBuffer.addLast(packet)
                        while (preRollBuffer.size > 1 &&
                            pts - preRollBuffer.first().ptsMs > PRE_ROLL_MAX_MS
                        ) {
                            preRollBuffer.removeFirst()
                        }
                        false
                    } else {
                        true
                    }
                }
                if (pushDirect) {
                    activeAudioFeedQueue?.trySend(packet)
                }
            },
            onLevel = { rms -> service.lifecycleScope.launch { onLevel(rms) } },
            onError = { msg ->
                service.lifecycleScope.launch { onError(msg) }
                stop(service)
            },
        )
        if (!capture.start()) {
            logW("pre-roll capture start failed; will fall back to onReady-time capture")
            resetPreRoll()
            return
        }
        activeCapture = capture
        logI("pre-roll capture started config=$preRollConfig")
    }

    private fun onProviderReady(
        service: FcitxInputMethodService,
        onLevel: (Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        val preRollCapture = activeCapture
        val configMatches = activeSessionConfig.audio == VoiceInputAudioCapture.Config()
        if (preRollCapture == null || !configMatches) {
            if (preRollCapture != null) {
                logI("discarding pre-roll: config mismatch desired=${activeSessionConfig.audio}")
                resetPreRoll()
                runCatching { preRollCapture.stop() }
                    .onFailure { Timber.w(it, "stop pre-roll capture") }
                activeCapture = null
            }
            startCapture(service, onLevel, onError)
            return
        }
        val queue = Channel<QueuedAudio>(capacity = Channel.UNLIMITED)
        val drained: List<QueuedAudio>
        synchronized(preRollLock) {
            activeAudioFeedQueue = queue
            drained = preRollBuffer.toList()
            preRollBuffer.clear()
            preRollActive = false
        }
        logI("pre-roll flush: packets=${drained.size}")
        drained.forEach { queue.trySend(it) }
        activeAudioFeedJob = launchAudioFeedJob(service, queue)
    }

    private fun launchAudioFeedJob(
        service: FcitxInputMethodService,
        queue: Channel<QueuedAudio>,
    ): Job = service.lifecycleScope.launch(Dispatchers.IO) {
        for (packet in queue) {
            val currentProvider = activeProvider ?: continue
            try {
                currentProvider.feedAudio(
                    packet.pcm, 0, packet.pcm.size, packet.ptsMs,
                )
            } catch (e: RemoteException) {
                // Binder is dead — provider crashed or was killed. Don't keep
                // looping silently; surface via the callback so the UI clears
                // its "listening" state and stopSession unwinds.
                Timber.w(e, "feedAudio failed — provider gone, aborting session")
                val cb = activeCallback
                withContext(Dispatchers.Main) {
                    if (cb != null) {
                        runCatching {
                            cb.onError(
                                VoiceInputIpc.ErrorCodes.UNKNOWN,
                                "Provider disconnected",
                            )
                        }
                    } else {
                        stopSession(service, keepConnectionWarm = false)
                    }
                }
                return@launch
            }
        }
    }

    private fun resetPreRoll() {
        synchronized(preRollLock) {
            preRollBuffer.clear()
            preRollActive = false
        }
    }

    private fun startCapture(
        service: FcitxInputMethodService,
        onLevel: (Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (activeCapture != null) return
        activeProvider ?: return
        val audioQueue = Channel<QueuedAudio>(capacity = Channel.UNLIMITED)
        activeAudioFeedQueue = audioQueue
        activeAudioFeedJob = launchAudioFeedJob(service, audioQueue)
        val capture = VoiceInputAudioCapture(
            context = service,
            config = activeSessionConfig.audio,
            onPcm = { buf, off, len, pts ->
                audioQueue.trySend(
                    QueuedAudio(
                        pcm = buf.copyOfRange(off, off + len),
                        ptsMs = pts,
                    ),
                )
            },
            onLevel = { rms -> service.lifecycleScope.launch { onLevel(rms) } },
            onError = { msg ->
                service.lifecycleScope.launch { onError(msg) }
                stop(service)
            },
        )
        activeCapture = capture
        if (!capture.start()) {
            logW("capture start failed")
            activeCapture = null
            activeAudioFeedQueue?.close()
            activeAudioFeedQueue = null
            activeAudioFeedJob?.cancel()
            activeAudioFeedJob = null
        } else {
            logI("capture started")
        }
    }

    private fun finish(context: Context = appContext) {
        if (floatingFallbackActive) {
            stopFloatingFallback(context)
            return
        }
        if (finishing) {
            logI("finish ignored: already finishing")
            return
        }
        finishing = true
        logI(
            "finish provider=${activeProvider != null} " +
                "connection=${activeConnection != null} capture=${activeCapture != null}"
        )
        tlogI(
            "Finishing voice input provider: provider=${activeProvider != null} " +
                "connection=${activeConnection != null} capture=${activeCapture != null}"
        )
        activeCapture?.let {
            runCatching { it.stop() }.onFailure { Timber.w(it, "capture stop") }
        }
        activeCapture = null
        resetPreRoll()

        val queue = activeAudioFeedQueue
        val feedJob = activeAudioFeedJob
        val scope = (context as? FcitxInputMethodService)?.lifecycleScope
        if (queue != null && feedJob != null && scope != null) {
            scope.launch {
                queue.close()
                val drained = withTimeoutOrNull(FEED_DRAIN_TIMEOUT_MS) { feedJob.join() } != null
                if (!drained) logW("feed queue drain timed out before endStream")
                sendEndStream(context)
            }
        } else {
            sendEndStream(context)
        }
    }

    private fun sendEndStream(context: Context) {
        try {
            activeProvider?.endStream()
            logI("endStream sent")
        } catch (e: Throwable) {
            serviceFinished(context, null)
            Timber.w(e, "endStream")
            stopSession(context, keepConnectionWarm = false)
        }
    }

    private fun serviceFinished(context: Context, onFinished: (() -> Unit)?) {
        if (context is FcitxInputMethodService && onFinished != null) {
            context.lifecycleScope.launch { onFinished() }
        }
    }

    fun stop(context: Context = appContext) {
        if (floatingFallbackActive) {
            stopFloatingFallback(context)
            return
        }
        stopSession(context, keepConnectionWarm = false)
    }

    private fun stopSession(context: Context = appContext, keepConnectionWarm: Boolean) {
        voiceSessionTerminalized = true
        logI(
            "stop provider=${activeProvider != null} " +
                "connection=${activeConnection != null} capture=${activeCapture != null} " +
                "keepWarm=$keepConnectionWarm"
        )
        tlogI(
            "Stopping voice input provider: provider=${activeProvider != null} " +
                "connection=${activeConnection != null} capture=${activeCapture != null} " +
                "keepWarm=$keepConnectionWarm"
        )
        activeCapture?.let {
            runCatching { it.stop() }.onFailure { Timber.w(it, "capture stop") }
        }
        activeCapture = null
        resetPreRoll()
        activeAudioFeedQueue?.close()
        activeAudioFeedQueue = null
        activeAudioFeedJob?.cancel()
        activeAudioFeedJob = null
        try {
            activeProvider?.stopSession()
        } catch (e: Throwable) {
            Timber.w(e, "stopSession")
        }
        activeCallback = null
        finishing = false
        sessionActive = false
        floatingFallbackActive = false
        floatingFallbackComponent = null
        if (keepConnectionWarm && activeProvider != null && activeConnection != null) {
            scheduleWarmConnectionRelease(context)
        } else {
            releaseProviderConnection(context, callStopSession = false)
        }
        keepAliveConnection?.let {
            runCatching { context.unbindService(it) }
                .onFailure { Timber.w(it, "keepalive unbind on stop") }
        }
        keepAliveConnection = null
        ContextCompat.getMainExecutor(appContext).execute { sessionEndedCallback?.invoke() }
    }

    private fun scheduleWarmConnectionRelease(context: Context) {
        val generation = ++warmConnectionReleaseGeneration
        logI("schedule warm provider release in ${WARM_CONNECTION_HOLD_MS}ms")
        if (context is FcitxInputMethodService) {
            context.lifecycleScope.launch {
                delay(WARM_CONNECTION_HOLD_MS)
                if (warmConnectionReleaseGeneration == generation && !isActive()) {
                    releaseProviderConnection(context, callStopSession = true)
                }
            }
        }
    }

    private fun cancelWarmConnectionRelease() {
        warmConnectionReleaseGeneration++
    }

    private fun releaseProviderConnection(context: Context, callStopSession: Boolean) {
        voiceSessionTerminalized = true
        cancelWarmConnectionRelease()
        activeAudioFeedQueue?.close()
        activeAudioFeedQueue = null
        activeAudioFeedJob?.cancel()
        activeAudioFeedJob = null
        if (callStopSession) {
            try {
                activeProvider?.stopSession()
            } catch (e: Throwable) {
                Timber.w(e, "stopSession")
            }
        }
        activeProviderDeathRecipient?.let { deathRecipient ->
            activeProviderBinder?.let { binder ->
                runCatching { binder.unlinkToDeath(deathRecipient, 0) }
                    .onFailure { Timber.w(it, "unlink binder death") }
            }
        }
        activeProviderBinder = null
        activeProviderDeathRecipient = null
        activeProvider = null
        activeProviderId = null
        activeConnection?.let {
            runCatching { context.unbindService(it) }
                .onFailure { Timber.w(it, "unbind") }
        }
        activeConnection = null
    }

    private fun stopFloatingFallback(context: Context) {
        val component = floatingFallbackComponent
        floatingFallbackActive = false
        floatingFallbackComponent = null
        finishing = false
        sessionActive = false
        if (component != null) {
            val intent = Intent("stop_service").apply { this.component = component }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Timber.w(it, "stop floating fallback failed") }
        }
        ContextCompat.getMainExecutor(appContext).execute { sessionEndedCallback?.invoke() }
        voiceFinishedCallback?.invoke()
    }
}
