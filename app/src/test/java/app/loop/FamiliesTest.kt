package app.loop

import app.loop.model.Category
import app.loop.model.Families
import app.loop.model.MetricId
import app.loop.model.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FamiliesTest {
    @Test fun onlyTheLeadMetricOfAFamilyIsListed() {
        assertTrue(Families.isListed(MetricId.HRV)); assertFalse(Families.isListed(MetricId.DT_HRV))
        assertTrue(Families.isListed(MetricId.STRESS)); assertFalse(Families.isListed(MetricId.DAY_STRESS)); assertFalse(Families.isListed(MetricId.DT_STRESS))
        assertTrue(Families.isListed(MetricId.SKIN_TEMP)); assertFalse(Families.isListed(MetricId.SKIN_TEMP_REL))
    }

    @Test fun advancedNumbersAreNotListed() {
        Families.advanced.forEach { assertFalse("$it", Families.isListed(it)) }
    }

    @Test fun everyMetricIsStillReachable() {
        // listed, advanced, or a facet of a listed family: nothing is orphaned
        Metrics.all.forEach { m ->
            val reachable = Families.isListed(m.id) || Families.isAdvanced(m.id) || Families.familyOf(m.id) != null
            assertTrue("${m.id} is unreachable", reachable)
        }
    }

    @Test fun aMetricBelongsToOneFamilyAtMost() {
        val ids = Families.all.flatMap { f -> f.facets.map { it.id } }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun daytimeIsNoLongerACategory() {
        assertFalse(Category.DAYTIME in Families.categories)
        assertTrue(Metrics.of(Category.DAYTIME).isEmpty())
        assertNull(Families.familyOf(MetricId.RECOVERY))
    }

    @Test fun everyDayStressStyleNumberSitsInARealCategory() {
        Metrics.listed(Category.MIND).map { it.id }.let { assertTrue(MetricId.STRESS in it && MetricId.MOOD in it) }
        assertTrue(Metrics.listed(Category.RECOVERY).size < Metrics.of(Category.RECOVERY).size)
    }
}
