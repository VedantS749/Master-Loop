package app.loop

import app.loop.model.Settings
import app.loop.whoop.Cmd
import app.loop.whoop.Decoder
import app.loop.whoop.Family
import app.loop.whoop.Frames
import app.loop.whoop.HistRecord
import app.loop.whoop.Msg
import app.loop.whoop.Reassembler
import app.loop.whoop.StrapAnalyzer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test fun whoop5HelloMatchesKnownFrame() {
        // GET_HELLO (145) rev 1, seq 1 must reproduce the documented fixed CLIENT_HELLO bytes.
        assertArrayEquals(Frames.CLIENT_HELLO5, Frames.command5(145, byteArrayOf(1), 1))
    }

    @Test fun whoop4FrameRoundTripsAndResyncs() {
        val f = Frames.command4(Cmd.GET_BATTERY, byteArrayOf(0), 7)
        val r = Reassembler(Family.WHOOP4)
        // garbage, then the frame split across two notifications
        val out = r.feed(byteArrayOf(1, 2, 3) + f.copyOfRange(0, 5)) + r.feed(f.copyOfRange(5, f.size))
        assertEquals(1, out.size)
        assertArrayEquals(f, out[0])
    }

    @Test fun corruptFrameIsRejected() {
        val f = Frames.command4(Cmd.GET_BATTERY, byteArrayOf(0), 1)
        f[f.size - 1] = (f[f.size - 1] + 1).toByte()
        assertTrue(Reassembler(Family.WHOOP4).feed(f).isEmpty())
    }

    @Test fun whoop5FrameRoundTrips() {
        val f = Frames.command5(Cmd.SEND_HISTORICAL, byteArrayOf(0), 3)
        val out = Reassembler(Family.WHOOP5).feed(f)
        assertEquals(1, out.size)
    }

    @Test fun whoop4HistoryRecordDecodes() {
        // Build a v24 frame by hand: AA len crc8 | type 47, version 24 ... crc32
        val inner = ByteArray(84)
        inner[0] = 47; inner[1] = 24
        val ts = (System.currentTimeMillis() / 1000 - 3600).toInt()
        for (k in 0..3) inner[7 + k] = (ts ushr (8 * k)).toByte() // frame offset 11 = inner 7
        inner[17] = 62 // hr @ frame 21
        inner[18] = 2   // rr count @ 22
        inner[19] = 0x48; inner[20] = 0x03 // 840 ms
        inner[21] = 0x20; inner[22] = 0x03 // 800 ms
        val len = inner.size + 4
        val f = ByteArray(4 + inner.size + 4)
        f[0] = 0xAA.toByte(); f[1] = len.toByte(); f[2] = (len shr 8).toByte()
        f[3] = app.loop.whoop.Crc.crc8(f, 1, 3).toByte()
        inner.copyInto(f, 4)
        val c = app.loop.whoop.Crc.crc32(inner, 0, inner.size)
        for (k in 0..3) f[4 + inner.size + k] = (c ushr (8 * k)).toByte()
        val frames = Reassembler(Family.WHOOP4).feed(f)
        assertEquals(1, frames.size)
        val m = Decoder.decode(frames[0], Family.WHOOP4) as Msg.History
        assertNotNull(m.rec)
        assertEquals(62, m.rec!!.hr)
        assertArrayEquals(intArrayOf(840, 800), m.rec!!.rr)
    }

    @Test fun analyzerFindsASyntheticNight() {
        // 22:00 -> 06:00 still with low HR and noisy RR, 08:00 -> 20:00 moving with higher HR.
        val zone = java.time.ZoneId.of("UTC")
        val day = java.time.LocalDate.now().minusDays(2)
        val recs = ArrayList<HistRecord>()
        val rnd = java.util.Random(1)
        var t = day.atTime(20, 0).atZone(zone).toEpochSecond()
        val end = day.plusDays(1).atTime(20, 0).atZone(zone).toEpochSecond()
        while (t < end) {
            val hourOfDay = ((t % 86400) / 3600).toInt()
            val asleep = hourOfDay >= 22 || hourOfDay < 6
            val hr = if (asleep) 52 + rnd.nextInt(4) else 80 + rnd.nextInt(20)
            val rr = (60000.0 / hr + rnd.nextGaussian() * if (asleep) 40 else 15).toInt()
            val g = if (asleep) 0f else rnd.nextFloat()
            recs += HistRecord(t, hr, intArrayOf(rr), 1, g, 0f, 1f, null, null)
            t += 1
        }
        val out = StrapAnalyzer.analyze(recs, Settings(), 60.0, zone)
        assertEquals(1, out.nights)
        val night = out.days.first { it.sleepMin != null }
        assertTrue("sleep ${night.sleepMin}", night.sleepMin!! in 360.0..540.0)
        assertTrue(night.rhr!! in 48.0..58.0)
    }
}
