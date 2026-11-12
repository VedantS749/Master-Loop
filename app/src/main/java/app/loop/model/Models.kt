package app.loop.model

import java.time.LocalDate

/** The five areas Loop organises every number into. Order = order shown in the app. */
enum class Category(
    val title: String,
    val tagline: String,
    val blurb: String,
) {
    RECOVERY(
        "Recovery", "How ready your body is",
        "Recovery tells you how well your body bounced back overnight, using your heart-rate variability, " +
            "resting heart rate and sleep compared with your own normal."
    ),
    SLEEP(
        "Sleep", "How well you rested",
        "Sleep covers how long you slept, how efficient it was, and how much of it was deep and REM sleep, " +
            "the stages where your body repairs and your brain consolidates memory."
    ),
    DAYTIME(
        "Daytime", "Live estimates through the day",
        "Daytime metrics are worked out in real time from your heartbeat while your strap is connected and logged every minute. " +
            "They are estimates, rougher than the overnight numbers, and are kept separate from them."
    ),
    ACTIVITY(
        "Activity", "How hard you moved",
        "Activity measures the load you put on your body today: strain from your heart rate, " +
            "workouts, calories and steps."
    ),
    BODY(
        "Body", "Your vital signs",
        "Body vitals are slow-moving signals such as breathing rate, skin temperature " +
            "and weight. Big changes from your normal can be an early sign of illness or stress."
    ),
    MIND(
        "Mind", "Stress and mood",
        "Mind brings together your body's stress load and how you say you feel, so you can see " +
            "what actually lifts or drains you."
    ),
}

/** Everything we know about one calendar day. Raw fields are stored; derived ones are filled by [app.loop.analytics.Enricher]. */
data class DayRecord(
    val day: String, // ISO yyyy-MM-dd (the day you woke up)
    // ----- raw, stored -----
    val hrv: Double? = null,            // overnight RMSSD, ms
    val rhr: Double? = null,            // resting heart rate, bpm
    val resp: Double? = null,           // breaths / min
    val skinTempDev: Double? = null,    // degrees C from personal baseline
    val sleepMin: Double? = null,       // time actually asleep
    val sleepEff: Double? = null,       // 0..100
    val deepMin: Double? = null,
    val remMin: Double? = null,
    val lightMin: Double? = null,
    val awakeMin: Double? = null,
    val sleepPerf: Double? = null,      // 0..100 if the source supplied it
    val sleepConsistency: Double? = null,
    val kcal: Double? = null,
    val steps: Double? = null,
    val weightKg: Double? = null,
    val recoveryRaw: Double? = null,    // 0..100 if the source supplied it
    val strainRaw: Double? = null,      // 0..100 if the source supplied it
    val wearMin: Double? = null,        // minutes on wrist (from strap history)
    val skinIdx: Double? = null,        // uncalibrated skin-temp shift in standard deviations (WHOOP 4.0)
    val hrr: Double? = null,            // heart-rate recovery, bpm dropped in the first minute after a hard effort
    val dayStress: Double? = null,      // 0..100 average daytime stress
    val stressHighMin: Double? = null,  // minutes of high daytime stress
    val napMin: Double? = null,         // minutes of naps and any second sleep that day
    val noSleep: Boolean? = null,       // true: worn through the night, still moving, no sleep found (an all-nighter); false: slept; null: unknown
    val trimp: Double? = null,          // the strap's all-day training load (Banister TRIMP); strain is worked out from it
    val rhythmIrreg: Double? = null,    // % of the night's 5-minute stretches with irregular beat timing (from R-R beat timing)
    // ----- daytime live log rollups (estimates) -----
    val dtHr: Double? = null, val dtHrLow: Double? = null, val dtHrv: Double? = null, val dtSdnn: Double? = null,
    val dtBreath: Double? = null, val dtStress: Double? = null, val dtCoherence: Double? = null, val dtLfHf: Double? = null,
    val dtKcal: Double? = null, val dtMin: Double? = null,
    // ----- derived -----
    val recovery: Double? = null,
    val strain: Double? = null,
    val sleepScore: Double? = null,
    val sleepDebtMin: Double? = null,
    val stress: Double? = null,         // 0..100, higher = more strain on the body
    val mood: Double? = null,           // 1..5
    val vo2max: Double? = null,         // estimated, mL/kg/min
    val loadRatio: Double? = null,      // 7-day / 28-day training load
    val recoveryConf: Int? = null,      // 0 low, 1 medium, 2 high: how far the recovery score can be trusted
    val recoveryConfNote: String? = null,
) {
    val date: LocalDate get() = LocalDate.parse(day)
    val hasAnything: Boolean
        get() = listOf(hrv, rhr, resp, sleepMin, kcal, steps, weightKg, recoveryRaw, strainRaw, wearMin, dayStress, dtHr, rhythmIrreg, napMin).any { it != null }
}

