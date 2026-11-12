package app.loop

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.loop.analytics.AlarmTime
import app.loop.analytics.Scoring
import app.loop.ble.HeartRateMonitor
import app.loop.ble.HistorySink
import app.loop.data.DayLogRow
import app.loop.data.DaytimeStore
import app.loop.data.LoopData
import app.loop.data.LoopStore
import app.loop.data.SettingsStore
import app.loop.data.WhoopImporter
import app.loop.model.DataMode
import app.loop.model.Workout
import app.loop.whoop.HistRecord
import app.loop.whoop.HistoryStore
import app.loop.whoop.StrapAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import app.loop.model.plural

class LoopApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(StrapService.CHANNEL, "Strap connection", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Loop stays connected to your strap so workouts and sleep keep recording."
                    setShowBadge(false)
                },
            )
        }
    }
}

/** What a live workout recording looks like. */
data class Recording(
    val active: Boolean = false,
    val sport: String = "Workout",
    val startedAt: Long = 0,
    val elapsedSec: Int = 0,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val zoneNow: Int = 0,
    val strain: Double = 0.0,
    val kcal: Double = 0.0,
    val zoneMin: List<Double> = List(5) { 0.0 },
)

/**
 * Process-wide singletons. They live here, not in a ViewModel, so the strap connection, the workout
 * recorder and history sync keep running when the screen is off or the app is swiped away.
 */
class Graph(private val app: Application) : HistorySink {
    val store = LoopStore(app)
    val settings = SettingsStore(app)
    val monitor = HeartRateMonitor(app)
    val history = HistoryStore(app)
    val daytime = DaytimeStore(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _recording = MutableStateFlow(Recording())
    val recording: StateFlow<Recording> = _recording.asStateFlow()
    private var lastSampleTs = 0L
    private var hrSum = 0L
    private var hrCount = 0
    private val zoneSec = DoubleArray(5)

    private val _analysis = MutableStateFlow("")
    /** Short human-readable result of the last strap analysis. */
    val analysisNote: StateFlow<String> = _analysis.asStateFlow()

    private var serviceRunning = false

    private val _systemAlarm = MutableStateFlow<Long?>(null)
    /** The phone's next system alarm (epoch ms), refreshed whenever it changes. */
    val systemAlarm: StateFlow<Long?> = _systemAlarm.asStateFlow()
    private val _liveNorm = MutableStateFlow<app.loop.whoop.StrapAnalyzer.DaytimeNorm?>(null)
    /** Personal daytime-at-rest norm, used to score live stress. Null until enough strap history exists. */
    val liveNorm: StateFlow<app.loop.whoop.StrapAnalyzer.DaytimeNorm?> = _liveNorm.asStateFlow()
    // live daytime log: samples are averaged per minute and written as one row each
    private var accMinute = -1L
    private val accHr = ArrayList<Int>()
    private val accSteady = ArrayList<Boolean>()
    private val accRmssd = ArrayList<Double>(); private val accSdnn = ArrayList<Double>(); private val accPnn = ArrayList<Double>()
    private val accBreath = ArrayList<Double>(); private val accStress = ArrayList<Double>(); private val accCoh = ArrayList<Double>()
    private val accLfHf = ArrayList<Double>(); private var accKcal = 0.0; private var accKcalN = 0
    private var rollupCounter = 0
    private var lastMirrorTarget = 0L
    private var lastMirrorAt = 0L

    init {
        monitor.sink = this
        scope.launch(Dispatchers.IO) { refreshLiveNorm() }
        store.load(settings.settings.value.mode)

        scope.launch { monitor.samples.collect { onSample(it) } }

        // Keep the foreground service in step with the connection.
        scope.launch {
            monitor.state.map { it.phase }.distinctUntilChanged().collect { phase ->
                when (phase) {
                    HeartRateMonitor.Phase.CONNECTING, HeartRateMonitor.Phase.CONNECTED, HeartRateMonitor.Phase.RECONNECTING -> startService()
                    HeartRateMonitor.Phase.IDLE -> stopService()
                    else -> {}
                }
            }
        }

        // First download shortly after a WHOOP connects, then every 15 minutes, only once the user has agreed.
        scope.launch {
            monitor.state.map { it.phase == HeartRateMonitor.Phase.CONNECTED && it.family != null }.distinctUntilChanged().collect { ready ->
                if (ready && settings.settings.value.strapSync) {
                    delay(15_000)
                    monitor.startSync()
                }
            }
        }
        scope.launch {
            while (true) {
                delay(15 * 60_000L)
                if (settings.settings.value.strapSync && monitor.isConnected && monitor.state.value.family != null) monitor.startSync()
            }
        }

        // Log live daytime metrics every few seconds into one-minute rows.
        scope.launch { while (true) { delay(5000); runCatching { logTick() } } }

        // Mirror the phone's next system alarm onto the strap. Checked when the alarm changes, when the strap connects, and every minute.
        runCatching {
            ContextCompat.registerReceiver(
                app,
                object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) { scope.launch { syncSystemAlarm() } }
                },
                android.content.IntentFilter(android.app.AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED), ContextCompat.RECEIVER_EXPORTED,
            )
        }
        scope.launch { monitor.state.map { it.phase == HeartRateMonitor.Phase.CONNECTED && it.family != null && !it.pairingNeeded }.distinctUntilChanged().collect { if (it) { delay(3000); syncSystemAlarm() } } }
        scope.launch { while (true) { syncSystemAlarm(); delay(60_000L) } }

