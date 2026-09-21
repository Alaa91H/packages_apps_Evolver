package com.android.systemui.axdynamicbar.data.source

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.PersistableBundle
import android.util.Log
import android.util.Size
import androidx.core.content.FileProvider
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.res.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryInteractor
import com.android.systemui.statusbar.policy.BatteryController
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@SysUISingleton
class SystemIslandManager
@Inject
constructor(
    @Application private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @Main private val mainHandler: Handler,
    private val batteryInteractor: BatteryInteractor,
    private val batteryController: BatteryController,
) {
    companion object {
        private const val TAG = "SystemIslandManager"
        private const val MAX_CLIPBOARD_HISTORY = 10
        private const val PREFS_NAME = "ax_dynamic_bar_prefs"
        private const val KEY_CLIPBOARD_STASH = "clipboard_stash"
        private const val CLIPBOARD_CACHE_DIR = "clipboard_cache"
        private const val FILE_PROVIDER_AUTHORITY = "com.android.systemui.fileprovider"
        private const val EXTRA_DYNAMIC_BAR_SELF_COPY =
            "com.android.systemui.axdynamicbar.SELF_COPY"
        private const val EXTRA_SUPPRESS_CLIPBOARD_OVERLAY =
            "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY"
        private const val MAX_CLIPBOARD_IMAGE_WIDTH_PX = 512
        private const val MAX_CLIPBOARD_IMAGE_HEIGHT_PX = 2048
    }

    private val _chargingEvent = MutableStateFlow<IslandEvent.Charging?>(null)
    val chargingEvent: StateFlow<IslandEvent.Charging?> = _chargingEvent.asStateFlow()

    private val _ringerEvent = MutableStateFlow<IslandEvent.RingerMode?>(null)
    val ringerEvent: StateFlow<IslandEvent.RingerMode?> = _ringerEvent.asStateFlow()

    private val _clipboardEvent = MutableStateFlow<IslandEvent.Clipboard?>(null)
    val clipboardEvent: StateFlow<IslandEvent.Clipboard?> = _clipboardEvent.asStateFlow()

    var onChargingStarted: ((IslandEvent.Charging) -> Unit)? = null

    var onRingerChanged: ((IslandEvent.RingerMode) -> Unit)? = null

    var onClipboardCopied: ((IslandEvent.Clipboard) -> Unit)? = null

    private val clipboardManager: ClipboardManager by lazy {
        context.getSystemService(ClipboardManager::class.java)
    }

    private var wasCharging = false
    @Volatile var chargingDismissed = false
    private var lastRingerMode = -1
    private var batteryJob: Job? = null

    private var listening = false
    @Volatile private var lastClipboardToken: String? = null
    @Volatile private var clipboardGeneration = 0L

    private val clipboardHistory = mutableListOf<IslandEvent.ClipboardItem>()

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val clipboardCacheDir: File by lazy {
        File(context.cacheDir, CLIPBOARD_CACHE_DIR).apply { mkdirs() }
    }

    private var persistJob: Job? = null

    private val ringerReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val mode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)
                if (mode < 0) return
                if (lastRingerMode != -1 && lastRingerMode != mode) {
                    val label =
                        when (mode) {
                            AudioManager.RINGER_MODE_SILENT -> context.getString(R.string.ax_dynamic_bar_silent)
                            AudioManager.RINGER_MODE_VIBRATE -> context.getString(R.string.ax_dynamic_bar_vibrate)
                            AudioManager.RINGER_MODE_NORMAL -> context.getString(R.string.ax_dynamic_bar_ring)
                            else -> return
                        }
                    val event = IslandEvent.RingerMode(mode = mode, label = label)
                    _ringerEvent.value = event
                    onRingerChanged?.invoke(event)
                }
                lastRingerMode = mode
            }
        }

    private val clipboardListener =
        ClipboardManager.OnPrimaryClipChangedListener {
            if (!clipboardManager.hasPrimaryClip()) return@OnPrimaryClipChangedListener

            val clip =
                try {
                    clipboardManager.primaryClip
                } catch (e: SecurityException) {
                    Log.w(TAG, "Unable to read clipboard", e)
                    null
                } ?: return@OnPrimaryClipChangedListener

            if (clip.itemCount <= 0) return@OnPrimaryClipChangedListener
            val item = clip.getItemAt(0)
            val desc = clip.description

            // Only suppress copies explicitly authored by Dynamic Bar. Other SystemUI producers
            // (notably screenshot-to-clipboard) must remain observable.
            if (desc.extras?.getBoolean(EXTRA_DYNAMIC_BAR_SELF_COPY, false) == true) {
                invalidatePendingClipboardWork()
                return@OnPrimaryClipChangedListener
            }

            // Sensitive clips remain available to the platform/default IME but are never rendered
            // or persisted by Dynamic Bar.
            if (desc.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true) {
                invalidatePendingClipboardWork()
                _clipboardEvent.value = null
                return@OnPrimaryClipChangedListener
            }

            // Avoid coerceToText(): it can synchronously dereference arbitrary content URIs.
            val rawText = item.text?.toString() ?: ""
            val preview = rawText.trim()
            val isUrl =
                preview.startsWith("http://") ||
                    preview.startsWith("https://") ||
                    preview.startsWith("www.")
            val isImage = desc.hasMimeType("image/*") && item.uri != null
            val sourceUri = if (isImage) item.uri else null
            val label = desc.label?.toString() ?: ""

            if (preview.isEmpty() && !isImage && label.isEmpty()) {
                return@OnPrimaryClipChangedListener
            }

            // ClipboardService can rebroadcast the same clip after text classification. Android
            // keeps ClipDescription.timestamp stable for that mutation, so use it instead of a
            // timing heuristic that can swallow legitimate rapid repeated copies.
            val clipTimestamp = desc.timestamp
            val token =
                if (clipTimestamp > 0L) {
                    buildString {
                        append(clipTimestamp)
                        append('\u0000')
                        append(preview)
                        append('\u0000')
                        append(label)
                        append('\u0000')
                        append(sourceUri?.toString() ?: "")
                    }
                } else {
                    null
                }
            if (token != null && token == lastClipboardToken) {
                return@OnPrimaryClipChangedListener
            }
            lastClipboardToken = token

            val generation = nextClipboardGeneration()
            val itemId =
                if (clipTimestamp > 0L) clipTimestamp
                else System.currentTimeMillis()

            if (isImage && sourceUri != null) {
                applicationScope.launch(backgroundDispatcher) {
                    val cachedUri = cacheClipboardImage(sourceUri, itemId)
                    if (
                        !commitClipboardEvent(
                            itemId,
                            preview,
                            label,
                            isUrl,
                            true,
                            cachedUri,
                            generation,
                            callbackOnMainThread = true,
                        )
                    ) {
                        cleanupCachedImage(itemId)
                    }
                }
            } else {
                commitClipboardEvent(
                    itemId,
                    preview,
                    label,
                    isUrl,
                    false,
                    null,
                    generation,
                )
            }
        }

    private fun cacheClipboardImage(sourceUri: Uri, itemId: Long): Uri? {
        return try {
            val bitmap =
                context.contentResolver.loadThumbnail(
                    sourceUri,
                    Size(MAX_CLIPBOARD_IMAGE_WIDTH_PX, MAX_CLIPBOARD_IMAGE_HEIGHT_PX),
                    null,
                )
            val file = File(clipboardCacheDir, "clip_$itemId.webp")
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
            }
            bitmap.recycle()
            FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache clipboard image", e)
            null
        }
    }

    private fun commitClipboardEvent(
        itemId: Long,
        preview: String,
        label: String,
        isUrl: Boolean,
        isImage: Boolean,
        imageUri: Uri?,
        generation: Long,
        callbackOnMainThread: Boolean = false,
    ): Boolean {
        val clipItem =
            IslandEvent.ClipboardItem(
                id = itemId,
                preview = preview,
                label = label,
                isUrl = isUrl,
                isImage = isImage,
                imageUri = imageUri,
                timestamp = itemId,
            )

        val event: IslandEvent.Clipboard
        val historySnapshot: List<IslandEvent.ClipboardItem>
        synchronized(clipboardHistory) {
            if (generation != clipboardGeneration) return false

            clipboardHistory.removeAll { it.preview == preview && !isImage }
            clipboardHistory.add(0, clipItem)
            while (clipboardHistory.size > MAX_CLIPBOARD_HISTORY) {
                val removed = clipboardHistory.removeLast()
                cleanupCachedImage(removed.id)
            }
            historySnapshot = clipboardHistory.toList()
            event =
                IslandEvent.Clipboard(
                    label = label,
                    preview = preview,
                    isUrl = isUrl,
                    isImage = isImage,
                    imageUri = imageUri,
                    items = historySnapshot,
                )
        }

        persistClipboardHistory(historySnapshot, generation)
        _clipboardEvent.value = event

        if (callbackOnMainThread) {
            mainHandler.post {
                synchronized(clipboardHistory) {
                    if (generation == clipboardGeneration) {
                        onClipboardCopied?.invoke(event)
                    }
                }
            }
        } else {
            onClipboardCopied?.invoke(event)
        }
        return true
    }

    private fun nextClipboardGeneration(): Long =
        synchronized(clipboardHistory) {
            clipboardGeneration += 1L
            clipboardGeneration
        }

    private fun invalidatePendingClipboardWork() {
        synchronized(clipboardHistory) {
            clipboardGeneration += 1L
        }
    }

    private fun cleanupCachedImage(itemId: Long) {
        try {
            File(clipboardCacheDir, "clip_$itemId.webp").delete()
            File(clipboardCacheDir, "clip_$itemId.png").delete()
        } catch (_: Exception) {}
    }

    private var chargingListening = false
    private var ringerListening = false
    private var clipboardListening = false

    fun startCharging() {
        if (chargingListening) return
        chargingListening = true
        wasCharging = batteryController.isPluggedIn
        batteryJob?.cancel()
        batteryJob =
            applicationScope.launch(backgroundDispatcher) {
                combine(
                        batteryInteractor.isCharging,
                        batteryInteractor.level,
                        batteryInteractor.powerSave,
                        batteryInteractor.batteryTimeRemainingEstimate,
                    ) { isCharging: Boolean, level: Int?, isPowerSave: Boolean, timeEst: String? ->
                        ChargingSnapshot(isCharging, level, isPowerSave, timeEst)
                    }
                    .distinctUntilChanged()
                    .collect { snap ->
                        val wasChargingBefore = wasCharging
                        wasCharging = snap.isCharging
                        if (snap.isCharging && !wasChargingBefore && snap.level != null) {
                            chargingDismissed = false
                            val event =
                                IslandEvent.Charging(
                                    level = snap.level,
                                    isWireless = batteryController.isWirelessCharging,
                                    isPowerSave = snap.isPowerSave,
                                    timeRemaining = snap.timeEst,
                                )
                            _chargingEvent.value = event
                            onChargingStarted?.invoke(event)
                        } else if (snap.isCharging && wasChargingBefore && !chargingDismissed) {
                            _chargingEvent.value =
                                _chargingEvent.value?.copy(
                                    level = snap.level ?: _chargingEvent.value?.level ?: 0,
                                    isPowerSave = snap.isPowerSave,
                                    timeRemaining = snap.timeEst,
                                )
                        } else if (!snap.isCharging && wasChargingBefore) {
                            chargingDismissed = false
                            _chargingEvent.value = null
                        }
                    }
            }
    }

    fun stopCharging() {
        if (!chargingListening) return
        chargingListening = false
        batteryJob?.cancel()
        batteryJob = null
        _chargingEvent.value = null
    }

    fun startRinger() {
        if (ringerListening) return
        ringerListening = true
        lastRingerMode =
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode
        context.registerReceiver(
            ringerReceiver,
            IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION),
            null,
            mainHandler,
        )
    }

    fun stopRinger() {
        if (!ringerListening) return
        ringerListening = false
        try { context.unregisterReceiver(ringerReceiver) } catch (_: Exception) {}
        _ringerEvent.value = null
    }

    fun startClipboard() {
        if (clipboardListening) return
        clipboardListening = true
        loadClipboardHistory()
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
    }

    fun stopClipboard() {
        if (!clipboardListening) return
        clipboardListening = false
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        lastClipboardToken = null
        invalidatePendingClipboardWork()
        _clipboardEvent.value = null
    }

    fun startListening() {
        if (listening) return
        listening = true
        startCharging()
        startRinger()
        startClipboard()
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        stopCharging()
        stopRinger()
        stopClipboard()
    }

    fun clearRinger() {
        _ringerEvent.value = null
    }

    fun setRingerMode(mode: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.ringerMode = mode
    }

    fun getRingerMode(): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.ringerMode
    }

    fun emitRingerEvent(mode: Int) {
        val label =
            when (mode) {
                AudioManager.RINGER_MODE_SILENT -> context.getString(R.string.ax_dynamic_bar_silent)
                AudioManager.RINGER_MODE_VIBRATE -> context.getString(R.string.ax_dynamic_bar_vibrate)
                else -> context.getString(R.string.ax_dynamic_bar_ring)
            }
        val event = IslandEvent.RingerMode(mode = mode, label = label)
        _ringerEvent.value = event
        onRingerChanged?.invoke(event)
    }

    fun clearClipboard() {
        persistJob?.cancel()
        synchronized(clipboardHistory) {
            clipboardGeneration += 1L
            _clipboardEvent.value = null
            clipboardHistory.forEach { cleanupCachedImage(it.id) }
            clipboardHistory.clear()
            clipboardCacheDir.listFiles()?.forEach { it.delete() }
            prefs.edit().remove(KEY_CLIPBOARD_STASH).apply()
        }
    }

    fun clearCharging() {
        chargingDismissed = true
        _chargingEvent.value = null
    }

    fun removeClipboardItem(id: Long) {
        cleanupCachedImage(id)
        val event: IslandEvent.Clipboard?
        val historySnapshot: List<IslandEvent.ClipboardItem>
        val generation: Long
        synchronized(clipboardHistory) {
            clipboardHistory.removeAll { it.id == id }
            generation = clipboardGeneration
            historySnapshot = clipboardHistory.toList()
            event =
                if (clipboardHistory.isEmpty()) null
                else {
                    val latest = clipboardHistory.first()
                    _clipboardEvent.value?.copy(
                        label = latest.label,
                        preview = latest.preview,
                        isUrl = latest.isUrl,
                        isImage = latest.isImage,
                        imageUri = latest.imageUri,
                        items = historySnapshot,
                    )
                }
        }
        persistClipboardHistory(historySnapshot, generation)
        _clipboardEvent.value = event
    }

    fun copyToClipboard(text: String) {
        if (text.isEmpty()) return
        clipboardManager.setPrimaryClip(
            markDynamicBarSelfCopy(ClipData.newPlainText("Copied", text))
        )
    }

    fun copyUriToClipboard(uri: Uri, mimeType: String = "image/*") {
        try {
            clipboardManager.setPrimaryClip(
                markDynamicBarSelfCopy(
                    ClipData("Copied", arrayOf(mimeType), ClipData.Item(uri))
                )
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Failed to copy URI to clipboard, trying plain text", e)
            clipboardManager.setPrimaryClip(
                markDynamicBarSelfCopy(ClipData.newPlainText("Copied", uri.toString()))
            )
        }
    }

    private fun markDynamicBarSelfCopy(clip: ClipData): ClipData {
        val extras = clip.description.extras ?: PersistableBundle()
        extras.putBoolean(EXTRA_DYNAMIC_BAR_SELF_COPY, true)
        extras.putBoolean(EXTRA_SUPPRESS_CLIPBOARD_OVERLAY, true)
        clip.description.extras = extras
        return clip
    }

    fun openUrl(url: String) {
        if (url.isEmpty()) return
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            _clipboardEvent.value = null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open URL: $url", e)
        }
    }

    private fun persistClipboardHistory(
        history: List<IslandEvent.ClipboardItem>,
        generation: Long,
    ) {
        persistJob?.cancel()
        persistJob =
            applicationScope.launch(backgroundDispatcher) {
                try {
                    val arr = JSONArray()
                    history.forEach { item ->
                        arr.put(
                            JSONObject().apply {
                                put("id", item.id)
                                put("preview", item.preview)
                                put("label", item.label)
                                put("isUrl", item.isUrl)
                                put("isImage", item.isImage)
                                put("imageUri", item.imageUri?.toString() ?: "")
                                put("ts", item.timestamp)
                            }
                        )
                    }
                    synchronized(clipboardHistory) {
                        if (generation == clipboardGeneration) {
                            prefs.edit().putString(KEY_CLIPBOARD_STASH, arr.toString()).apply()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist clipboard history", e)
                }
            }
    }

    private fun loadClipboardHistory() {
        try {
            val json = prefs.getString(KEY_CLIPBOARD_STASH, null) ?: return
            val arr = JSONArray(json)
            synchronized(clipboardHistory) {
                clipboardHistory.clear()
                for (i in 0 until arr.length().coerceAtMost(MAX_CLIPBOARD_HISTORY)) {
                    val obj = arr.getJSONObject(i)
                    val id = obj.optLong("id", 0L)
                    val isImage = obj.optBoolean("isImage", false)
                    val imageUri =
                        if (isImage) {
                            val cachedWebp = File(clipboardCacheDir, "clip_$id.webp")
                            val cachedPng = File(clipboardCacheDir, "clip_$id.png")
                            when {
                                cachedWebp.exists() ->
                                    FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, cachedWebp)
                                cachedPng.exists() ->
                                    FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, cachedPng)
                                else -> null
                            }
                        } else null
                    clipboardHistory.add(
                        IslandEvent.ClipboardItem(
                            id = id,
                            preview = obj.optString("preview", ""),
                            label = obj.optString("label", ""),
                            isUrl = obj.optBoolean("isUrl", false),
                            isImage = isImage,
                            imageUri = imageUri,
                            timestamp = obj.optLong("ts", 0L),
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load clipboard history", e)
        }
    }

    private data class ChargingSnapshot(
        val isCharging: Boolean,
        val level: Int?,
        val isPowerSave: Boolean,
        val timeEst: String?,
    )
}