data class Workout(
    val id: Long,              // start time, epoch seconds
    val sport: String,
    val durationMin: Double,
    val avgHr: Int?,
    val maxHr: Int?,
    val strain: Double,        // 0..100 contribution
    val kcal: Double?,
    val zoneMin: List<Double>, // 5 entries
    val source: String,        // strap | import | manual | demo
) {
    val day: String get() = java.time.Instant.ofEpochSecond(id).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
}


data class MoodEntry(val day: String, val level: Int, val tags: List<String>, val note: String = "")

/** A day's journal: the factors the person says applied that day. An entry with no factors still counts as "journaled". */
data class JournalEntry(val day: String, val factors: List<String>)

data class StrengthSet(val reps: Int, val kg: Double)
data class StrengthExercise(val name: String, val sets: List<StrengthSet>)
data class StrengthSession(
    val id: Long, val name: String, val durationMin: Int, val rpe: Int, val exercises: List<StrengthExercise>,
) {
    val day: String get() = java.time.Instant.ofEpochSecond(id).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
    val volumeKg: Double get() = exercises.sumOf { e -> e.sets.sumOf { it.reps * it.kg } }
    val totalSets: Int get() = exercises.sumOf { it.sets.size }
}

/** Which part of the 24 hours a number comes from. */
enum class Window(val tag: String, val title: String, val blurb: String) {
    NIGHT("NIGHT", "Night-time metrics", "Scored overnight, with scoring based on NOOP's documented methods"),
    DAY("DAY", "Daytime metrics", "Live estimates from your heartbeat, logged while your strap is connected"),
    WHOLE("", "Whole-day metrics", "Measured across the full 24 hours"),
}

/** Category-level grouping used to sort the app into night-time, daytime and whole-day. */
fun Category.window(): Window = when (this) {
    Category.RECOVERY, Category.SLEEP -> Window.NIGHT
    Category.DAYTIME -> Window.DAY
    else -> Window.WHOLE
}

enum class DataMode { NONE, DEMO, REAL }
/** BLACK is dark mode on true black surfaces, for OLED screens. */
enum class ThemeMode { SYSTEM, LIGHT, DARK, BLACK }

/** Colour presets. SYSTEM uses the phone's Material You colours (Android 12+). */
enum class Palette(val label: String, val blurb: String) {
    VIBRANT("Vibrant", "Electric and bright"),
    OCEAN("Ocean", "Cool blues and aqua"),
    SUNSET("Sunset", "Warm coral and gold"),
    FOREST("Forest", "Fresh greens"),
    SOMBER("Somber", "Quiet and muted"),
    SYSTEM("System", "Matches your wallpaper"),
}