        // Daily alarm: the strap fires each alarm once, so re-arm it for the next occurrence when it has passed.
        scope.launch {
            while (true) {
                delay(2 * 60_000L)
                val s = settings.settings.value
                if (s.alarmRepeat && !s.alarmFollowSystem && monitor.isConnected && monitor.state.value.family != null && !monitor.state.value.pairingNeeded &&
                    s.alarmAtMs < System.currentTimeMillis() - 60_000
                ) {
                    val ms = AlarmTime.nextOccurrenceMs(s.alarmHour, s.alarmMinute, System.currentTimeMillis(), java.time.ZoneId.systemDefault())
                    if (monitor.setAlarm(ms)) settings.update { it.copy(alarmAtMs = ms) }
                }
            }
        }

        // Reconnect to the last strap after an app restart.
        scope.launch(Dispatchers.Main) {
            val s = settings.settings.value
            if (s.autoConnect && s.lastDevice.isNotEmpty() && monitor.hasPermissions() && monitor.isEnabled) {
                monitor.connect(HeartRateMonitor.Device(s.lastDeviceName.ifEmpty { "Strap" }, s.lastDevice, 0))
            }
        }
    }

    // ------------------------------------------------------------ live daytime log
    private fun logTick() {
        val now = System.currentTimeMillis()
        val minute = now / 60_000
        if (minute != accMinute) { flushMinute(); accMinute = minute }
        val st = monitor.state.value
        val bpm = st.bpm ?: return
        if (st.phase != HeartRateMonitor.Phase.CONNECTED || now - st.lastBeatAt > 10_000) return
        val s = settings.settings.value
        val rhr = store.data.value.days.takeLast(14).mapNotNull { it.rhr }.takeIf { it.isNotEmpty() }?.average() ?: 60.0
        accHr += bpm; accSteady += st.liveSteady
        if (st.liveSteady) {
            st.rmssd?.let { accRmssd += it }; st.liveSdnn?.let { accSdnn += it }; st.livePnn50?.let { accPnn += it }
            st.liveBreath?.let { accBreath += it }; st.liveCoherence?.let { accCoh += it }; st.liveLfHf?.let { accLfHf += it }
            val norm = _liveNorm.value
            if (norm != null && st.rmssd != null && st.rmssd > 0) accStress += StrapAnalyzer.stressScore(bpm.toDouble(), st.rmssd, norm)
        }
        // Energy only counts minutes with an elevated heart rate, like the workout estimate.
        val z = Scoring.zone(bpm.toDouble(), rhr, Scoring.maxHr(s))
        accKcal += if (z >= 1) Scoring.kcalPerMin(bpm.toDouble(), s) else 0.0; accKcalN++
    }

    private fun flushMinute() {
        if (accHr.size >= 3) {
            fun m(l: List<Double>) = l.takeIf { it.isNotEmpty() }?.average()
            daytime.append(
                DayLogRow(
                    accMinute * 60, accHr.average(), accSteady.count { it } * 2 >= accSteady.size,
                    m(accRmssd), m(accSdnn), m(accPnn), m(accBreath), m(accStress), m(accCoh), m(accLfHf),
                    if (accKcalN > 0) accKcal / accKcalN else 0.0,
                ),
            )
            if (++rollupCounter % 10 == 0) scope.launch(Dispatchers.IO) { rollupDaytime() }
        }
        accHr.clear(); accSteady.clear(); accRmssd.clear(); accSdnn.clear(); accPnn.clear(); accBreath.clear(); accStress.clear()
        accCoh.clear(); accLfHf.clear(); accKcal = 0.0; accKcalN = 0
    }

    /** Merges the last couple of days of the live log into the daily records as daytime metrics. */
    fun rollupDaytime() {
        val s = settings.settings.value
        if (s.mode == DataMode.DEMO) return
        val rows = daytime.readSince(System.currentTimeMillis() / 1000 - 2L * 86400)
        if (rows.isEmpty()) return
        if (s.mode == DataMode.NONE) settings.update { it.copy(mode = DataMode.REAL) }
        store.update(DataMode.REAL) { WhoopImporter.merge(it, WhoopImporter.Result(DaytimeStore.rollup(rows), emptyList(), emptyList(), null)) }
        daytime.prune(System.currentTimeMillis() / 1000 - 60L * 86400)
    }

    // ------------------------------------------------------------ system alarm sync
    /**
     * With "follow my phone's alarm" on, the strap alarm is armed for the phone's next alarm (if it is within a day) and
     * cleared if that alarm is removed. Only alarms Loop itself armed are ever cleared.
     */
    fun syncSystemAlarm() {
        val next = SystemAlarms.next(app)
        _systemAlarm.value = next
        val s = settings.settings.value
        if (!s.alarmFollowSystem) return
        val st = monitor.state.value
        if (!(monitor.isConnected && st.family != null && !st.pairingNeeded)) return
        val now = System.currentTimeMillis()
        val target = next?.takeIf { it > now + 30_000 && it < now + 24 * 3_600_000L }
        if (target == null) {
            if (s.alarmAtMs > now && monitor.disableAlarm()) settings.update { it.copy(alarmAtMs = 0) }
            return
        }
        if (kotlin.math.abs(target - s.alarmAtMs) <= 30_000) return
        // "First alarm only": once the strap alarm has gone off, later phone alarms (snoozes, backups) within 6 hours are skipped.
        if (!s.alarmAllAlarms && s.alarmAtMs in 1..now && target < s.alarmAtMs + 6 * 3_600_000L) return
        // Don't hammer the strap if it keeps refusing the same time.
        if (target == lastMirrorTarget && now - lastMirrorAt < 10 * 60_000L) return
        lastMirrorTarget = target; lastMirrorAt = now
        if (monitor.setAlarm(target)) {
            val z = java.time.Instant.ofEpochMilli(target).atZone(java.time.ZoneId.systemDefault())
            settings.update { it.copy(alarmAtMs = target, alarmHour = z.hour, alarmMinute = z.minute) }
        }
    }

    fun setFollowSystemAlarm(on: Boolean) {
        settings.update { it.copy(alarmFollowSystem = on) }
        lastMirrorTarget = 0L // an explicit toggle always tries again
        if (on) syncSystemAlarm()
        else if (settings.settings.value.alarmAtMs > System.currentTimeMillis() && monitor.disableAlarm()) settings.update { it.copy(alarmAtMs = 0) }
    }

    // ------------------------------------------------------------ connection
    fun connect(d: HeartRateMonitor.Device) {
        settings.update { it.copy(lastDevice = d.address, lastDeviceName = d.name, autoConnect = true) }
        monitor.connect(d)
    }

    fun disconnect() {
        settings.update { it.copy(autoConnect = false) }
        monitor.disconnect()
    }

    private fun startService() {
        if (serviceRunning) return
        runCatching { ContextCompat.startForegroundService(app, Intent(app, StrapService::class.java)); serviceRunning = true }
    }

    private fun stopService() {
        if (!serviceRunning) return
        runCatching { app.stopService(Intent(app, StrapService::class.java)) }
        serviceRunning = false
    }

    // ------------------------------------------------------------ data mode
    fun setMode(mode: DataMode) {
        settings.update { it.copy(mode = mode) }
        store.load(mode)
        if (mode == DataMode.REAL) scope.launch(Dispatchers.IO) { analyseHistory() }
    }

    // ------------------------------------------------------------ strap history
    override fun commit(records: List<HistRecord>): Boolean = history.append(records)

    override fun onSyncFinished(newRecords: Int) {
        scope.launch(Dispatchers.IO) { analyseHistory() }
    }

    /** Recomputes sleep, HRV, strain and workouts from the last three weeks of downloaded history. */
    fun refreshLiveNorm() {
        _liveNorm.value = StrapAnalyzer.daytimeNorm(history.readSince(System.currentTimeMillis() / 1000 - 7L * 86400))
    }

    fun analyseHistory() {
        refreshLiveNorm()
        val s = settings.settings.value
        // Sample data is on screen: keep the download, and score it when the user switches to their own data.
        if (s.mode == DataMode.DEMO) { _analysis.value = "Strap history is saved. It will be scored when you switch to your own data."; return }
        val recs = history.readSince(System.currentTimeMillis() / 1000 - 21L * 86400)
        if (recs.isEmpty()) { _analysis.value = "No strap history on this phone yet."; return }
        val out = StrapAnalyzer.analyze(
            recs, s, store.data.value.days.takeLast(14).mapNotNull { it.rhr }.takeIf { it.isNotEmpty() }?.average(),
            manualSleep = store.data.value.manualSleep.map { StrapAnalyzer.ManualSleepSpan(it.first, it.second) },
        )
        if (out.days.isEmpty() && out.workouts.isEmpty()) { _analysis.value = "Downloaded ${plural(recs.size, "record")}, but not enough wear time to score yet."; return }
        if (s.mode == DataMode.NONE) settings.update { it.copy(mode = DataMode.REAL) }
        store.update(DataMode.REAL) { WhoopImporter.merge(it, WhoopImporter.Result(out.days, out.workouts, emptyList(), null)) }
        // Once the strap has clearly shown a higher sustained heart rate than the age estimate, zones use the real one.
        settings.update {
            it.copy(
                lastSyncAt = System.currentTimeMillis(),
                observedMaxHr = if (out.observedMaxHr > Scoring.maxHr(it.age) + 2 && out.observedMaxHr > it.observedMaxHr) out.observedMaxHr else it.observedMaxHr,
            )
        }
        _analysis.value = "Scored ${plural(out.days.size, "day")}, ${plural(out.nights, "night")} and ${plural(out.workouts.size, "workout")} from ${plural(recs.size, "record")}."
        history.prune(System.currentTimeMillis() / 1000 - 30L * 86400)
    }

    // ------------------------------------------------------------ workout recorder
    private fun restingHr(): Double = store.data.value.days.takeLast(14).mapNotNull { it.rhr }.takeIf { it.isNotEmpty() }?.average() ?: 60.0

    private fun onSample(s: HeartRateMonitor.Sample) {
        val r = _recording.value
        if (!r.active) return
        val set = settings.settings.value
        val dt = if (lastSampleTs == 0L) 1L else min(5L, max(0L, s.ts - lastSampleTs))
        lastSampleTs = s.ts
        val z = Scoring.zone(s.bpm.toDouble(), restingHr(), Scoring.maxHr(set))
        if (z in 1..5) zoneSec[z - 1] += dt.toDouble()
        hrSum += s.bpm; hrCount++
        val zm = zoneSec.map { it / 60.0 }
        _recording.value = r.copy(
            elapsedSec = (System.currentTimeMillis() / 1000 - r.startedAt).toInt(),
            avgHr = (hrSum / hrCount).toInt(), maxHr = max(r.maxHr ?: 0, s.bpm), zoneNow = z,
            zoneMin = zm, strain = Scoring.strainFromTrimp(Scoring.trimp(zm)),
            kcal = r.kcal + Scoring.kcalPerMin(s.bpm.toDouble(), set) * dt / 60.0,
        )
    }

    fun startWorkout(sport: String) {
        zoneSec.fill(0.0); hrSum = 0; hrCount = 0; lastSampleTs = 0
        _recording.value = Recording(active = true, sport = sport, startedAt = System.currentTimeMillis() / 1000)
    }

    fun tickRecording() {
        val r = _recording.value
        if (r.active) _recording.value = r.copy(elapsedSec = (System.currentTimeMillis() / 1000 - r.startedAt).toInt())
    }

    /** Ends the session; returns the saved workout (or null when discarded or too short). */
    fun stopWorkout(save: Boolean): Workout? {
        val r = _recording.value
        _recording.value = Recording()
        if (!save || !r.active) return null
        val mins = r.elapsedSec / 60.0
        if (mins < 1.0 || hrCount == 0) return null
        val w = Workout(r.startedAt, r.sport, mins, r.avgHr, r.maxHr, r.strain, r.kcal, r.zoneMin, "strap")
        if (settings.settings.value.mode == DataMode.NONE) setMode(DataMode.REAL)
        store.update(settings.settings.value.mode) { it.copy(workouts = it.workouts + w) }
        return w
    }
}

