package app.loop

import app.loop.model.Glossary
import app.loop.model.MetricId
import app.loop.model.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryTest {
    @Test fun everyMetricExplainsHowItIsWorkedOut() {
        MetricId.entries.forEach { id -> assertTrue("$id", Glossary.method(id).length > 40) }
        assertEquals(MetricId.entries.size, Metrics.all.size) // every id has a definition too
    }

    @Test fun everyReferencedTermExists() {
        MetricId.entries.forEach { id -> Glossary.termNames(id).forEach { n -> assertTrue("$id -> $n", Glossary.term(n) != null) } }
        Glossary.live.values.forEach { e -> e.terms.forEach { n -> assertTrue("${e.title} -> $n", Glossary.term(n) != null) } }
    }

    @Test fun termNamesAreUnique() {
        assertEquals(Glossary.terms.size, Glossary.terms.map { it.name }.toSet().size)
    }
}
