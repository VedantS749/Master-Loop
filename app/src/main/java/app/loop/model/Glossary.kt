package app.loop.model

/** A plain-language definition of a term used somewhere in Loop. */
class Term(val name: String, val meaning: String)

/** A glossary entry for something that is not a stored metric, such as a live tile. */
class LiveEntry(val title: String, val what: String, val how: String, val terms: List<String>)

/**
 * The glossary: for every metric, how Loop actually works it out (not just what it means), and the terms that explanation
 * relies on. Tapping any metric shows this, so nothing in the app is a black box.
 */
object Glossary {
    val terms: List<Term> = listOf(
        Term("Heart-rate variability (HRV)", "How much the time between heartbeats varies from beat to beat. A relaxed, recovered nervous system gives a more variable heartbeat."),
        Term("R-R interval", "The time in milliseconds between two successive heartbeats. Every HRV number is built from these."),
        Term("RMSSD", "Root mean square of successive differences: the typical size of the change from one beat interval to the next. It reflects the fast, calming (parasympathetic) side of the nervous system and is the standard short-term HRV measure."),
        Term("SDNN", "Standard deviation of beat intervals: the overall spread, including slower rhythms. A broader HRV view than RMSSD."),
        Term("pNN50", "The percentage of successive beat-to-beat changes larger than 50 ms. Another measure of fast variability."),
        Term("Baseline", "Your own recent normal for a measure, from your last 28 days. Most body signals are only meaningful compared with your baseline, not with other people."),
        Term("Z-score", "How far a value is from your baseline, counted in your own typical spreads. Zero is exactly normal, plus or minus two is unusual."),
        Term("MAD", "Median absolute deviation: a robust measure of spread that a few odd readings can't distort. Loop uses it for daytime norms."),
        Term("Resting heart rate", "Your heart rate when fully at rest. Loop takes it from the quietest part of your sleep."),
        Term("Maximum heart rate (Tanaka)", "Your estimated top heart rate, 208 minus 0.7 times your age. Used to set heart-rate zones."),
        Term("Heart-rate reserve", "The gap between your resting and maximum heart rate. Effort is expressed as a share of this gap."),
        Term("Heart-rate zones", "Five bands of effort, as a share of heart-rate reserve: warm-up from 50%, then light, aerobic, hard and peak up to 100%."),
        Term("TRIMP", "Training impulse: each minute weighted by how hard your heart is working, as a smooth curve of your heart-rate reserve (Banister's method), then added up. It is the basis of strain."),
        Term("Keytel formula", "A published equation that estimates energy burned per minute from heart rate, weight, age and sex."),
        Term("Uth method", "Estimates VO₂ max as 15.3 times your maximum heart rate divided by your resting heart rate."),
        Term("Respiratory sinus arrhythmia", "Your heart speeds up when you breathe in and slows when you breathe out. Loop reads this rhythm in your beat intervals to estimate breathing rate."),
        Term("LF/HF ratio", "The power of slow heart-rate rhythms divided by the power of fast ones. Higher leans towards an alert state, lower towards rest. A rough, debated measure."),
        Term("Coherence", "How single-toned your slow heart rhythm is, from 0 to 100. Slow, even breathing raises it."),
        Term("Sleep efficiency", "Time asleep divided by time in bed."),
        Term("Sleep need", "The amount of sleep you aim for, which you set in your profile."),
        Term("Sleep debt", "How much you've fallen short of your sleep need over the last five nights."),
        Term("Deep sleep and REM", "The two sleep stages most tied to physical repair (deep) and to memory and mood (REM). Loop estimates them from heart rate, variability and movement, so they are estimates, not lab measures."),
        Term("Strain", "A 0 to 100 measure of cardiovascular load, from TRIMP on a logarithmic scale so the first effort counts for more than the last."),
        Term("Acute and chronic load", "Acute load is your last 7 days of strain on average, chronic load your last 28. Their ratio shows whether you are ramping up safely."),
        Term("Skin temperature deviation", "How far last night's skin temperature is from the median of your previous nights."),
        Term("Estimate", "A number worked out from limited data with a simple method. It is useful as a guide, but rougher than a measured value. Loop labels every live number this way."),
        Term("Night-time versus daytime", "Night-time metrics are scored overnight with methods based on NOOP's documented approach. Daytime metrics are live estimates logged while your strap is connected, and are kept separate."),
    )

