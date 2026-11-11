package app.loop.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.loop.analytics.AlarmTime
import app.loop.analytics.Brief
import app.loop.analytics.CycleEngine
import app.loop.analytics.FactorEffect
import app.loop.analytics.JournalImpact
import app.loop.model.JournalEntry
import app.loop.model.StrengthSession
import app.loop.whoop.StrapAnalyzer
import kotlinx.coroutines.withContext
import app.loop.analytics.Enricher
import app.loop.analytics.Insight
import app.loop.analytics.Insights
import app.loop.analytics.Scoring
import app.loop.analytics.Stats
import app.loop.LoopApplication
import app.loop.Recording
import app.loop.ble.HeartRateMonitor
import app.loop.data.LoopCsv
import app.loop.data.LoopData
import app.loop.data.LoopStore
import app.loop.data.SettingsStore
import app.loop.data.WhoopImporter
import app.loop.model.HealthLog
import app.loop.model.Medicine
import app.loop.model.DoseTaken
import app.loop.model.Problem
import app.loop.model.ProblemKind
import app.loop.model.Schedule
import app.loop.model.PainEntry
import app.loop.analytics.HealthLogs
import app.loop.analytics.SleepCoach
import app.loop.analytics.SleepAdvice
import app.loop.analytics.TonightAdvice
import app.loop.model.Category
import app.loop.model.DataMode
import app.loop.model.DayRecord
import app.loop.model.MetricDef
import app.loop.model.MetricId
import app.loop.model.MoodEntry
import app.loop.model.Settings
import app.loop.model.Workout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

enum class Tab(val label: String) { TODAY("Today"), OVERVIEW("Overview"), LOG("Health"), EXPLORE("Explore"), PRACTICE("Practice"), YOU("You") }

sealed interface Route {
    data class CategoryScreen(val category: Category) : Route
    data class MetricScreen(val id: MetricId) : Route
    data class WorkoutScreen(val id: Long) : Route
    data object Workouts : Route
    data object Live : Route
    data object Breathe : Route
    data object Intervals : Route
    data object ImportData : Route
    data object Devices : Route
    data object Profile : Route
    data object Help : Route
    data object About : Route
    data object MoodCheckIn : Route
    data object LogWorkout : Route
    data object Journal : Route
    data object Cycle : Route
    data object Strength : Route
    data object StrengthEdit : Route
    data object Alarm : Route
}

/** Fully scored view of everything, recomputed whenever data or settings change. */
data class Dashboard(
    val days: List<DayRecord> = emptyList(),
    val workouts: List<Workout> = emptyList(),
    val moods: List<MoodEntry> = emptyList(),
    val settings: Settings = Settings(),
    val latest: DayRecord? = null,
    val brief: Brief? = null,
    val insights: List<Insight> = emptyList(),
    val loaded: Boolean = false,
    val journal: List<JournalEntry> = emptyList(),
    val cycleStarts: List<LocalDate> = emptyList(),
    val strength: List<StrengthSession> = emptyList(),
    val journalEffects: List<FactorEffect> = emptyList(),
    val log: app.loop.model.HealthLog = app.loop.model.HealthLog(),
    /** Days left out of baselines (illness and serious injuries), so one rough week does not skew what is normal. */
    val offDays: Set<String> = emptySet(),
    val sleepAdvice: SleepAdvice? = null,
    val tonight: TonightAdvice? = null,
) {
    val hasData: Boolean get() = days.any { it.hasAnything } || workouts.isNotEmpty()

    /** Values for the last [n] calendar days ending today, aligned by date (null where there is no data). */
    fun series(def: MetricDef, n: Int): List<Double?> {
        val byDay = days.associateBy { it.day }
        val today = LocalDate.now()
        return (n - 1 downTo 0).map { off -> byDay[today.minusDays(off.toLong()).toString()]?.let(def.read) }
    }

    fun dates(n: Int): List<LocalDate> = (n - 1 downTo 0).map { LocalDate.now().minusDays(it.toLong()) }

    /** Most recent day that has a value for this metric. */
    fun latestValue(def: MetricDef): Pair<DayRecord, Double>? =
        days.asReversed().firstNotNullOfOrNull { d -> def.read(d)?.let { d to it } }

    fun baseline(def: MetricDef): Stats.Baseline? = Stats.baseline(days.takeLast(29).dropLast(1).map { if (it.day in offDays) null else def.read(it) }, minN = 4)
}

