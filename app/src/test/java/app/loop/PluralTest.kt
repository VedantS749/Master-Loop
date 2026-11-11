package app.loop

import app.loop.model.plural
import org.junit.Assert.assertEquals
import org.junit.Test

class PluralTest {
    @Test fun oneTakesTheSingular() {
        assertEquals("1 day", plural(1, "day"))
        assertEquals("1 workout", plural(1L, "workout"))
    }

    @Test fun everythingElseTakesThePlural() {
        assertEquals("0 days", plural(0, "day"))
        assertEquals("2 nights", plural(2, "night"))
        assertEquals("12 records", plural(12, "record"))
    }

    @Test fun irregularPluralsCanBeGiven() {
        assertEquals("1 factor", plural(1, "factor"))
        assertEquals("3 people", plural(3, "person", "people"))
    }
}