data class Settings(
    val onboarded: Boolean = false,
    val mode: DataMode = DataMode.NONE,
    val name: String = "",
    val age: Int = 30,
    val female: Boolean = false,
    val weightKg: Double = 70.0,
    val heightCm: Double = 172.0,
    val sleepNeedMin: Int = 480,
    /** Use the recommended sleep need (from age and your own nights) instead of the number above. */
    val sleepNeedAuto: Boolean = true,
    /** The highest sustained heart rate Loop has seen from the strap, used for zones once it beats the age estimate. 0 = none. */
    val observedMaxHr: Int = 0,
    val imperial: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val palette: Palette = Palette.VIBRANT,
    val checklistDismissed: Boolean = false,
    val profileDone: Boolean = false,
    val lastDevice: String = "",
    val lastDeviceName: String = "",
    /** The user has agreed to download history from a WHOOP strap (this lets the strap free what we saved). */
    val strapSync: Boolean = false,
    /** Reconnect to the last strap automatically (cleared when the user disconnects). */
    val autoConnect: Boolean = false,
    /** Show and use cycle tracking (opt-in). */
    val cycleTracking: Boolean = false,
    /** Wake alarm on the strap: next absolute time (epoch ms), 0 when none is armed. */
    val alarmAtMs: Long = 0,
    val alarmHour: Int = 7,
    val alarmMinute: Int = 0,
    /** Re-arm the strap alarm each day while connected. */
    val alarmRepeat: Boolean = false,
    /** Mirror the phone's next system alarm onto the strap. */
    val alarmFollowSystem: Boolean = false,
    /** When following the phone: arm the strap for every phone alarm in turn (true), or only the first alarm of each morning (false). */
    val alarmAllAlarms: Boolean = true,
    val lastSyncAt: Long = 0,
)

/** How a number is displayed. */
enum class Fmt { INT, ONE_DECIMAL, DURATION, PERCENT, TEMP_DELTA, MOOD }

enum class MetricId {
    RECOVERY, HRV, RHR,
    SLEEP_SCORE, SLEEP_DURATION, SLEEP_EFFICIENCY, DEEP, REM, SLEEP_DEBT,
    STRAIN, CALORIES, STEPS,
    RESP, SKIN_TEMP, WEIGHT,
    STRESS, MOOD,
    HR_RECOVERY, VO2_MAX, LOAD_RATIO, DAY_STRESS, STRESS_HIGH, SKIN_TEMP_REL, WEAR_TIME,
    DT_HR, DT_HR_LOW, DT_HRV, DT_SDNN, DT_BREATH, DT_STRESS, DT_COHERENCE, DT_LFHF, DT_KCAL, DT_LOGGED,
    RHYTHM_IRREG,
}

data class MetricDef(
    val id: MetricId,
    val title: String,
    val category: Category,
    val unit: String,
    val fmt: Fmt,
    /** true: higher is better, false: lower is better, null: neutral (only your own normal matters). */
    val higherIsBetter: Boolean?,
    val what: String,
    val why: String,
    val good: String,
    val read: (DayRecord) -> Double?,
)

