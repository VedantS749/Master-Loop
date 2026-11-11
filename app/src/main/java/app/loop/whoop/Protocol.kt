package app.loop.whoop

import java.util.UUID
import java.util.zip.CRC32

/*
 * WHOOP strap wire protocol (independent Kotlin implementation).
 *
 * Built from the interoperability facts documented by the NOOP project (docs/PROTOCOL*.md), which in turn
 * credits johnmiddleton12/my-whoop (WHOOP 4) and b-nnett/goose (WHOOP 5/MG). Only wire facts are used:
 * UUIDs, frame layouts, CRC parameters, command numbers and record offsets.
 *
 * Loop talks only to a strap its owner pairs with it, and only reads data. The one write that changes
 * strap state is the standard history acknowledgement, which is sent strictly AFTER the chunk has been
 * saved to disk (see StrapLink).
 */

enum class Family(val label: String) { WHOOP4("WHOOP 4.0"), WHOOP5("WHOOP 5.0 / MG") }

object WhoopUuids {
    val W4_SERVICE: UUID = UUID.fromString("61080001-8d6d-82b8-614a-1c8cb0f8dcc6")
    val W4_CMD: UUID = UUID.fromString("61080002-8d6d-82b8-614a-1c8cb0f8dcc6")
    val W4_NOTIFY: List<UUID> = listOf("61080003", "61080004", "61080005").map { UUID.fromString("$it-8d6d-82b8-614a-1c8cb0f8dcc6") }

    val W5_SERVICE: UUID = UUID.fromString("fd4b0001-cce1-4033-93ce-002d5875f58a")
    val W5_CMD: UUID = UUID.fromString("fd4b0002-cce1-4033-93ce-002d5875f58a")
    val W5_NOTIFY: List<UUID> = listOf("fd4b0003", "fd4b0004", "fd4b0005", "fd4b0007").map { UUID.fromString("$it-cce1-4033-93ce-002d5875f58a") }
}

object Cmd {
    const val TOGGLE_REALTIME_HR = 3
    const val ABORT_HISTORICAL = 20
    const val SEND_HISTORICAL = 22
    const val HISTORICAL_RESULT = 23
    const val GET_BATTERY = 26
    const val GET_DATA_RANGE = 34
    const val STOP_R10_R11 = 63
    const val SET_ALARM = 66
    const val GET_ALARM = 67
    const val RUN_ALARM = 68
    const val DISABLE_ALARM = 69
    const val HAPTICS_W4 = 79
    const val HAPTICS_W5 = 19
}

object PType {
    const val COMMAND = 35
    const val COMMAND_RESPONSE = 36
    const val PUFFIN_COMMAND_RESPONSE = 38
    const val REALTIME = 40
    const val HISTORICAL = 47
    const val EVENT = 48
    const val METADATA = 49
    const val PUFFIN_METADATA = 56
}

object Crc {
    private val t8 = IntArray(256).also { t ->
        for (i in 0 until 256) { var c = i; repeat(8) { c = if (c and 0x80 != 0) (c shl 1) xor 0x07 else c shl 1 }; t[i] = c and 0xFF }
    }

    fun crc8(d: ByteArray, from: Int, to: Int): Int { var c = 0; for (i in from until to) c = t8[c xor (d[i].toInt() and 0xFF)]; return c }

    fun crc16Modbus(d: ByteArray, from: Int, to: Int): Int {
        var c = 0xFFFF
        for (i in from until to) { c = c xor (d[i].toInt() and 0xFF); repeat(8) { c = if (c and 1 == 1) (c ushr 1) xor 0xA001 else c ushr 1 } }
        return c and 0xFFFF
    }

    fun crc32(d: ByteArray, from: Int, to: Int): Long = CRC32().also { it.update(d, from, to - from) }.value
}

private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
private fun ByteArray.u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
private fun ByteArray.u32(i: Int) = (u8(i).toLong()) or (u8(i + 1).toLong() shl 8) or (u8(i + 2).toLong() shl 16) or (u8(i + 3).toLong() shl 24)
private fun ByteArray.f32(i: Int) = java.lang.Float.intBitsToFloat(u32(i).toInt())

object Frames {
    /** WHOOP 4: AA | len u16 | crc8(len) | [type seq cmd payload] | crc32(inner). */
    fun command4(cmd: Int, payload: ByteArray, seq: Int): ByteArray {
        val inner = ByteArray(3 + payload.size)
        inner[0] = PType.COMMAND.toByte(); inner[1] = seq.toByte(); inner[2] = cmd.toByte()
        payload.copyInto(inner, 3)
        val len = inner.size + 4
        val f = ByteArray(4 + inner.size + 4)
        f[0] = 0xAA.toByte(); f[1] = (len and 0xFF).toByte(); f[2] = (len ushr 8).toByte()
        f[3] = Crc.crc8(f, 1, 3).toByte()
        inner.copyInto(f, 4)
        val c = Crc.crc32(inner, 0, inner.size)
        for (k in 0..3) f[4 + inner.size + k] = (c ushr (8 * k)).toByte()
        return f
    }