sealed interface ImportUi {
    data object Idle : ImportUi
    data object Working : ImportUi
    data class Done(val days: Int, val workouts: Int) : ImportUi
    data class Failed(val message: String) : ImportUi
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val graph = (app as LoopApplication).graph
    private val store = graph.store
    private val settingsStore = graph.settings
    val monitor = graph.monitor
    val recording: StateFlow<Recording> = graph.recording
    val analysisNote: StateFlow<String> = graph.analysisNote

    val settings: StateFlow<Settings> = settingsStore.settings

    val dashboard: StateFlow<Dashboard> = combine(store.data, settings) { d, chosen ->
        // The sleep need is recommended from age and the person's own nights, unless they chose their own number.
        val advice = SleepCoach.recommend(chosen.age, d.days)
        val s = if (chosen.sleepNeedAuto) chosen.copy(sleepNeedMin = advice.needMin) else chosen
        val off = HealthLogs.offDays(d.log)
        val days = Enricher.enrich(d.days, d.workouts, d.moods, s, off, d.strength)
        Dashboard(
            days = days, workouts = d.workouts.sortedByDescending { it.id }, moods = d.moods, settings = s,
            latest = days.lastOrNull { it.hasAnything || it.recovery != null || it.sleepScore != null },
            brief = Insights.brief(days, s), insights = Insights.insights(days, s), loaded = true,
            journal = d.journal.sortedBy { it.day }, cycleStarts = if (s.female) CycleEngine.starts(d.cycleStarts) else emptyList(), strength = d.strength.sortedByDescending { it.id },
            journalEffects = JournalImpact.compute(days, d.journal), log = d.log, offDays = off,
            sleepAdvice = advice, tonight = SleepCoach.tonight(s.sleepNeedMin, days.lastOrNull { it.hasAnything }),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, Dashboard())

    // ---------------- navigation ----------------
    var tab by mutableStateOf(Tab.TODAY)
    val stack = mutableStateListOf<Route>()
    fun push(r: Route) { if (stack.lastOrNull() != r) stack.add(r) }
    fun pop(): Boolean = if (stack.isNotEmpty()) { stack.removeAt(stack.lastIndex); true } else false
    fun selectTab(t: Tab) { stack.clear(); tab = t }
    fun openCategory(c: Category) = push(Route.CategoryScreen(c))
    fun openMetric(id: MetricId) = push(Route.MetricScreen(id))

    // ---------------- live workout recording (lives in Graph so it survives the screen) ----------------
    fun startWorkout(sport: String) = graph.startWorkout(sport)
    fun tickRecording() = graph.tickRecording()
    /** Ends the session. Returns the saved workout id, or null when discarded or too short. */
    fun stopWorkout(save: Boolean): Long? = graph.stopWorkout(save)?.id

    // ---------------- strap ----------------
    fun connectStrap(d: HeartRateMonitor.Device) = graph.connect(d)
    fun disconnectStrap() = graph.disconnect()
    fun syncStrap() = monitor.startSync()
    fun pairStrap() = monitor.pairStrap()
    fun reanalyse() = viewModelScope.launch(Dispatchers.IO) { graph.analyseHistory() }

    private val _import = MutableStateFlow<ImportUi>(ImportUi.Idle)
    val importState: StateFlow<ImportUi> = _import.asStateFlow()

    /** Manual entry: sport + minutes + perceived effort 1..10, turned into zone minutes with a simple model. */
    fun logManualWorkout(sport: String, minutes: Int, effort: Int, startEpoch: Long = System.currentTimeMillis() / 1000) {
        mutate { it.copy(workouts = it.workouts + buildManualWorkout(sport, minutes, effort, startEpoch, "manual")) }
    }

    private fun buildManualWorkout(sport: String, minutes: Int, effort: Int, startEpoch: Long, source: String): Workout {
        val centre = (effort - 1) / 9.0 * 4.0 // 0..4
        val raw = DoubleArray(5) { k -> Math.exp(-Math.pow(k - centre, 2.0) / 1.6) }
        val total = raw.sum()
        val zm = raw.map { it / total * minutes }
        val s = settings.value
        val avg = (110 + effort * 7).coerceAtMost(185)
        val w = Workout(
            id = startEpoch, sport = sport, durationMin = minutes.toDouble(), avgHr = avg, maxHr = (avg + 18).coerceAtMost(200),
            strain = Scoring.strainFromTrimp(Scoring.trimp(zm)),
            kcal = Scoring.kcalPerMin(avg.toDouble(), s) * minutes, zoneMin = zm, source = source,
        )
        return w
    }

    fun deleteWorkout(id: Long) = mutate { it.copy(workouts = it.workouts.filterNot { w -> w.id == id }) }

    // ---------------- mood ----------------
    fun addMood(level: Int, tags: List<String>, note: String = "") {
        val today = LocalDate.now().toString()
        mutate { it.copy(moods = it.moods.filterNot { m -> m.day == today } + MoodEntry(today, level, tags, note)) }
    }

    fun todaysMood(): MoodEntry? = dashboard.value.moods.firstOrNull { it.day == LocalDate.now().toString() }

    // ---------------- journal ----------------
    fun saveJournal(day: String, factors: List<String>) =
        mutate { it.copy(journal = it.journal.filterNot { j -> j.day == day } + JournalEntry(day, factors)) }

    // ---------------- health log: medicines, injuries and illness ----------------
    private fun newId() = System.currentTimeMillis()
    private fun editLog(t: (HealthLog) -> HealthLog) = mutate { it.copy(log = t(it.log)) }

    fun addMedicine(name: String, dose: String, schedule: Schedule, start: String) =
        editLog { l -> l.copy(medicines = l.medicines + Medicine(newId(), name.trim(), dose.trim(), schedule, start)) }
    fun stopMedicine(id: Long, day: String = LocalDate.now().toString()) = editLog { l -> l.copy(medicines = l.medicines.map { if (it.id == id) it.copy(end = day) else it }) }
    fun removeMedicine(id: Long) = editLog { l -> l.copy(medicines = l.medicines.filterNot { it.id == id }, doses = l.doses.filterNot { it.medId == id }) }
    /** Ticks or un-ticks today's dose of a "when needed" medicine. */
    fun toggleDose(medId: Long, day: String = LocalDate.now().toString()) = editLog { l ->
        val d = DoseTaken(medId, day)
        l.copy(doses = if (d in l.doses) l.doses - d else l.doses + d)
    }

    fun addProblem(kind: ProblemKind, area: String, severity: Int, start: String) =
        editLog { l -> l.copy(problems = l.problems + Problem(newId(), kind, area.trim(), start, severity.coerceIn(1, 10))) }
    fun healProblem(id: Long, day: String = LocalDate.now().toString()) = editLog { l -> l.copy(problems = l.problems.map { if (it.id == id) it.copy(end = day) else it }) }
    fun removeProblem(id: Long) = editLog { l -> l.copy(problems = l.problems.filterNot { it.id == id }, pain = l.pain.filterNot { it.problemId == id }) }
    fun logPain(problemId: Long, level: Int, day: String = LocalDate.now().toString()) =
        editLog { l -> l.copy(pain = l.pain.filterNot { it.problemId == problemId && it.day == day } + PainEntry(problemId, day, level.coerceIn(0, 10))) }

    // ---------------- sex ----------------
    /** Switching to male removes every trace of cycle tracking: the setting and any stored dates. */
    fun setSex(female: Boolean) {
        if (!female) mutate { it.copy(cycleStarts = emptyList()) }
        update { it.copy(female = female, profileDone = true, cycleTracking = if (female) it.cycleTracking else false) }
    }

    // ---------------- cycle ----------------
    fun addPeriodStart(date: LocalDate) = mutate { it.copy(cycleStarts = (it.cycleStarts + date.toString()).distinct().sorted()) }
    fun removePeriodStart(iso: String) = mutate { it.copy(cycleStarts = it.cycleStarts - iso) }

    // ---------------- strength ----------------
    /** Saves the session and adds a matching workout so strain and the workout list include it. */
    fun saveStrength(session: StrengthSession) {
        val w = buildManualWorkout("Strength", session.durationMin, session.rpe, session.id, "strength")
        mutate { it.copy(strength = it.strength.filterNot { x -> x.id == session.id } + session, workouts = it.workouts.filterNot { x -> x.id == session.id } + w) }
    }

    fun deleteStrength(id: Long) = mutate { it.copy(strength = it.strength.filterNot { s -> s.id == id }, workouts = it.workouts.filterNot { w -> w.id == id }) }

    // ---------------- daytime stress through the day ----------------
    private val stressCache = HashMap<String, List<Pair<Int, Double>>>()

    /** Stress per 5 minutes for one date from the strap's saved history. Empty when no strap history covers it. */
    suspend fun stressTimeline(date: LocalDate): List<Pair<Int, Double>> = withContext(Dispatchers.IO) {
        stressCache.getOrPut(date.toString() + ":" + (graph.history.sizeBytes() / 4096)) {
            val zone = java.time.ZoneId.systemDefault()
            val from = date.minusDays(6).atStartOfDay(zone).toEpochSecond()
            val to = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
            val recs = graph.history.readSince(from).filter { it.ts < to }
            StrapAnalyzer.stressTimeline(recs, date, zone)
        }
    }

    // ---------------- strap wake alarm ----------------
    fun setWakeAlarm(hour: Int, minute: Int): Boolean {
        val ms = AlarmTime.nextOccurrenceMs(hour, minute, System.currentTimeMillis(), java.time.ZoneId.systemDefault())
        val ok = monitor.setAlarm(ms)
        if (ok) update { it.copy(alarmAtMs = ms, alarmHour = hour, alarmMinute = minute) }
        return ok
    }

    val systemAlarm: StateFlow<Long?> = graph.systemAlarm
    val liveNorm = graph.liveNorm
    fun setFollowSystemAlarm(on: Boolean) = graph.setFollowSystemAlarm(on)
    fun refreshSystemAlarm() = graph.syncSystemAlarm()

    fun clearWakeAlarm(): Boolean {
        val ok = monitor.disableAlarm()
        if (ok) update { it.copy(alarmAtMs = 0) }
        return ok
    }

    // ---------------- data mode / settings ----------------
    private fun mutate(t: (LoopData) -> LoopData) {
        if (settings.value.mode == DataMode.NONE) setMode(DataMode.REAL)
        store.update(settings.value.mode, t)
    }

    fun setMode(mode: DataMode) = graph.setMode(mode)

    fun update(t: (Settings) -> Settings) = settingsStore.update(t)

    fun finishOnboarding(mode: DataMode) {
        settingsStore.update { it.copy(onboarded = true, mode = mode) }
        store.load(mode)
    }

    fun wipeMyData() {
        store.wipeReal()
        settingsStore.update { it.copy(mode = DataMode.NONE) }
        store.load(DataMode.NONE)
    }

    // ---------------- import / export ----------------
    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _import.value = ImportUi.Working
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { WhoopImporter.readFiles(getApplication(), uris) }
            val res = r.getOrNull()
            if (res == null) { _import.value = ImportUi.Failed("Couldn't read that file: ${r.exceptionOrNull()?.message ?: "unknown error"}"); return@launch }
            if (res.days.isEmpty() && res.workouts.isEmpty()) { _import.value = ImportUi.Failed(res.note ?: "Nothing to import."); return@launch }
            if (settings.value.mode != DataMode.REAL) setMode(DataMode.REAL)
            store.update(DataMode.REAL) { WhoopImporter.merge(it, res) }
            _import.value = ImportUi.Done(res.days.size, res.workouts.size)
        }
    }

    fun clearImportState() { _import.value = ImportUi.Idle }

    fun exportCsv(uri: Uri, onDone: (Boolean) -> Unit) {
        val csv = LoopCsv.export(dashboard.value.days)
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) } != null }.getOrDefault(false)
            launch(Dispatchers.Main) { onDone(ok) }
        }
    }

    override fun onCleared() { super.onCleared() }
}