    private val termMap = terms.associateBy { it.name }
    fun term(name: String): Term? = termMap[name]

    /** How Loop works this metric out, in the same terms the code uses. */
    fun method(id: MetricId): String = when (id) {
        MetricId.RECOVERY -> "Last night's HRV (judged in logs, against your 28-day baseline and your last week), resting heart rate, breathing rate, sleep length, sleep debt and efficiency, and skin temperature are each compared with your own normal as z-scores. HRV counts for 50%, resting heart rate 20%, sleep 20%, breathing and skin temperature 5% each. Nights of the same type (weekday or weekend) count a little more, and illness days are left out of your normal. The combined score goes through a logistic curve onto 0 to 100, with an average night landing near 58. It needs HRV and about five nights of history, and says when it is less sure. Scores imported from WHOOP are shown as WHOOP calculated them."
        MetricId.HRV -> "Loop finds your longest still, low-heart-rate stretch overnight, cuts it into 5-minute epochs, computes RMSSD from the clean beat intervals in each (dropping any interval over 20% off its neighbour), and reports the median across the night."
        MetricId.RHR -> "The average of the quietest quarter of the 5-minute mean heart rates during the detected sleep, rounded to a whole number. Using a quarter of the night, not the single lowest dip, keeps one odd stretch from setting it."
        MetricId.SLEEP_SCORE -> "50% from time asleep against your sleep need, 25% from sleep efficiency (70% scores zero, 95% scores full), and 25% from how much of your sleep was deep plus REM (40% of sleep scores full). Imported WHOOP sleep performance is used as given."
        MetricId.SLEEP_DURATION -> "Minutes judged asleep inside the sleep window Loop detected from low movement and low heart rate, allowing wake bouts of up to 15 minutes. From an import it is the figure WHOOP reported."
        MetricId.SLEEP_EFFICIENCY -> "Minutes asleep divided by minutes in bed, as a percentage."
        MetricId.DEEP, MetricId.REM -> "Each 5-minute epoch of sleep is scored from its heart rate, variability and movement. Deep sleep favours low heart rate, high HRV, stillness and the early night; REM favours higher, more variable heart rate and the later night. The top-scoring epochs of each kind (about 18% and 22%) are assigned. It is an estimate; only a sleep lab can measure stages."
        MetricId.SLEEP_DEBT -> "The sum of your sleep need minus actual sleep over the last five nights that have data, never below zero."
        MetricId.STRAIN -> "Every minute of the day is weighted by how hard your heart is working, as a smooth curve of your heart-rate reserve (Banister TRIMP), using your real maximum heart rate once Loop has seen it. Ordinary daily movement counts nothing. Steps and logged strength sessions add a little. The total is mapped logarithmically onto 0 to 100 and scaled so your own hardest typical days read about 85."
        MetricId.CALORIES -> "The Keytel equation turns each minute's heart rate, your weight, age and sex into kilocalories, counting only minutes above the lowest heart-rate zone."
        MetricId.STEPS -> "From the strap, Loop adds up the positive changes in its pedometer counter between consecutive records, ignoring gaps and implausible jumps. Imports use the step count they contain."
        MetricId.RESP -> "During sleep, Loop resamples your beat intervals and looks for the strongest rhythm between 6 and 30 breaths a minute (respiratory sinus arrhythmia), then takes the median across 5-minute epochs."
        MetricId.SKIN_TEMP -> "Last night's mean skin temperature minus the median of your previous up to 14 nights. It needs at least three earlier nights."
        MetricId.SKIN_TEMP_REL -> "A WHOOP 4.0 reports only an uncalibrated sensor value, so Loop expresses last night's reading as a number of standard deviations from your previous nights. It is not in degrees."
        MetricId.WEIGHT -> "Entered in your profile or brought in from an import."
        MetricId.STRESS -> "Last night's HRV and resting heart rate are compared with your baselines: stress is 45 plus 20 times (0.7 times the negative HRV z-score plus 0.3 times the resting heart-rate z-score), kept between 0 and 100."
        MetricId.MOOD -> "Your own 1 to 5 rating from the daily check-in. If you check in more than once a day, Loop averages them."
        MetricId.HR_RECOVERY -> "For each detected workout with a high enough heart rate, Loop takes the peak heart rate in the last two minutes and subtracts the heart rate one minute after it ended. The best drop of the day is kept."
        MetricId.VO2_MAX -> "The Uth method: 15.3 times your maximum heart rate (Tanaka formula) divided by your average resting heart rate over the last week. It is a population-level estimate, not a lab test."
        MetricId.LOAD_RATIO -> "Your average strain over the last 7 days divided by your average over the last 28. It needs at least 4 recent days and 14 days of history."
        MetricId.DAY_STRESS -> "In 5-minute windows when your wrist is still and you are awake, Loop compares heart rate and the log of RMSSD with your own daytime medians (using MAD for the spread). A faster heart and lower variability score higher. The day's value is the average of its windows."
        MetricId.STRESS_HIGH -> "Five minutes for every daytime window that scored 65 or higher."
        MetricId.WEAR_TIME -> "The number of minutes in which the strap recorded at least five clean heartbeat readings."
        MetricId.DT_HR -> "The average of your live heart rate across the minutes logged while your strap was connected."
        MetricId.DT_HR_LOW -> "The lowest per-minute heart rate among minutes when your heart rate had been steady, a daytime stand-in for resting heart rate."
        MetricId.DT_HRV -> "Live RMSSD from the last 90 clean beat intervals, averaged over logged minutes when your heart rate was steady."
        MetricId.DT_SDNN -> "Live SDNN from the last 90 clean beat intervals, averaged over logged minutes when your heart rate was steady."
        MetricId.DT_BREATH -> "Every few seconds, while your heart rate is steady, Loop looks at the last few minutes of beat intervals for a clear breathing rhythm between 8 and 30 breaths a minute, and smooths it. A minute only counts if the rhythm is clear."
        MetricId.DT_STRESS -> "Live heart rate and the log of live RMSSD are scored against your personal daytime norm from strap history, using the same formula as the history-based daytime stress, then averaged over the minutes you were still."
        MetricId.DT_COHERENCE -> "The share of slow-rhythm power (0.04 to 0.26 Hz) concentrated in one peak and its neighbours, on a 0 to 100 scale."
        MetricId.DT_LFHF -> "Power in the 0.04 to 0.15 Hz band divided by power in the 0.15 to 0.40 Hz band of your beat-interval signal, limited to between 0.1 and 10."
        MetricId.DT_KCAL -> "The Keytel equation applied to each logged minute above the lowest heart-rate zone, summed for the day."
        MetricId.DT_LOGGED -> "The number of minutes for which Loop wrote a daytime log row."
        MetricId.RHYTHM_IRREG -> "A screening-style score of how irregular your beat-to-beat timing was overnight. It looks at the regularity of the intervals only, and it is a prompt to see a clinician, never a diagnosis."
    }

