package app.loop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.loop.analytics.Scoring
import app.loop.model.DayRecord
import app.loop.model.MetricDef
import app.loop.model.MetricId
import app.loop.ui.screens.deltaVs
import app.loop.ui.theme.good
import app.loop.ui.theme.warn
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Tone { GOOD, OK, WARN, NEUTRAL }

/** Appended to every live, daytime interpretation so nobody mistakes it for a measured, overnight value. */
private const val est = " This is an estimate from live heartbeat timing."

/** A value turned into words: a short verdict, what it means for the person, and how it compares with their normal. */
data class Interpretation(val headline: String, val meaning: String, val tone: Tone, val vsNormal: String? = null)

@Composable
fun Tone.color(): Color = when (this) {
    Tone.GOOD -> MaterialTheme.colorScheme.good
    Tone.OK -> MaterialTheme.colorScheme.primary
    Tone.WARN -> MaterialTheme.colorScheme.warn
    Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Plain-language significance of a metric value. Uses the user's own baseline where one exists, because for most
 * body signals "normal" is personal. [day] supplies same-day context (e.g. total sleep for the stage shares).
 */
fun interpret(def: MetricDef, v: Double?, dash: Dashboard, day: DayRecord? = null): Interpretation? {
    if (v == null) return null
    val s = dash.settings
    val base = dash.baseline(def)
    val d = day ?: dash.latest
    val vs = deltaVs(def, v, dash)?.text
    fun i(h: String, m: String, t: Tone) = Interpretation(h, m, t, vs)

    // A total that is still building up today cannot be judged yet: 70 steps at 4 a.m. is not "sedentary".
    if (app.loop.analytics.HealthOverviews.inProgress(def.id, d?.day)) {
        return Interpretation("So far today", "Today's total so far. Loop judges it once the day is over, because it keeps building until midnight.", Tone.NEUTRAL)
    }

    return when (def.id) {
        MetricId.RECOVERY -> when {
            v >= 67 -> i("Well recovered", "Your body has bounced back. It can handle a hard session today, and training now builds fitness most efficiently.", Tone.GOOD)
            v >= 34 -> i("Partly recovered", "You're in the middle. Moderate effort is fine, but a very hard day may leave you flat tomorrow.", Tone.OK)
            else -> i("Under-recovered", "Your body is still repairing. Rest or easy movement protects you from illness and injury. Pushing hard usually backfires.", Tone.WARN)
        }
        MetricId.HRV -> {
            if (base == null) Interpretation("Building your baseline", "Heart-rate variability is personal, so Loop needs about five nights before it can say whether ${v.roundToInt()} ms is high or low for you.", Tone.NEUTRAL)
            else {
                val pct = (v - base.mean) / base.mean * 100
                when {
                    pct >= 8 -> i("Above your normal", "A calm, adaptable nervous system. This usually follows good sleep, low stress and well-judged training.", Tone.GOOD)
                    pct > -8 -> i("In your normal range", "Your nervous system is in its usual balance. Nothing unusual to act on.", Tone.OK)
                    else -> i("Below your normal", "Stress, short sleep, alcohol, illness or heavy training can all pull HRV down. Take it easier and protect tonight's sleep.", Tone.WARN)
                }
            }
        }
        MetricId.RHR -> {
            val diff = base?.let { v - it.mean }
            when {
                diff == null -> Interpretation("Resting heart rate", "Lower generally means a fitter, more rested heart. Loop will compare it with your own normal after a few nights.", Tone.NEUTRAL)
                diff <= -2 -> i("Lower than usual", "A relaxed, well-recovered heart. This often accompanies improving fitness.", Tone.GOOD)
                diff < 2 -> i("Steady", "Right where it normally sits. Your body is in balance.", Tone.OK)
                diff < 5 -> i("A little elevated", "A few beats above your normal can come from poor sleep, stress, dehydration or alcohol. That is worth noticing.", Tone.WARN)
                else -> i("Clearly elevated", "5 or more beats above your normal is an early sign of illness, overtraining or serious dehydration. Rest and watch for symptoms.", Tone.WARN)
            }
        }
        MetricId.SLEEP_SCORE -> when {
            v >= 85 -> i("Excellent night", "You slept long enough, with few interruptions and good restorative sleep. Your body and brain got what they needed.", Tone.GOOD)
            v >= 70 -> i("Good night", "Mostly restorative. Small gains are available from a slightly earlier bedtime or fewer interruptions.", Tone.GOOD)
            v >= 50 -> i("Fair night", "Short or broken sleep. Expect lower energy and focus, and go easier on hard training.", Tone.OK)
            else -> i("Poor night", "You fell well short of what your body needs. Prioritise an early night and avoid caffeine late in the day.", Tone.WARN)
        }
        MetricId.SLEEP_DURATION -> {
            val ratio = v / s.sleepNeedMin
            val short = Scoring.hm((s.sleepNeedMin - v).coerceAtLeast(0.0))
            when {
                ratio >= 0.95 -> i("Enough sleep", "You met your sleep need. Consistent full nights protect mood, immunity and recovery.", Tone.GOOD)
                ratio >= 0.8 -> i("Slightly short", "You were $short under your need. One short night is fine, but repeated ones build sleep debt.", Tone.OK)
                else -> i("Short on sleep", "You were $short under your need. This is enough to dull focus, mood and recovery. Bring bedtime forward tonight.", Tone.WARN)
            }
        }
        MetricId.SLEEP_EFFICIENCY -> when {
            v >= 90 -> i("Very efficient", "You spent almost all of your time in bed asleep, a sign of deep, uninterrupted rest.", Tone.GOOD)
            v >= 85 -> i("Healthy", "A normal level of wakefulness. This is the usual benchmark for good sleep.", Tone.GOOD)
            else -> i("Fragmented", "A lot of time in bed was spent awake. Caffeine, alcohol, stress, noise or a warm room are common causes.", Tone.WARN)
        }
        MetricId.DEEP -> share(def, v, d?.sleepMin, 13.0, 23.0, "Deep sleep is when your body repairs muscle and tissue.", "restorative", ::i)
        MetricId.REM -> share(def, v, d?.sleepMin, 20.0, 25.0, "REM sleep supports memory, learning and mood.", "mentally restorative", ::i)
        MetricId.SLEEP_DEBT -> when {
            v < 60 -> i("Rested", "You owe almost no sleep. Your recovery isn't being held back by past nights.", Tone.GOOD)
            v < 180 -> i("Some debt", "You've missed a little sleep recently. An early night or two will clear it.", Tone.OK)
            else -> i("Significant debt", "You owe ${Scoring.hm(v)} of sleep. It drags down recovery, mood and focus until you pay it back.", Tone.WARN)
        }
        MetricId.STRAIN -> {
            val rec = d?.recovery
            val band = Scoring.strainWord(v)
            if (rec == null) i("$band effort", "Strain measures the load on your heart across the day. Pair it with recovery to judge whether the effort was right.", Tone.NEUTRAL)
            else {
                val t = Scoring.strainTarget(rec)
                when {
                    v > t.last + 10 -> i("$band, above target", "That's more than your ${rec.roundToInt()}% recovery supports (aim for ${t.first} to ${t.last}). Expect it to cost you tomorrow.", Tone.WARN)
                    v >= t.first -> i("$band, on target", "Right in the range your ${rec.roundToInt()}% recovery supports (${t.first} to ${t.last}). Good balance of effort and rest.", Tone.GOOD)
                    else -> i("$band, below target", "Under the ${t.first} to ${t.last} your ${rec.roundToInt()}% recovery could handle. Fine for a rest day; otherwise there's room to do more.", Tone.OK)
                }
            }
        }
        MetricId.CALORIES -> Interpretation("Workout energy", "About ${v.roundToInt()} kcal burned in workouts, estimated from your heart rate, weight and age. Most useful for comparing your own sessions over time.", Tone.NEUTRAL)
        MetricId.STEPS -> when {
            v >= 10000 -> i("Very active", "At 10,000 or more steps you're well into the range linked to better heart health and longevity.", Tone.GOOD)
            v >= 7000 -> i("Active", "Between 7,000 and 10,000 steps is where most of the health benefit lies.", Tone.GOOD)
            v >= 4000 -> i("Lightly active", "A modest day. Short walks after meals are an easy way to add a few thousand steps.", Tone.OK)
            else -> i("Sedentary", "Very little everyday movement. Even gentle walking helps your heart, mood and sleep.", Tone.WARN)
        }
        MetricId.RESP -> {
            val diff = base?.let { v - it.mean }
            when {
                diff == null -> if (v in 12.0..20.0) Interpretation("Typical", "12 to 20 breaths a minute is the normal adult range at rest.", Tone.GOOD)
                else Interpretation("Outside typical", "Most adults breathe 12 to 20 times a minute asleep. Loop will learn what's normal for you.", Tone.OK)
                diff > 1.0 -> i("Higher than usual", "A rise of a breath or more per minute is one of the earliest signs of illness, stress or poor recovery.", Tone.WARN)
                diff < -1.0 -> i("Lower than usual", "Slower breathing than normal, often seen with deep relaxation or good fitness.", Tone.OK)
                else -> i("Steady", "Your breathing rate is very stable from night to night, so this is a reassuring sign.", Tone.GOOD)
            }
        }
        MetricId.SKIN_TEMP -> when {
            abs(v) < 0.5 -> i("Normal", "Within about half a degree of your baseline. Nothing to flag.", Tone.GOOD)
            abs(v) < 1.0 -> i(if (v > 0) "Slightly warm" else "Slightly cool", "A small shift from your baseline. Room temperature, bedding and alcohol all affect this. Watch the trend.", Tone.OK)
            v > 0 -> i("Elevated", "A sustained rise of a degree or more can signal fever or the start of an illness${if (s.female) ", or a hormonal-cycle shift" else ""}. Look at it alongside resting heart rate.", Tone.WARN)
            else -> i("Cooler than usual", "A large drop from baseline is usually environmental, such as a cold room or sleeping uncovered.", Tone.OK)
        }
        MetricId.WEIGHT -> {
            val diff = base?.let { v - it.mean }
            val unit = if (s.imperial) "lb" else "kg"
            val mag = diff?.let { "%.1f".format(abs(it) * if (s.imperial) 2.20462 else 1.0) }
            if (diff == null || mag == null || abs(diff) < 0.4) Interpretation("Stable", "Day-to-day weight moves with water and meals. Only the slow trend matters.", Tone.NEUTRAL)
            else Interpretation(if (diff > 0) "Up on average" else "Down on average", "$mag $unit ${if (diff > 0) "above" else "below"} your recent average. Judge change over weeks, not days.", Tone.NEUTRAL)
        }
        MetricId.STRESS -> when {
            v < 35 -> i("Low", "Your body shows little sign of strain. Heart-rate variability and resting heart rate are at or better than your baseline.", Tone.GOOD)
            v < 65 -> i("Moderate", "A normal amount of load. Short breathing sessions and a wind-down routine can bring it lower.", Tone.OK)
            else -> i("High", "Your nervous system is working hard. Prioritise rest, sleep and the Breathe tool, and be wary of piling on intense training.", Tone.WARN)
        }
        MetricId.DT_HR -> {
            val diff = base?.let { v - it.mean }
            when {
                diff == null -> Interpretation("Daytime average", "Your average heart rate while the strap was connected today. It depends on how active you were, so compare it with your own days.", Tone.NEUTRAL)
                diff >= 5 -> i("Higher than usual", "About ${diff.roundToInt()} bpm above your usual daytime average. Stress, heat, caffeine, poor sleep or the start of illness can all do this.", Tone.WARN)
                diff <= -4 -> i("Lower than usual", "A calmer day for your heart than normal, or a fitter you.", Tone.GOOD)
                else -> i("Typical", "In line with your usual daytime heart rate.", Tone.OK)
            }
        }
        MetricId.DT_HR_LOW -> {
            val night = d?.rhr
            when {
                night == null -> Interpretation("Daytime low", "The lowest steady heart rate you held today. It will sit a little above your sleeping resting heart rate once Loop has a night to compare.", Tone.NEUTRAL)
                v - night <= 12 -> i("Close to resting", "Only ${(v - night).roundToInt()} bpm above your sleeping resting heart rate, so your body found a calm baseline during the day.", Tone.GOOD)
                else -> i("Well above resting", "${(v - night).roundToInt()} bpm above your sleeping resting heart rate. You may not have had a truly still moment today, or stress and caffeine kept it up.", Tone.OK)
            }
        }
        MetricId.DT_HRV, MetricId.DT_SDNN -> {
            if (base == null) Interpretation("Building your daytime norm", "Daytime variability is personal and runs lower than overnight. Loop needs several days of live data before it can judge this.$est", Tone.NEUTRAL)
            else {
                val pct = (v - base.mean) / base.mean * 100
                when {
                    pct >= 10 -> i("Above your daytime norm", "Your nervous system looked unusually relaxed and adaptable today.$est", Tone.GOOD)
                    pct > -10 -> i("In your daytime range", "Nothing unusual compared with your other days.$est", Tone.OK)
                    else -> i("Below your daytime norm", "Variability was lower than usual, which often follows stress, short sleep, caffeine or illness.$est", Tone.WARN)
                }
            }
        }
        MetricId.DT_BREATH -> when {
            v in 12.0..20.0 -> i("Typical", "A normal resting breathing rate.$est", Tone.GOOD)
            v > 20 -> i("Fast", "Higher than a calm resting rate. Stress, caffeine, a recent effort or illness can raise it.$est", Tone.WARN)
            else -> i("Slow", "Slower than most adults at rest, which is common with fitness or deep relaxation.$est", Tone.OK)
        }
        MetricId.DT_STRESS -> when {
            v < 35 -> i("Relaxed", "Your live heart rate and variability stayed calm whenever you were still.$est", Tone.GOOD)
            v < 65 -> i("Moderate", "A normal amount of strain through the day.$est", Tone.OK)
            else -> i("High", "Your body spent a lot of still time under strain. Add breaks and a wind-down.$est", Tone.WARN)
        }
        MetricId.DT_COHERENCE -> when {
            v >= 50 -> i("Smooth rhythm", "Your slow heart rhythm was clean and even, the signature of calm, steady breathing.$est", Tone.GOOD)
            v >= 25 -> i("Mixed", "Some rhythm, but it wandered. Slow, even breathing makes it climb.$est", Tone.OK)
            else -> i("Scattered", "No clear slow rhythm, which is normal when you are active or distracted.$est", Tone.OK)
        }
        MetricId.DT_LFHF -> when {
            v <= 2.0 -> i("Leaning calm", "Your heart rhythm leaned towards rest and recovery. A rough, debated measure.$est", Tone.GOOD)
            v <= 4.0 -> i("Balanced", "A balance between alertness and calm.$est", Tone.OK)
            else -> i("Leaning alert", "Your rhythm leaned towards the alert 'fight or flight' side. Look at it as a trend, not a verdict.$est", Tone.WARN)
        }
        MetricId.DT_KCAL -> Interpretation("Estimated extra energy", "About ${v.roundToInt()} kcal burned above rest across the minutes Loop logged. A rough guide to how active your day was.$est", Tone.NEUTRAL)
        MetricId.DT_LOGGED -> when {
            v >= 600 -> i("Good coverage", "Loop logged most of your day, so the daytime estimates are well supported.", Tone.GOOD)
            v >= 240 -> i("Partial coverage", "Several hours logged. More connected time gives a fuller picture.", Tone.OK)
            else -> i("Little logged", "Only a short stretch was logged, so daytime averages may not represent the day. Keep the strap connected.", Tone.WARN)
        }
        MetricId.HR_RECOVERY -> when {
            v >= 25 -> i("Excellent", "Your heart rate drops fast once you stop. That signals a strong heart and a well-tuned nervous system.", Tone.GOOD)
            v >= 18 -> i("Good", "A healthy drop after hard effort. It tends to improve as aerobic fitness does.", Tone.GOOD)
            v >= 12 -> i("Average", "A typical recovery. Steady aerobic training and good sleep nudge this upward over time.", Tone.OK)
            else -> i("Slow", "A slow drop after effort can come from fatigue, dehydration or illness. A persistent value under 12 is worth mentioning to a doctor.", Tone.WARN)
        }
        MetricId.VO2_MAX -> {
            val adj = v + if (s.female) 5 else 0
            when {
                adj >= 50 -> i("Excellent fitness", "Your estimated aerobic capacity is in the top tier. This is strongly linked to long-term health.", Tone.GOOD)
                adj >= 40 -> i("Good fitness", "Above average. Regular aerobic training at a steady pace is the best way to push it higher.", Tone.GOOD)
                adj >= 30 -> i("Average fitness", "Around the middle for adults. A few weekly sessions of easy-to-moderate cardio can raise it noticeably.", Tone.OK)
                else -> i("Room to build", "Below average, which also means the biggest gains are available. Build up gradually with easy aerobic work.", Tone.WARN)
            }
        }
        MetricId.LOAD_RATIO -> when {
            v > 1.5 -> i("Load spike", "This week is much heavier than your usual. A jump like this is when injuries and illness show up, so ease off for a few days.", Tone.WARN)
            v > 1.3 -> i("Building fast", "You're ramping up quickly. Fine for a short push, but add recovery soon.", Tone.OK)
            v >= 0.8 -> i("Sweet spot", "Your recent load matches what your body is used to, the zone where fitness builds with the lowest risk.", Tone.GOOD)
            else -> i("Detraining", "You've done less than usual lately. Great for recovery, but fitness will slip if it continues.", Tone.OK)
        }
        MetricId.DAY_STRESS -> when {
            v < 35 -> i("Relaxed day", "Your heart rate and variability stayed calm whenever you were still.", Tone.GOOD)
            v < 65 -> i("Moderate stress", "A normal amount of load. A short breathing session or walk can bring it down.", Tone.OK)
            else -> i("High stress day", "Your body spent a lot of the day under strain. Build in breaks and protect tonight's sleep.", Tone.WARN)
        }
        MetricId.STRESS_HIGH -> when {
            v < 30 -> i("Little high stress", "Only brief spikes while resting. That is healthy.", Tone.GOOD)
            v < 120 -> i("Some high stress", "A couple of hours of elevated stress while resting. Short recovery breaks help.", Tone.OK)
            else -> i("Long high stress", "Hours spent in a high-stress state without relief wear down sleep and HRV. Add breaks and calming routines.", Tone.WARN)
        }
        MetricId.SKIN_TEMP_REL -> when {
            abs(v) < 1.0 -> i("Normal for you", "Last night's skin temperature sits within your usual spread. This value is relative, not degrees.", Tone.GOOD)
            abs(v) < 2.0 -> i("Slight shift", "A small move away from your recent nights. Room temperature and bedding often explain it.", Tone.OK)
            else -> i(if (v > 0) "Warmer than usual" else "Cooler than usual", "A notable shift from your recent nights. Look at resting heart rate and breathing for signs of illness.", Tone.WARN)
        }
        MetricId.WEAR_TIME -> when {
            v >= 1320 -> i("Nearly always on", "Your strap captured almost the whole day, so your scores rest on solid data.", Tone.GOOD)
            v >= 960 -> i("Mostly on", "A good amount of data. Wearing it through the night in particular makes sleep and recovery more reliable.", Tone.OK)
            else -> i("Large gaps", "The strap was off for much of the day, so scores for this day are less reliable.", Tone.WARN)
        }
        MetricId.RHYTHM_IRREG -> {
            val tone = when { v < 5 -> Tone.GOOD; v < 20 -> Tone.OK; else -> Tone.WARN }
            i(if (v < 5) "Regular" else if (v < 20) "Some uneven stretches" else "Often irregular", app.loop.analytics.RhythmCheck.adviceFor(v), tone)
        }
        MetricId.MOOD -> when {
            v >= 4 -> i("Feeling good", "A positive day. Look at what your sleep and recovery were like to see what's helping.", Tone.GOOD)
            v >= 2.5 -> i("Neutral", "A middling day, which is completely normal. Patterns over weeks tell you more than any single rating.", Tone.OK)
            else -> i("Low mood", "It's okay to have low days. If this lasts for weeks, talking to someone you trust or a professional is a good step.", Tone.WARN)
        }
    }
}

private fun share(
    def: MetricDef, v: Double, asleep: Double?, lo: Double, hi: Double, what: String, adj: String,
    make: (String, String, Tone) -> Interpretation,
): Interpretation {
    if (asleep == null || asleep <= 0) return Interpretation("Stage time", "$what Typical adults get about ${lo.roundToInt()} to ${hi.roundToInt()}% of their sleep here.", Tone.NEUTRAL)
    val pct = v / asleep * 100
    return when {
        pct < lo - 3 -> make("Low (${pct.roundToInt()}%)", "$what Under the typical ${lo.roundToInt()} to ${hi.roundToInt()}%. Alcohol, late meals, stress and short nights cut this first.", Tone.WARN)
        pct <= hi + 5 -> make("Healthy (${pct.roundToInt()}%)", "$what Within or near the typical ${lo.roundToInt()} to ${hi.roundToInt()}%, so your night was $adj.", Tone.GOOD)
        else -> make("High (${pct.roundToInt()}%)", "$what Above the typical range, which can happen after sleep loss as your body catches up.", Tone.OK)
    }
}
