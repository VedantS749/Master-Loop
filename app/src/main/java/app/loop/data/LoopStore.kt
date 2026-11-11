package app.loop.data

import android.content.Context
import android.content.SharedPreferences
import app.loop.model.DataMode
import app.loop.model.DayRecord
import app.loop.model.JournalEntry
import app.loop.model.StrengthExercise
import app.loop.model.StrengthSession
import app.loop.model.StrengthSet
import app.loop.model.MoodEntry
import app.loop.model.Settings
import app.loop.model.ThemeMode
import app.loop.model.Workout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Everything persisted on-device. Raw data only; scores are recomputed by Enricher. */
data class LoopData(
    val days: List<DayRecord> = emptyList(),
    val workouts: List<Workout> = emptyList(),
    val moods: List<MoodEntry> = emptyList(),
    val journal: List<JournalEntry> = emptyList(),
    /** ISO dates on which a period started. */
    val cycleStarts: List<String> = emptyList(),
    val strength: List<StrengthSession> = emptyList(),
    val log: app.loop.model.HealthLog = app.loop.model.HealthLog(),
)

/**
 * Tiny local database: one JSON file in the app's private storage. Nothing leaves the phone.
 * Demo data and real data are kept in separate files so exploring sample data can never touch your own.
 */
class LoopStore(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _data = MutableStateFlow(LoopData())
    val data: StateFlow<LoopData> = _data.asStateFlow()

    private var currentFile = "loop_real.json"

    fun load(mode: DataMode) {
        currentFile = if (mode == DataMode.DEMO) "loop_demo.json" else "loop_real.json"
        if (mode == DataMode.DEMO) {
            // Demo data is regenerated relative to today so it never looks stale.
            _data.value = DemoData.generate()
            return
        }
        val f = File(context.filesDir, currentFile)
        _data.value = if (f.exists()) runCatching { decode(f.readText()) }.getOrDefault(LoopData()) else LoopData()
    }

    fun update(mode: DataMode, transform: (LoopData) -> LoopData) {
        _data.update(transform)
        if (mode != DataMode.DEMO) persist()
    }

    fun wipeReal() {
        _data.value = LoopData()
        scope.launch { lock.withLock { File(context.filesDir, "loop_real.json").delete() } }
    }

    private fun persist() {
        val snapshot = _data.value
        val file = currentFile
        scope.launch {
            lock.withLock {
                val tmp = File(context.filesDir, "$file.tmp")
                tmp.writeText(encode(snapshot))
                tmp.renameTo(File(context.filesDir, file))
            }
        }
    }

    companion object {
        private fun JSONObject.putN(k: String, v: Double?) { if (v != null && !v.isNaN()) put(k, v) }
        private fun JSONObject.optN(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null

        fun encode(d: LoopData): String {
            val o = JSONObject()
            o.put("v", 1)
            o.put("days", JSONArray().apply {
                d.days.forEach { r ->
                    put(JSONObject().apply {
                        put("day", r.day)
                        putN("hrv", r.hrv); putN("rhr", r.rhr); putN("resp", r.resp)
                        putN("skin", r.skinTempDev); putN("sleepMin", r.sleepMin); putN("eff", r.sleepEff)
                        putN("deep", r.deepMin); putN("rem", r.remMin); putN("light", r.lightMin); putN("awake", r.awakeMin)
                        putN("perf", r.sleepPerf); putN("cons", r.sleepConsistency); putN("kcal", r.kcal)
                        putN("steps", r.steps); putN("weight", r.weightKg); putN("rec", r.recoveryRaw); putN("strain", r.strainRaw)
                        putN("dtHr", r.dtHr); putN("dtLow", r.dtHrLow); putN("dtHrv", r.dtHrv); putN("dtSdnn", r.dtSdnn); putN("dtBr", r.dtBreath); putN("dtSt", r.dtStress); putN("dtCo", r.dtCoherence); putN("dtLf", r.dtLfHf); putN("dtKc", r.dtKcal); putN("dtMin", r.dtMin)
                        putN("wear", r.wearMin); putN("skinIdx", r.skinIdx); putN("hrr", r.hrr); putN("trimp", r.trimp); putN("dstress", r.dayStress); putN("shigh", r.stressHighMin); putN("irr", r.rhythmIrreg)
                    })
                }
            })
            o.put("workouts", JSONArray().apply {
                d.workouts.forEach { w ->
                    put(JSONObject().apply {
                        put("id", w.id); put("sport", w.sport); put("dur", w.durationMin)
                        w.avgHr?.let { put("avg", it) }; w.maxHr?.let { put("max", it) }
                        put("strain", w.strain); putN("kcal", w.kcal)
                        put("zones", JSONArray(w.zoneMin)); put("src", w.source)
                    })
                }
            })
            o.put("moods", JSONArray().apply {
                d.moods.forEach { m ->
                    put(JSONObject().apply {
                        put("day", m.day); put("level", m.level); put("tags", JSONArray(m.tags)); put("note", m.note)
                    })
                }
            })
            o.put("journal", JSONArray().apply {
                d.journal.forEach { j -> put(JSONObject().apply { put("day", j.day); put("f", JSONArray(j.factors)) }) }
            })
            o.put("cycles", JSONArray(d.cycleStarts))
            o.put("log", JSONObject().apply {
                put("meds", JSONArray().apply { d.log.medicines.forEach { m -> put(JSONObject().apply { put("id", m.id); put("n", m.name); put("d", m.dose); put("s", m.schedule.name); put("st", m.start); m.end?.let { put("en", it) } }) } })
                put("doses", JSONArray().apply { d.log.doses.forEach { x -> put(JSONObject().apply { put("m", x.medId); put("day", x.day) }) } })
                put("probs", JSONArray().apply { d.log.problems.forEach { q -> put(JSONObject().apply { put("id", q.id); put("k", q.kind.name); put("a", q.area); put("st", q.start); put("sv", q.severity); q.end?.let { put("en", it) } }) } })
                put("pain", JSONArray().apply { d.log.pain.forEach { x -> put(JSONObject().apply { put("p", x.problemId); put("day", x.day); put("l", x.level) }) } })
            })
            o.put("strength", JSONArray().apply {
                d.strength.forEach { ss ->
                    put(JSONObject().apply {
                        put("id", ss.id); put("name", ss.name); put("dur", ss.durationMin); put("rpe", ss.rpe)
                        put("ex", JSONArray().apply {
                            ss.exercises.forEach { e ->
                                put(JSONObject().apply {
                                    put("n", e.name)
                                    put("s", JSONArray().apply { e.sets.forEach { st -> put(JSONArray().apply { put(st.reps); put(st.kg) }) } })
                                })
                            }
                        })
                    })
                }
            })
            return o.toString()
        }

        fun decode(text: String): LoopData {
            val o = JSONObject(text)
            val days = o.optJSONArray("days")?.let { a ->
                (0 until a.length()).map { i ->
                    val j = a.getJSONObject(i)
                    DayRecord(
                        day = j.getString("day"), hrv = j.optN("hrv"), rhr = j.optN("rhr"), resp = j.optN("resp"),
                        skinTempDev = j.optN("skin"), sleepMin = j.optN("sleepMin"),
                        sleepEff = j.optN("eff"), deepMin = j.optN("deep"), remMin = j.optN("rem"), lightMin = j.optN("light"),
                        awakeMin = j.optN("awake"), sleepPerf = j.optN("perf"), sleepConsistency = j.optN("cons"),
                        kcal = j.optN("kcal"), steps = j.optN("steps"), weightKg = j.optN("weight"),
                        recoveryRaw = j.optN("rec"), strainRaw = j.optN("strain"),
                        dtHr = j.optN("dtHr"), dtHrLow = j.optN("dtLow"), dtHrv = j.optN("dtHrv"), dtSdnn = j.optN("dtSdnn"), dtBreath = j.optN("dtBr"), dtStress = j.optN("dtSt"), dtCoherence = j.optN("dtCo"), dtLfHf = j.optN("dtLf"), dtKcal = j.optN("dtKc"), dtMin = j.optN("dtMin"),
                        wearMin = j.optN("wear"), skinIdx = j.optN("skinIdx"), hrr = j.optN("hrr"), trimp = j.optN("trimp"), dayStress = j.optN("dstress"), stressHighMin = j.optN("shigh"), rhythmIrreg = j.optN("irr"),
                    )
                }
            }.orEmpty()
            val workouts = o.optJSONArray("workouts")?.let { a ->
                (0 until a.length()).map { i ->
                    val j = a.getJSONObject(i)
                    val z = j.optJSONArray("zones")
                    Workout(
                        id = j.getLong("id"), sport = j.optString("sport", "Workout"), durationMin = j.optDouble("dur", 0.0),
                        avgHr = if (j.has("avg")) j.optInt("avg") else null, maxHr = if (j.has("max")) j.optInt("max") else null,
                        strain = j.optDouble("strain", 0.0), kcal = j.optN("kcal"),
                        zoneMin = (0 until 5).map { k -> z?.optDouble(k, 0.0) ?: 0.0 }, source = j.optString("src", "import"),
                    )
                }
            }.orEmpty()
            val moods = o.optJSONArray("moods")?.let { a ->
                (0 until a.length()).map { i ->
                    val j = a.getJSONObject(i)
                    val t = j.optJSONArray("tags")
                    MoodEntry(j.getString("day"), j.getInt("level"), (0 until (t?.length() ?: 0)).map { k -> t!!.getString(k) }, j.optString("note", ""))
                }
            }.orEmpty()
            val journal = o.optJSONArray("journal")?.let { a ->
                (0 until a.length()).map { i ->
                    val j = a.getJSONObject(i); val f = j.optJSONArray("f")
                    JournalEntry(j.getString("day"), (0 until (f?.length() ?: 0)).map { k -> f!!.getString(k) })
                }
            }.orEmpty()
            val cycles = o.optJSONArray("cycles")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val strength = o.optJSONArray("strength")?.let { a ->
                (0 until a.length()).map { i ->
                    val j = a.getJSONObject(i); val ex = j.optJSONArray("ex")
                    StrengthSession(
                        j.getLong("id"), j.optString("name", "Strength"), j.optInt("dur", 45), j.optInt("rpe", 6),
                        (0 until (ex?.length() ?: 0)).map { k ->
                            val e = ex!!.getJSONObject(k); val ss = e.optJSONArray("s")
                            StrengthExercise(e.getString("n"), (0 until (ss?.length() ?: 0)).map { m -> val st = ss!!.getJSONArray(m); StrengthSet(st.getInt(0), st.getDouble(1)) })
                        },
                    )
                }
            }.orEmpty()
            val log = o.optJSONObject("log")?.let { l ->
                fun arr(k: String) = l.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
                app.loop.model.HealthLog(
                    arr("meds").map { j -> app.loop.model.Medicine(j.getLong("id"), j.getString("n"), j.optString("d", ""), runCatching { app.loop.model.Schedule.valueOf(j.getString("s")) }.getOrDefault(app.loop.model.Schedule.DAILY), j.getString("st"), if (j.has("en")) j.getString("en") else null) },
                    arr("doses").map { j -> app.loop.model.DoseTaken(j.getLong("m"), j.getString("day")) },
                    arr("probs").map { j -> app.loop.model.Problem(j.getLong("id"), runCatching { app.loop.model.ProblemKind.valueOf(j.getString("k")) }.getOrDefault(app.loop.model.ProblemKind.INJURY), j.getString("a"), j.getString("st"), j.optInt("sv", 5), if (j.has("en")) j.getString("en") else null) },
                    arr("pain").map { j -> app.loop.model.PainEntry(j.getLong("p"), j.getString("day"), j.getInt("l")) },
                )
            } ?: app.loop.model.HealthLog()
            return LoopData(days, workouts, moods, journal, cycles, strength, log)
        }
    }
}

/** Profile, preferences and onboarding state. */
class SettingsStore(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("loop_settings", Context.MODE_PRIVATE)
    private val _s = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _s.asStateFlow()

    private fun read() = Settings(
        onboarded = sp.getBoolean("onboarded", false),
        mode = runCatching { DataMode.valueOf(sp.getString("mode", "NONE")!!) }.getOrDefault(DataMode.NONE),
        name = sp.getString("name", "") ?: "",
        age = sp.getInt("age", 30),
        female = sp.getBoolean("female", false),
        weightKg = sp.getFloat("weightKg", 70f).toDouble(),
        heightCm = sp.getFloat("heightCm", 172f).toDouble(),
        sleepNeedMin = sp.getInt("sleepNeed", 480), sleepNeedAuto = sp.getBoolean("sleepNeedAuto", true), observedMaxHr = sp.getInt("observedMax", 0),
        imperial = sp.getBoolean("imperial", false),
        palette = runCatching { app.loop.model.Palette.valueOf(sp.getString("palette", "VIBRANT")!!) }.getOrDefault(app.loop.model.Palette.VIBRANT),
        theme = runCatching { ThemeMode.valueOf(sp.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
        checklistDismissed = sp.getBoolean("checklistDismissed", false),
        profileDone = sp.getBoolean("profileDone", false),
        lastDevice = sp.getString("lastDevice", "") ?: "",
        lastDeviceName = sp.getString("lastDeviceName", "") ?: "",
        cycleTracking = sp.getBoolean("cycleTracking", false),
        alarmAtMs = sp.getLong("alarmAtMs", 0L), alarmHour = sp.getInt("alarmHour", 7), alarmMinute = sp.getInt("alarmMinute", 0), alarmRepeat = sp.getBoolean("alarmRepeat", false), alarmFollowSystem = sp.getBoolean("alarmFollowSystem", false), alarmAllAlarms = sp.getBoolean("alarmAllAlarms", true),
        strapSync = sp.getBoolean("strapSync", false),
        autoConnect = sp.getBoolean("autoConnect", false),
        lastSyncAt = sp.getLong("lastSyncAt", 0L),
    )

    fun update(transform: (Settings) -> Settings) {
        val n = transform(_s.value)
        _s.value = n
        sp.edit()
            .putBoolean("onboarded", n.onboarded).putString("mode", n.mode.name).putString("name", n.name)
            .putInt("age", n.age).putBoolean("female", n.female).putFloat("weightKg", n.weightKg.toFloat())
            .putFloat("heightCm", n.heightCm.toFloat()).putInt("sleepNeed", n.sleepNeedMin).putBoolean("sleepNeedAuto", n.sleepNeedAuto).putInt("observedMax", n.observedMaxHr)
            .putBoolean("imperial", n.imperial).putString("palette", n.palette.name).putString("theme", n.theme.name)
            .putBoolean("checklistDismissed", n.checklistDismissed).putBoolean("profileDone", n.profileDone)
            .putString("lastDevice", n.lastDevice).putString("lastDeviceName", n.lastDeviceName)
            .putBoolean("cycleTracking", n.cycleTracking).putLong("alarmAtMs", n.alarmAtMs).putInt("alarmHour", n.alarmHour).putInt("alarmMinute", n.alarmMinute).putBoolean("alarmRepeat", n.alarmRepeat).putBoolean("alarmFollowSystem", n.alarmFollowSystem).putBoolean("alarmAllAlarms", n.alarmAllAlarms)
            .apply()
    }
}