    /** The terms (from the list above) that a metric's explanation relies on. */
    fun termsFor(id: MetricId): List<Term> = termNames(id).mapNotNull { term(it) }

    /** Names of the terms a metric relies on; every one must exist in [terms] (checked by a unit test). */
    fun termNames(id: MetricId): List<String> = when (id) {
        MetricId.RECOVERY -> listOf("Baseline", "Z-score", "Heart-rate variability (HRV)", "Resting heart rate")
        MetricId.HRV -> listOf("Heart-rate variability (HRV)", "RMSSD", "R-R interval", "Baseline")
        MetricId.RHR -> listOf("Resting heart rate", "Baseline")
        MetricId.SLEEP_SCORE -> listOf("Sleep need", "Sleep efficiency", "Deep sleep and REM")
        MetricId.SLEEP_DURATION -> listOf("Sleep need", "Estimate")
        MetricId.SLEEP_EFFICIENCY -> listOf("Sleep efficiency")
        MetricId.DEEP, MetricId.REM -> listOf("Deep sleep and REM", "RMSSD", "Estimate")
        MetricId.SLEEP_DEBT -> listOf("Sleep debt", "Sleep need")
        MetricId.STRAIN -> listOf("Strain", "TRIMP", "Heart-rate zones", "Heart-rate reserve", "Maximum heart rate (Tanaka)")
        MetricId.CALORIES, MetricId.DT_KCAL -> listOf("Keytel formula", "Heart-rate zones", "Estimate")
        MetricId.STEPS, MetricId.WEIGHT, MetricId.MOOD -> emptyList()
        MetricId.RESP, MetricId.DT_BREATH -> listOf("Respiratory sinus arrhythmia", "R-R interval", "Estimate")
        MetricId.SKIN_TEMP, MetricId.SKIN_TEMP_REL -> listOf("Skin temperature deviation", "Baseline")
        MetricId.STRESS -> listOf("Baseline", "Z-score", "Heart-rate variability (HRV)", "Resting heart rate")
        MetricId.HR_RECOVERY -> listOf("Heart-rate zones", "Maximum heart rate (Tanaka)")
        MetricId.VO2_MAX -> listOf("Uth method", "Maximum heart rate (Tanaka)", "Resting heart rate", "Estimate")
        MetricId.LOAD_RATIO -> listOf("Acute and chronic load", "Strain")
        MetricId.DAY_STRESS, MetricId.STRESS_HIGH, MetricId.DT_STRESS -> listOf("RMSSD", "MAD", "Baseline", "Estimate", "Night-time versus daytime")
        MetricId.WEAR_TIME, MetricId.DT_LOGGED -> listOf("Night-time versus daytime")
        MetricId.DT_HR, MetricId.DT_HR_LOW -> listOf("Resting heart rate", "Night-time versus daytime", "Estimate")
        MetricId.DT_HRV -> listOf("RMSSD", "R-R interval", "Night-time versus daytime", "Estimate")
        MetricId.DT_SDNN -> listOf("SDNN", "R-R interval", "Night-time versus daytime", "Estimate")
        MetricId.DT_COHERENCE -> listOf("Coherence", "Respiratory sinus arrhythmia", "Estimate")
        MetricId.DT_LFHF -> listOf("LF/HF ratio", "Heart-rate variability (HRV)", "Estimate")
        MetricId.RHYTHM_IRREG -> listOf("R-R interval", "Estimate")
    }