    /** WHOOP 5/MG "format 1": AA 01 | len u16 | 00 01 | crc16 | [type seq cmd payload + pad4] | crc32. */
    fun command5(cmd: Int, payload: ByteArray, seq: Int): ByteArray {
        val raw = ByteArray(3 + payload.size)
        raw[0] = PType.COMMAND.toByte(); raw[1] = seq.toByte(); raw[2] = cmd.toByte()
        payload.copyInto(raw, 3)
        val inner = raw.copyOf(raw.size + (4 - raw.size % 4) % 4)
        val declared = inner.size + 4
        val f = ByteArray(8 + inner.size + 4)
        f[0] = 0xAA.toByte(); f[1] = 0x01; f[2] = (declared and 0xFF).toByte(); f[3] = (declared ushr 8).toByte(); f[4] = 0x00; f[5] = 0x01
        val h = Crc.crc16Modbus(f, 0, 6)
        f[6] = (h and 0xFF).toByte(); f[7] = (h ushr 8).toByte()
        inner.copyInto(f, 8)
        val c = Crc.crc32(inner, 0, inner.size)
        for (k in 0..3) f[8 + inner.size + k] = (c ushr (8 * k)).toByte()
        return f
    }

    fun command(family: Family, cmd: Int, payload: ByteArray, seq: Int) =
        if (family == Family.WHOOP4) command4(cmd, payload, seq) else command5(cmd, payload, seq)

    /** Fixed hello that starts a WHOOP 5/MG session. */
    val CLIENT_HELLO5: ByteArray = "AA0108000001E67123019101363E5C8D".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/** Splits the notification byte stream into complete, CRC-verified frames. Unverifiable bytes are skipped. */
class Reassembler(private val family: Family) {
    private var buf = ByteArray(0)
    var rejected = 0; private set

    fun feed(chunk: ByteArray): List<ByteArray> {
        buf += chunk
        val out = ArrayList<ByteArray>()
        var pos = 0
        loop@ while (true) {
            while (pos < buf.size && buf[pos] != 0xAA.toByte()) pos++
            val rem = buf.size - pos
            if (rem < 4) break
            val total: Int
            if (family == Family.WHOOP4) {
                val len = buf.u16(pos + 1)
                if (len < 7 || len + 4 > 8192) { pos++; rejected++; continue@loop }
                if (Crc.crc8(buf, pos + 1, pos + 3) != buf.u8(pos + 3)) { pos++; rejected++; continue@loop }
                total = len + 4
                if (rem < total) break
                if (Crc.crc32(buf, pos + 4, pos + len) != buf.u32(pos + len)) { pos++; rejected++; continue@loop }
            } else {
                if (rem < 8) break
                if (buf.u8(pos + 1) != 0x01) { pos++; rejected++; continue@loop }
                val len = buf.u16(pos + 2)
                total = len + 8
                if (total < 16 || total > 8192 || (len - 4) % 4 != 0) { pos++; rejected++; continue@loop }
                if (Crc.crc16Modbus(buf, pos, pos + 6) != buf.u16(pos + 6)) { pos++; rejected++; continue@loop }
                if (rem < total) break
                if (Crc.crc32(buf, pos + 8, pos + total - 4) != buf.u32(pos + total - 4)) { pos++; rejected++; continue@loop }
            }
            out += buf.copyOfRange(pos, pos + total)
            pos += total
        }
        buf = if (pos >= buf.size) ByteArray(0) else buf.copyOfRange(pos, buf.size)
        if (buf.size > 16384) buf = ByteArray(0)
        return out
    }
}

/** One decoded second of strap history. Gravity is in g; skin temperature only where the layout gives real degrees. */
class HistRecord(
    val ts: Long, val hr: Int, val rr: IntArray, val contact: Int, // contact: 1 on, 0 off, -1 unknown
    val gx: Float, val gy: Float, val gz: Float, val skinC: Float?, val steps: Int?,
    /** Raw, uncalibrated skin-temperature ADC (WHOOP 4.0). */
    val skinRaw: Int? = null,
)

sealed interface Msg {
    class Response(val cmd: Int, val origin: Int, val result: Int, val body: ByteArray) : Msg
    class Meta(val type: Int, val endBlock: ByteArray?) : Msg // type 1 start, 2 end, 3 complete
    class History(val rec: HistRecord?, val skipped: Boolean) : Msg
    class Realtime(val hr: Int, val rr: List<Int>, val ts: Long = 0) : Msg
    class Event(val number: Int) : Msg
    data object Other : Msg
}

object Decoder {
    const val META_START = 1
    const val META_END = 2
    const val META_COMPLETE = 3