/** Keeps the process alive and the strap connected while the screen is off. */
class StrapService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val graph get() = (application as LoopApplication).graph
    private var collectJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            graph.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        val n = build("Connecting…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(ID, n)
        // Android restarted us after killing the process: bring the link back.
        if (intent == null) {
            val s = graph.settings.settings.value
            if (s.autoConnect && s.lastDevice.isNotEmpty() && graph.monitor.state.value.phase == HeartRateMonitor.Phase.IDLE) {
                graph.monitor.connect(HeartRateMonitor.Device(s.lastDeviceName.ifEmpty { "Strap" }, s.lastDevice, 0))
            }
        }
        collectJob?.cancel()
        collectJob = scope.launch {
            var lastText = ""
            var lastAt = 0L
            graph.monitor.state.collect { st ->
                val name = st.device?.name ?: "strap"
                val text = when (st.phase) {
                    HeartRateMonitor.Phase.CONNECTED -> buildString {
                        append(name)
                        st.bpm?.let { append(" · $it bpm") }
                        if (graph.recording.value.active) append(" · recording")
                        if (st.sync.phase == HeartRateMonitor.SyncPhase.RUNNING) append(" · syncing")
                    }
                    HeartRateMonitor.Phase.RECONNECTING -> "Reconnecting to $name…"
                    else -> "Connecting to $name…"
                }
                val now = System.currentTimeMillis()
                if (text != lastText && now - lastAt > 4000) {
                    lastText = text; lastAt = now
                    getSystemService(NotificationManager::class.java).notify(ID, build(text))
                }
            }
        }
        return START_STICKY
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, StrapService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_loop).setContentTitle("Loop").setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Disconnect", stop).build()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val CHANNEL = "strap"
        const val ID = 41
        const val ACTION_STOP = "app.loop.STOP"
    }
}
