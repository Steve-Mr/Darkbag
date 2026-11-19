package top.maary.darkbag.repository

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe Jetpack DataStore provider and serialized preference store for Darkbag.
 *
 * Automatically migrates legacy `camera_settings` SharedPreferences entries (including mixed-type
 * legacy keys such as `flash_mode`, `raw_output_type`, `hdr_burst_count`, and `default_focal_1x`),
 * ensures single-instance DataStore safety per canonical file path across app and Robolectric tests,
 * and delivers immediate in-memory snapshot updates paired with ordered async disk persistence
 * without blocking the Main thread during cold-start hydration.
 */
object DarkbagDataStoreProvider {

    private val storesByPath = ConcurrentHashMap<String, DarkbagPreferencesStore>()

    fun getStore(context: Context): DarkbagPreferencesStore {
        val appContext = context.applicationContext ?: context
        val file = appContext.preferencesDataStoreFile(PreferenceKeys.DATASTORE_NAME)
        val canonicalPath = file.canonicalPath
        return storesByPath[canonicalPath] ?: synchronized(this) {
            storesByPath.getOrPut(canonicalPath) {
                createStore(appContext, file)
            }
        }
    }

    private fun createStore(appContext: Context, file: File): DarkbagPreferencesStore {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            migrations = listOf(
                SharedPreferencesMigration(
                    context = appContext,
                    sharedPreferencesName = PreferenceKeys.LEGACY_PREFS_NAME
                ),
                LegacyPreferenceTypeMigration()
            ),
            scope = scope,
            produceFile = { file }
        )
        return DarkbagPreferencesStore(dataStore, scope)
    }

    @VisibleForTesting
    fun resetForTest(context: Context, deleteDataStoreFile: Boolean = true): DarkbagPreferencesStore? {
        val appContext = context.applicationContext ?: context
        val file = appContext.preferencesDataStoreFile(PreferenceKeys.DATASTORE_NAME)
        val canonicalPath = file.canonicalPath
        val removed = synchronized(this) {
            storesByPath.remove(canonicalPath)
        }
        removed?.close()
        if (deleteDataStoreFile) {
            runCatching { file.delete() }
        }
        return removed
    }
}

/**
 * Normalizes legacy polymorphic SharedPreferences values migrated by [SharedPreferencesMigration]
 * into their canonical DataStore types so typed reads never suffer ClassCastException.
 */
internal class LegacyPreferenceTypeMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val rawMap = currentData.asMap().entries.associate { it.key.name to it.value }
        return rawMap[PreferenceKeys.KEY_FLASH_MODE] is String ||
            rawMap[PreferenceKeys.KEY_RAW_OUTPUT_TYPE] is String ||
            rawMap[PreferenceKeys.KEY_HDR_BURST_COUNT] is String ||
            rawMap[PreferenceKeys.KEY_DEFAULT_FOCAL_1X] is Float
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val mutable = currentData.toMutablePreferences()
        val rawMap = currentData.asMap().entries.associate { it.key.name to it.value }

        (rawMap[PreferenceKeys.KEY_FLASH_MODE] as? String)?.let { str ->
            mutable.remove(PreferenceKeys.FLASH_MODE)
            mutable[PreferenceKeys.FLASH_MODE] = str.equals("on", ignoreCase = true)
        }

        (rawMap[PreferenceKeys.KEY_RAW_OUTPUT_TYPE] as? String)?.let { str ->
            mutable.remove(PreferenceKeys.RAW_OUTPUT_TYPE)
            val index = if (str.contains("Linear")) 1 else (str.toIntOrNull() ?: 0)
            mutable[PreferenceKeys.RAW_OUTPUT_TYPE] = index
        }

        (rawMap[PreferenceKeys.KEY_HDR_BURST_COUNT] as? String)?.let { str ->
            mutable.remove(PreferenceKeys.HDR_BURST_COUNT)
            mutable[PreferenceKeys.HDR_BURST_COUNT] = str.toIntOrNull() ?: 5
        }

        (rawMap[PreferenceKeys.KEY_DEFAULT_FOCAL_1X] as? Float)?.let { focalFloat ->
            mutable.remove(PreferenceKeys.DEFAULT_FOCAL_1X)
            if (focalFloat > 0f) {
                mutable[PreferenceKeys.DEFAULT_FOCAL_1X] = "${focalFloat.toInt()}mm"
            }
        }

        return mutable.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

/**
 * Wrapper around [DataStore] providing non-blocking async cold-start hydration, synchronous
 * snapshot reads, reactive [StateFlow], serialized in-lock listener delivery, optimistic
 * non-blocking FIFO async writes, and suspend disk-flushed writes.
 */
class DarkbagPreferencesStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope
) {
    private sealed interface WriteCommand {
        val seq: Long
        data class Mutate(
            override val seq: Long,
            val transform: (MutablePreferences) -> Unit,
            val ack: CompletableDeferred<Preferences>? = null
        ) : WriteCommand

        data class Barrier(
            override val seq: Long,
            val ack: CompletableDeferred<Unit>
        ) : WriteCommand
    }

    private val pendingSeq = AtomicLong(0L)
    private val flushedSeq = AtomicLong(0L)
    private val snapshotLock = Any()

    private val hydratedDeferred = CompletableDeferred<Unit>()
    @Volatile
    private var isHydrated: Boolean = false
    private val preHydrationTransforms = mutableListOf<(MutablePreferences) -> Unit>()

    private val _preferencesFlow = MutableStateFlow(emptyPreferences())
    val preferencesFlow: StateFlow<Preferences> = _preferencesFlow.asStateFlow()

    private val listeners = CopyOnWriteArrayList<(Preferences) -> Unit>()
    private val writeChannel = Channel<WriteCommand>(Channel.UNLIMITED)

    val isHydratedSnapshot: Boolean
        get() = isHydrated

    init {
        scope.launch {
            val initial = try {
                dataStore.data.first()
            } catch (_: Throwable) {
                emptyPreferences()
            }
            synchronized(snapshotLock) {
                val mutable = initial.toMutablePreferences()
                preHydrationTransforms.forEach { transform -> transform(mutable) }
                preHydrationTransforms.clear()
                val combined = mutable.toPreferences()
                isHydrated = true
                publishSnapshotLocked(combined)
                hydratedDeferred.complete(Unit)
            }

            dataStore.data.collect { persisted ->
                synchronized(snapshotLock) {
                    if (isHydrated && flushedSeq.get() == pendingSeq.get()) {
                        publishSnapshotLocked(persisted)
                    }
                }
            }
        }

        scope.launch {
            hydratedDeferred.await()
            for (cmd in writeChannel) {
                when (cmd) {
                    is WriteCommand.Mutate -> {
                        val updated = try {
                            dataStore.edit { mutable -> cmd.transform(mutable) }
                        } catch (t: Throwable) {
                            cmd.ack?.completeExceptionally(t)
                            continue
                        }
                        synchronized(snapshotLock) {
                            flushedSeq.set(cmd.seq)
                            if (flushedSeq.get() == pendingSeq.get()) {
                                publishSnapshotLocked(updated)
                            }
                        }
                        cmd.ack?.complete(updated)
                    }
                    is WriteCommand.Barrier -> {
                        cmd.ack.complete(Unit)
                    }
                }
            }
        }
    }

    fun current(): Preferences = _preferencesFlow.value

    suspend fun awaitHydrated() {
        hydratedDeferred.await()
    }

    @VisibleForTesting
    fun awaitHydratedBlocking() {
        runBlocking { hydratedDeferred.await() }
    }

    fun addListenerAndCurrent(listener: (Preferences) -> Unit): Preferences =
        synchronized(snapshotLock) {
            listeners.add(listener)
            _preferencesFlow.value
        }

    fun addListener(listener: (Preferences) -> Unit) {
        synchronized(snapshotLock) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: (Preferences) -> Unit) {
        synchronized(snapshotLock) {
            listeners.remove(listener)
        }
    }

    /**
     * Immediately updates the in-memory [preferencesFlow] snapshot and schedules ordered disk
     * persistence via DataStore on the IO dispatcher without blocking the calling thread.
     * Pre-hydration edits are buffered and replayed onto the loaded disk state once hydrated.
     */
    fun updateAsync(transform: (MutablePreferences) -> Unit) {
        val seq: Long
        synchronized(snapshotLock) {
            seq = pendingSeq.incrementAndGet()
            if (!isHydrated) {
                preHydrationTransforms.add(transform)
            }
            val mutable = _preferencesFlow.value.toMutablePreferences()
            transform(mutable)
            val updated = mutable.toPreferences()
            publishSnapshotLocked(updated)
        }
        writeChannel.trySend(WriteCommand.Mutate(seq = seq, transform = transform))
    }

    /**
     * Updates the preference store and suspends until persisted to DataStore on disk.
     */
    suspend fun update(transform: (MutablePreferences) -> Unit): Preferences {
        val seq: Long
        synchronized(snapshotLock) {
            seq = pendingSeq.incrementAndGet()
            if (!isHydrated) {
                preHydrationTransforms.add(transform)
            }
            val mutable = _preferencesFlow.value.toMutablePreferences()
            transform(mutable)
            val updated = mutable.toPreferences()
            publishSnapshotLocked(updated)
        }
        val ack = CompletableDeferred<Preferences>()
        writeChannel.send(WriteCommand.Mutate(seq = seq, transform = transform, ack = ack))
        return ack.await()
    }

    /** Suspends until initial hydration and all previously queued writes have been committed to DataStore. */
    suspend fun awaitIdle() {
        hydratedDeferred.await()
        val ack = CompletableDeferred<Unit>()
        writeChannel.send(WriteCommand.Barrier(pendingSeq.get(), ack))
        ack.await()
    }

    /** Clears all entries in DataStore (intended for testing/reset). */
    fun clearAsync() {
        updateAsync { it.clear() }
    }

    private fun publishSnapshotLocked(snapshot: Preferences) {
        _preferencesFlow.value = snapshot
        listeners.forEach { it(snapshot) }
    }

    internal fun close() {
        writeChannel.close()
        hydratedDeferred.cancel()
        runBlocking {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
}