    /** Entries for live tiles that are not stored metrics. Keys match the tile titles. */
    val live: Map<String, LiveEntry> = mapOf(
        "pnn50" to LiveEntry("pNN50", "The share of heartbeat-to-heartbeat changes bigger than 50 ms in the last minute or two.",
            "Counted over the last 90 clean beat intervals: each successive difference over 50 ms counts, divided by the total number of differences.", listOf("pNN50", "R-R interval", "Estimate")),
        "effort" to LiveEntry("Effort", "How hard your heart is working right now, as a share of the gap between your resting and maximum heart rate.",
            "(Heart rate minus resting heart rate) divided by (maximum minus resting). Resting heart rate is your recent overnight value, or 60 if there isn't one yet; maximum comes from the Tanaka formula.",
            listOf("Heart-rate reserve", "Resting heart rate", "Maximum heart rate (Tanaka)", "Heart-rate zones", "Estimate")),
        "trend" to LiveEntry("Heart-rate trend", "Whether your heart rate has been rising or falling over the last minute, in beats per minute per minute.",
            "The average of the first 10 seconds of the last minute subtracted from the average of the last 10 seconds, scaled to a per-minute rate.", emptyList()),
        "beat" to LiveEntry("Beat interval", "The average time between your recent heartbeats, in milliseconds. 1000 ms is 60 beats a minute.",
            "The mean of the last 90 clean beat intervals reported by the strap.", listOf("R-R interval")),
        "quality" to LiveEntry("Signal quality", "The share of recently received heartbeat intervals that passed Loop's checks. Low values mean the live estimates are less trustworthy.",
            "Intervals outside 300 to 2000 ms, or more than 20% different from the previous one, are rejected as motion noise or missed beats. The quality is a running average of kept versus received.", listOf("R-R interval", "Estimate")),
    )
}