object Metrics {
    val all: List<MetricDef> = listOf(
        MetricDef(
            MetricId.RECOVERY, "Recovery", Category.RECOVERY, "%", Fmt.INT, true,
            "A 0 to 100 score for how ready your body is today, built from last night's heart-rate variability, resting heart rate, breathing and sleep, each compared with your own recent normal.",
            "It guides how hard to train. High recovery means you can push; low recovery means rest pays off more.",
            "67 to 100 is green (push), 34 to 66 is yellow (steady), under 34 is red (recharge).",
        ) { it.recovery },
        MetricDef(
            MetricId.HRV, "Heart-rate variability", Category.RECOVERY, "ms", Fmt.INT, true,
            "The tiny variation in time between heartbeats (RMSSD), measured while you sleep. A more variable heartbeat means your nervous system is relaxed and adaptable.",
            "It is the single best everyday signal of recovery. Stress, alcohol, illness and hard training all push it down.",
            "There is no universal good number. It varies hugely between people, so only compare it with your own baseline. Higher than your normal is good.",
        ) { it.hrv },
        MetricDef(
            MetricId.RHR, "Resting heart rate", Category.RECOVERY, "bpm", Fmt.INT, false,
            "Your lowest steady heart rate during sleep.",
            "A rising resting heart rate often shows up before you feel ill, dehydrated or overtrained.",
            "Lower than your own normal is good. A jump of 5 bpm or more is worth noticing.",
        ) { it.rhr },
        MetricDef(
            MetricId.SLEEP_SCORE, "Sleep score", Category.SLEEP, "", Fmt.INT, true,
            "A 0 to 100 summary of last night: how long you slept compared with what you need, how efficient it was and how much was deep and REM sleep.",
            "It turns four sleep numbers into one so you can see at a glance whether the night did its job.",
            "85 and above is excellent, 70 to 84 is good, under 70 means you came up short.",
        ) { it.sleepScore },
        MetricDef(
            MetricId.SLEEP_DURATION, "Time asleep", Category.SLEEP, "", Fmt.DURATION, true,
            "Total time you were actually asleep, not just in bed.",
            "Most adults need 7 to 9 hours. Chronic shortfalls build up as sleep debt.",
            "Aim to match your personal sleep need, which you can set in your profile.",
        ) { it.sleepMin },
        MetricDef(
            MetricId.SLEEP_EFFICIENCY, "Sleep efficiency", Category.SLEEP, "%", Fmt.INT, true,
            "The share of your time in bed that you spent asleep.",
            "Low efficiency means long awake periods, often caused by stress, caffeine, noise or temperature.",
            "90% or more is excellent, 85% is the usual healthy benchmark.",
        ) { it.sleepEff },
        MetricDef(
            MetricId.DEEP, "Deep sleep", Category.SLEEP, "", Fmt.DURATION, true,
            "The slowest, most restorative stage. Your body repairs muscle and tissue and clears metabolic waste.",
            "Deep sleep is where physical recovery happens. It tends to come early in the night.",
            "Roughly 13 to 23% of your sleep is typical for adults.",
        ) { it.deepMin },
        MetricDef(
            MetricId.REM, "REM sleep", Category.SLEEP, "", Fmt.DURATION, true,
            "The dreaming stage. Your brain processes emotion and locks in memory and learning.",
            "REM supports mood, focus and learning. Alcohol and short nights cut it first.",
            "Roughly 20 to 25% of your sleep is typical for adults.",
        ) { it.remMin },
        MetricDef(
            MetricId.SLEEP_DEBT, "Sleep debt", Category.SLEEP, "", Fmt.DURATION, false,
            "How many hours of sleep you owe your body from the last few nights compared with your sleep need.",
            "Debt drags down recovery, mood and focus until you pay it back.",
            "Under 1 hour is healthy. Over 3 hours is a sign to prioritise an early night.",
        ) { it.sleepDebtMin },
        MetricDef(
            MetricId.STRAIN, "Strain", Category.ACTIVITY, "", Fmt.INT, null,
            "A 0 to 100 measure of the cardiovascular load you put on your body today, from your heart rate across workouts and daily movement. It rises steeply at first and flattens near the top, so getting from 80 to 90 takes far more effort than 20 to 30.",
            "Pairing strain with recovery tells you whether you are under-training, in the sweet spot, or overreaching.",
            "0 to 30 light, 30 to 55 moderate, 55 to 75 high, 75 and above all-out. The right level depends on your recovery.",
        ) { it.strain },
        MetricDef(
            MetricId.CALORIES, "Active calories", Category.ACTIVITY, "kcal", Fmt.INT, null,
            "An estimate of energy burned in workouts, calculated from your heart rate, weight, age and sex.",
            "A rough guide to how much you moved, not a precise nutrition number.",
            "Depends entirely on your goals.",
        ) { it.kcal },
        MetricDef(
            MetricId.STEPS, "Steps", Category.ACTIVITY, "", Fmt.INT, true,
            "Estimated steps taken in the day.",
            "Everyday movement protects your heart and mood even outside workouts.",
            "7,000 to 10,000 steps a day is linked to better long-term health.",
        ) { it.steps },
        MetricDef(
            MetricId.RESP, "Respiratory rate", Category.BODY, "br/min", Fmt.ONE_DECIMAL, null,
            "How many breaths you take per minute while asleep.",
            "It is very stable for each person, so a rise of 1 to 2 breaths per minute is an early sign of illness.",
            "12 to 20 is typical. Watch for changes from your own baseline.",
        ) { it.resp },
        MetricDef(
            MetricId.SKIN_TEMP, "Skin temperature", Category.BODY, "", Fmt.TEMP_DELTA, null,
            "How far your overnight skin temperature is from your personal baseline.",
            "A sustained rise can signal fever or the start of an illness.",
            "Within about 0.5 degrees of your baseline is normal.",
        ) { it.skinTempDev },
        MetricDef(
            MetricId.WEIGHT, "Weight", Category.BODY, "", Fmt.ONE_DECIMAL, null,
            "Body weight from your imported data or profile.",
            "Slow trends matter far more than any single day.",
            "A healthy weight depends on you.",
        ) { it.weightKg },
        MetricDef(
            MetricId.STRESS, "Body stress", Category.MIND, "", Fmt.INT, false,
            "A 0 to 100 estimate of how much load your nervous system is under, from heart-rate variability and resting heart rate compared with your baseline.",
            "Chronic stress quietly wears down sleep, recovery and mood. Seeing it lets you act early.",
            "Under 35 is low, 35 to 65 moderate, over 65 high.",
        ) { it.stress },
        MetricDef(
            MetricId.MOOD, "Mood", Category.MIND, "/5", Fmt.MOOD, true,
            "Your own 1 to 5 rating from the daily check-in.",
            "Comparing mood with sleep and recovery shows what really lifts or drains you.",
            "There is no right answer. Loop looks for patterns over weeks.",
        ) { it.mood },
        MetricDef(
            MetricId.HR_RECOVERY, "Heart-rate recovery", Category.ACTIVITY, "bpm", Fmt.INT, true,
            "How many beats per minute your heart rate falls in the first minute after you stop a hard effort. Loop measures it from workouts detected in your strap's history.",
            "A fast drop shows a strong heart and a well-tuned nervous system. It is one of the best simple markers of cardiovascular fitness.",
            "Over 25 bpm is excellent, 18 to 25 is good, 12 to 18 is average, and under 12 is worth attention.",
        ) { it.hrr },
        MetricDef(
            MetricId.VO2_MAX, "Fitness (VO₂ max, est.)", Category.ACTIVITY, "mL/kg/min", Fmt.ONE_DECIMAL, true,
            "An estimate of how much oxygen your body can use at full effort, calculated from your resting and maximum heart rate (Uth method). It is a rough guide, not a lab test.",
            "VO₂ max is one of the strongest predictors of long-term health. Lower resting heart rate as you get fitter pushes the estimate up.",
            "Roughly 35 to 45 is average for adults, 45 to 55 is good, and above 55 is excellent. It varies a lot with age and sex.",
        ) { it.vo2max },
        MetricDef(
            MetricId.LOAD_RATIO, "Training load balance", Category.ACTIVITY, "", Fmt.ONE_DECIMAL, null,
            "Your last 7 days of strain compared with your last 28. A ratio of 1.0 means this week matches your usual load.",
            "Building load gradually makes you fitter. Spiking it too fast is when injuries and illness creep in.",
            "0.8 to 1.3 is the sweet spot. Above 1.5 is a spike that raises injury risk. Below 0.8 means you are detraining.",
        ) { it.loadRatio },
        MetricDef(
            MetricId.DAY_STRESS, "Daytime stress", Category.MIND, "", Fmt.INT, false,
            "A 0 to 100 estimate of stress across the day, from your heart rate and heart-rate variability whenever you are sitting still, compared with your own daytime norm.",
            "It shows when stress builds up, so you can break the pattern before it follows you into the night.",
            "Under 35 is relaxed, 35 to 65 is moderate, and over 65 is high.",
        ) { it.dayStress },
        MetricDef(
            MetricId.STRESS_HIGH, "High-stress time", Category.MIND, "", Fmt.DURATION, false,
            "How long your body spent in a high-stress state while resting during the day.",
            "Long stretches of high stress without recovery gaps wear down sleep and HRV.",
            "Under 30 minutes is low. Several hours is a signal to build in breaks.",
        ) { it.stressHighMin },
        MetricDef(
            MetricId.SKIN_TEMP_REL, "Skin temperature (relative)", Category.BODY, "σ", Fmt.ONE_DECIMAL, null,
            "How far last night's skin temperature is from your own recent nights, in standard deviations. WHOOP 4.0 only reports an uncalibrated sensor value, so Loop cannot show real degrees for it.",
            "A sustained shift in either direction can flag illness or a different sleeping environment.",
            "Within 1 is normal for you. Beyond 2 is a notable shift.",
        ) { it.skinIdx },
        MetricDef(
            MetricId.WEAR_TIME, "Wear time", Category.BODY, "", Fmt.DURATION, null,
            "How long your strap was on your wrist and reading a heartbeat during the day.",
            "Scores are only as good as the data behind them. Gaps in wear time can hide your real sleep and strain.",
            "Aim for 22 hours or more, taking it off only to charge.",
        ) { it.wearMin },
        MetricDef(
            MetricId.DT_HR, "Daytime heart rate", Category.ACTIVITY, "bpm", Fmt.INT, null,
            "Your average heart rate across the minutes Loop logged while the strap was connected during the day. Heart rate itself is measured by the strap, but the daily average depends on when you wore it.",
            "A steadily rising daytime average, for the same activity, can mean fatigue, stress, dehydration or illness.",
            "Compare it only with your own days. Lower at the same activity level is better.",
        ) { it.dtHr },
        MetricDef(
            MetricId.DT_HR_LOW, "Lowest steady heart rate", Category.RECOVERY, "bpm", Fmt.INT, false,
            "The lowest heart rate you held steadily for about a minute during the day. It is a daytime stand-in for resting heart rate, usually a little higher than your sleeping value.",
            "It moves with fitness and recovery, so it is a handy check between nights.",
            "A few beats above your sleeping resting heart rate is normal.",
        ) { it.dtHrLow },
        MetricDef(
            MetricId.DT_HRV, "Daytime HRV (RMSSD)", Category.RECOVERY, "ms", Fmt.INT, true,
            "Heart-rate variability measured live while your heart rate was steady, as the typical beat-to-beat change (RMSSD). It is an estimate from short windows.",
            "It shows how calm your nervous system is through the day. Daytime values run lower than overnight ones.",
            "Judge it against your own daytime average, not your sleeping HRV. Dips usually follow stress, caffeine or poor sleep.",
        ) { it.dtHrv },
        MetricDef(
            MetricId.DT_SDNN, "Daytime HRV (SDNN)", Category.RECOVERY, "ms", Fmt.INT, true,
            "The overall spread of your heartbeat intervals, a broader view of variability than RMSSD that also reflects slower rhythms. An estimate from short live windows.",
            "SDNN rises with fitness and recovery and falls under chronic stress.",
            "Higher than your own norm is good.",
        ) { it.dtSdnn },
        MetricDef(
            MetricId.DT_BREATH, "Daytime breathing rate (est.)", Category.BODY, "br/min", Fmt.ONE_DECIMAL, null,
            "Breaths per minute estimated from the way your heartbeat speeds up and slows down with each breath. It is only reported when you were sitting still for a few minutes.",
            "Resting breathing rate rises with stress, illness and fatigue. Treat this as a rough check, not a measurement.",
            "12 to 20 is typical at rest.",
        ) { it.dtBreath },
        MetricDef(
            MetricId.DT_STRESS, "Daytime stress (live, est.)", Category.MIND, "", Fmt.INT, false,
            "A 0 to 100 estimate from your live heart rate and variability against your own daytime norm, averaged over the minutes you were still.",
            "It tracks how much strain your body carried through the day.",
            "Under 35 is relaxed, 35 to 65 moderate, above 65 high.",
        ) { it.dtStress },
        MetricDef(
            MetricId.DT_COHERENCE, "Rhythm coherence (est.)", Category.MIND, "%", Fmt.INT, true,
            "How smooth and single-toned your slow heart rhythm was, from 0 to 100. Slow, even breathing makes it climb.",
            "High coherence goes with calm focus. It is a good way to see whether a breathing session worked.",
            "Higher is better. Brief spikes during guided breathing are normal.",
        ) { it.dtCoherence },
        MetricDef(
            MetricId.DT_LFHF, "Autonomic balance (LF/HF, est.)", Category.MIND, "", Fmt.ONE_DECIMAL, null,
            "The ratio of slower to faster heart-rate rhythms. Higher leans towards a 'fight or flight' state, lower towards 'rest and digest'. It is a debated, rough measure.",
            "Useful as a trend within one person, not as a diagnosis.",
            "Roughly 1 to 3 is common at rest. Look at your own trend.",
        ) { it.dtLfHf },
        MetricDef(
            MetricId.DT_KCAL, "Daytime energy (est.)", Category.ACTIVITY, "kcal", Fmt.INT, null,
            "Energy burned above resting, estimated from your logged heart rate, weight, age and sex. It only counts minutes with an elevated heart rate.",
            "A rough guide to how active the day was.",
            "Depends on your goals.",
        ) { it.dtKcal },
        MetricDef(
            MetricId.RHYTHM_IRREG, "Overnight rhythm irregularity", Category.BODY, "%", Fmt.INT, false,
            "The share of your night, in 5-minute stretches, where the timing between heartbeats was uneven in the way an irregular heart rhythm looks. It is worked out from the strap's beat-to-beat intervals. It cannot diagnose anything.",
            "A lot of irregular beat timing is a good reason to see a clinician.",
            "Under 5% is typical. A high share that keeps recurring, or comes with palpitations or dizziness, is worth mentioning to a clinician.",
        ) { it.rhythmIrreg },
        MetricDef(
            MetricId.DT_LOGGED, "Live data logged", Category.BODY, "", Fmt.DURATION, true,
            "How many minutes of live heart data Loop recorded during the day.",
            "Daytime estimates are only as complete as the time your strap was connected.",
            "More coverage gives a truer picture.",
        ) { it.dtMin },
    )

