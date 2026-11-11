package app.loop.model

enum class Schedule(val label: String) { DAILY("Every day"), AS_NEEDED("When needed") }

/** A medicine or supplement. Daily ones count as taken every day from [start] to [end]; "when needed" ones need a tick per dose. */
data class Medicine(val id: Long, val name: String, val dose: String, val schedule: Schedule, val start: String, val end: String? = null) {
    val active: Boolean get() = end == null
}

/** One dose of a "when needed" medicine. */
data class DoseTaken(val medId: Long, val day: String)

enum class ProblemKind(val label: String) { INJURY("Injury"), ILLNESS("Illness") }

/** An injury or illness: what, where, when it began and how bad it felt (1 to 10). [end] is set once it has healed. */
data class Problem(val id: Long, val kind: ProblemKind, val area: String, val start: String, val severity: Int, val end: String? = null) {
    val active: Boolean get() = end == null
    val title: String get() = if (kind == ProblemKind.ILLNESS) area else "$area injury"
}

/** A daily 0 to 10 rating of how an injury or illness feels. */
data class PainEntry(val problemId: Long, val day: String, val level: Int)

data class HealthLog(
    val medicines: List<Medicine> = emptyList(),
    val doses: List<DoseTaken> = emptyList(),
    val problems: List<Problem> = emptyList(),
    val pain: List<PainEntry> = emptyList(),
) {
    val isEmpty: Boolean get() = medicines.isEmpty() && problems.isEmpty()
}
