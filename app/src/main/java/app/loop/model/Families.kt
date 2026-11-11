package app.loop.model

/** One way of looking at a measure: the label shown in the toggle, and the metric behind it. */
class Facet(val label: String, val id: MetricId)

/**
 * The same thing measured at different times or in different ways, such as heart-rate variability overnight and
 * heart-rate variability through the day. Loop lists one entry for the whole family (the first facet) and shows the
 * others as a toggle on that metric's page, instead of as separate metrics.
 */
class Family(val facets: List<Facet>) {
    constructor(vararg f: Facet) : this(f.toList())
    val primary: MetricId get() = facets.first().id
}

object Families {
    val all: List<Family> = listOf(
        Family(Facet("Night", MetricId.HRV), Facet("Day", MetricId.DT_HRV)),
        Family(Facet("Night", MetricId.RHR), Facet("Day", MetricId.DT_HR_LOW)),
        Family(Facet("Night", MetricId.RESP), Facet("Day", MetricId.DT_BREATH)),
        Family(Facet("Night", MetricId.STRESS), Facet("Day", MetricId.DAY_STRESS), Facet("Live", MetricId.DT_STRESS)),
        Family(Facet("Workouts", MetricId.CALORIES), Facet("All day", MetricId.DT_KCAL)),
        Family(Facet("Degrees", MetricId.SKIN_TEMP), Facet("Relative", MetricId.SKIN_TEMP_REL)),
    )

    /** Specialist or data-quality numbers. They are still there, under "Advanced", but not in everyday lists. */
    val advanced: Set<MetricId> = setOf(
        MetricId.DT_SDNN, MetricId.DT_COHERENCE, MetricId.DT_LFHF, MetricId.STRESS_HIGH, MetricId.DT_LOGGED, MetricId.WEAR_TIME,
    )

    fun familyOf(id: MetricId): Family? = all.firstOrNull { f -> f.facets.any { it.id == id } }

    fun isAdvanced(id: MetricId) = id in advanced

    /** True when [id] appears in everyday lists: not advanced, and the lead metric of its family. */
    fun isListed(id: MetricId): Boolean = id !in advanced && (familyOf(id)?.primary ?: id) == id

    /** Daytime is a time of day, not a kind of number, so it is no longer shown as a category. */
    val categories: List<Category> = Category.entries.filter { it != Category.DAYTIME }
}