    private val byId = all.associateBy { it.id }
    operator fun get(id: MetricId): MetricDef = byId.getValue(id)
    fun of(category: Category): List<MetricDef> = all.filter { it.category == category }

    /** The metrics shown in everyday lists: no advanced numbers, and one entry per family. */
    fun listed(category: Category): List<MetricDef> = of(category).filter { Families.isListed(it.id) }

    /** Extra context shown only to people who chose female in their profile. Nothing about the menstrual cycle appears for anyone else. */
    fun femaleNote(id: MetricId): String? = when (id) {
        MetricId.SKIN_TEMP -> "In many people it also rises in the second half of the menstrual cycle."
        MetricId.SKIN_TEMP_REL -> "It can also shift with the menstrual cycle."
        else -> null
    }

    /** When a metric is produced: overnight, live through the day, or across the whole 24 hours. */
    fun window(id: MetricId): Window = when (id) {
        MetricId.RECOVERY, MetricId.HRV, MetricId.RHR, MetricId.SLEEP_SCORE, MetricId.SLEEP_DURATION, MetricId.SLEEP_EFFICIENCY,
        MetricId.DEEP, MetricId.REM, MetricId.SLEEP_DEBT, MetricId.RHYTHM_IRREG, MetricId.RESP, MetricId.SKIN_TEMP, MetricId.SKIN_TEMP_REL,
        MetricId.STRESS -> Window.NIGHT
        MetricId.DT_HR, MetricId.DT_HR_LOW, MetricId.DT_HRV, MetricId.DT_SDNN, MetricId.DT_BREATH, MetricId.DT_STRESS,
        MetricId.DT_COHERENCE, MetricId.DT_LFHF, MetricId.DT_KCAL, MetricId.DT_LOGGED, MetricId.DAY_STRESS, MetricId.STRESS_HIGH,
        MetricId.WEAR_TIME -> Window.DAY
        else -> Window.WHOLE
    }
}