    private fun plausible(ts: Long) = ts in 1_577_836_800L..(System.currentTimeMillis() / 1000 + 86_400)

    fun decode(f: ByteArray, family: Family): Msg {
        val w5 = family == Family.WHOOP5
        val typeOff = if (w5) 8 else 4
        val end = if (w5) f.size - 4 else f.size - 4 // CRC32 trailer is last four bytes in both families
        val type = f.u8(typeOff)
        return when (type) {
            PType.COMMAND_RESPONSE, PType.PUFFIN_COMMAND_RESPONSE -> {
                val c = typeOff + 2
                if (end < c + 3) return Msg.Other
                Msg.Response(f.u8(c), f.u8(c + 1), f.u8(c + 2), f.copyOfRange(c + 3, end))
            }
            PType.METADATA, PType.PUFFIN_METADATA -> {
                val m = f.u8(typeOff + 2)
                val blockOff = if (w5) 21 else 17
                val block = if (m == META_END && f.size >= blockOff + 8 + 4) f.copyOfRange(blockOff, blockOff + 8) else null
                Msg.Meta(m, block)
            }
            PType.REALTIME -> if (w5) realtime(f, 16, 17, 18, true) else realtime(f, 12, 13, 14, false)
            PType.EVENT -> Msg.Event(f.u8(typeOff + 2))
            PType.HISTORICAL -> if (w5) history5(f) else history4(f)
            else -> Msg.Other
        }
    }

    private fun realtime(f: ByteArray, hrOff: Int, nOff: Int, rrOff: Int, ticks: Boolean): Msg {
        if (f.size < rrOff + 4) return Msg.Other
        val rr = ArrayList<Int>()
        val n = f.u8(nOff)
        for (i in 0 until n) {
            val o = rrOff + 2 * i
            if (o + 2 > f.size - 4) break
            val v = f.u16(o)
            if (v > 0) rr += if (ticks) (v * 1000 + 512) / 1024 else v
        }
        return Msg.Realtime(f.u8(hrOff), rr, if (ticks) f.u32(10) else f.u32(6))
    }

    /** WHOOP 4, type 47 version 24 (and 12, which shares the map): 1 Hz biometrics. Other versions are skipped, not guessed. */
    private fun history4(f: ByteArray): Msg {
        val version = f.u8(5)
        if ((version != 24 && version != 12) || f.size < 88) return Msg.History(null, true)
        val ts = f.u32(11)
        if (!plausible(ts)) return Msg.History(null, true)
        val hr = f.u8(21)
        val n = f.u8(22).coerceAtMost(4)
        val rr = IntArray(n) { f.u16(23 + 2 * it) }.filter { it in 250..2500 }.toIntArray()
        val gx = f.f32(40); val gy = f.f32(44); val gz = f.f32(48)
        val grav = if (gx.isFinite() && gy.isFinite() && gz.isFinite()) Triple(gx, gy, gz) else Triple(0f, 0f, 0f)
        return Msg.History(HistRecord(ts, hr, rr, f.u8(55).let { if (it == 0) 0 else 1 }, grav.first, grav.second, grav.third, null, null, f.u16(72).takeIf { it in 1..65534 }), false)
    }

    /** WHOOP 5/MG, type 47 layout 18: 124-byte biometric summary. */
    private fun history5(f: ByteArray): Msg {
        if (f.size < 124 || f.u8(9) != 18) return Msg.History(null, true)
        val ts = f.u32(15)
        if (!plausible(ts)) return Msg.History(null, true)
        val hr = f.u8(22)
        val n = f.u8(23).coerceAtMost(4)
        val rr = IntArray(n) { (f.u16(24 + 2 * it) * 1000 + 512) / 1024 }.filter { it in 250..2500 }.toIntArray()
        val gx = f.f32(45); val gy = f.f32(49); val gz = f.f32(53)
        val ok = gx.isFinite() && gy.isFinite() && gz.isFinite()
        val skin = f.u16(73) / 100f
        return Msg.History(
            HistRecord(ts, hr, rr, -1, if (ok) gx else 0f, if (ok) gy else 0f, if (ok) gz else 0f, if (skin in 5f..45f) skin else null, f.u16(57)),
            false,
        )
    }
}
