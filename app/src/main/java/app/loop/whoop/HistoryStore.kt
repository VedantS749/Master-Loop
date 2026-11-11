package app.loop.whoop

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream

/**
 * Append-only binary log of every strap record Loop has downloaded. Records are fsync'd before the strap is
 * told it may free them, so a crash can never lose data the strap already discarded.
 *
 * Record: ts u32 | hr u8 | contact i8 | rrN u8 | rr u16*N | gx gy gz i16 (1/2000 g) | skin i16 (0.01 C, 0x7FFF none) | steps u16 (0xFFFF none) | skinRaw u16 (0xFFFF none)
 */
class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "strap_history_v2.bin")

    init { File(context.filesDir, "strap_history.bin").delete() } // older layout without skin-temperature sensor values
    private val lock = Any()

    /** Durably appends [records]. Returns false if anything went wrong, in which case the caller must NOT acknowledge. */
    fun append(records: List<HistRecord>): Boolean = synchronized(lock) {
        val before = if (file.exists()) file.length() else 0L
        runCatching {
            FileOutputStream(file, true).use { fos ->
                val out = DataOutputStream(BufferedOutputStream(fos, 64 * 1024))
                for (r in records) write(out, r)
                out.flush()
                fos.fd.sync()
            }
            true
        }.getOrElse {
            // Roll back a partial write so the next append stays record-aligned.
            runCatching { java.io.RandomAccessFile(file, "rw").use { it.setLength(before) } }
            false
        }
    }

    fun readSince(sinceTs: Long): List<HistRecord> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        val byTs = java.util.TreeMap<Long, HistRecord>()
        runCatching {
            DataInputStream(BufferedInputStream(file.inputStream(), 64 * 1024)).use { inp ->
                while (true) {
                    val r = read(inp) ?: break
                    if (r.ts >= sinceTs) byTs[r.ts] = r
                }
            }
        }
        byTs.values.toList()
    }

    fun sizeBytes(): Long = if (file.exists()) file.length() else 0L

    /** Drops records older than [beforeTs] so the file stays bounded. */
    fun prune(beforeTs: Long) = synchronized(lock) {
        if (!file.exists() || file.length() < 8L * 1024 * 1024) return@synchronized
        val keep = readSinceLocked(beforeTs)
        val tmp = File(file.parentFile, "strap_history.tmp")
        runCatching {
            FileOutputStream(tmp).use { fos ->
                val out = DataOutputStream(BufferedOutputStream(fos, 64 * 1024))
                keep.forEach { write(out, it) }
                out.flush(); fos.fd.sync()
            }
            tmp.renameTo(file)
        }
    }

    private fun readSinceLocked(sinceTs: Long): List<HistRecord> {
        val out = ArrayList<HistRecord>()
        runCatching {
            DataInputStream(BufferedInputStream(file.inputStream(), 64 * 1024)).use { inp ->
                while (true) { val r = read(inp) ?: break; if (r.ts >= sinceTs) out += r }
            }
        }
        return out
    }

    fun clear() = synchronized(lock) { file.delete(); Unit }

    private fun write(o: DataOutputStream, r: HistRecord) {
        o.writeInt(r.ts.toInt()); o.writeByte(r.hr); o.writeByte(r.contact)
        o.writeByte(r.rr.size); r.rr.forEach { o.writeShort(it) }
        o.writeShort((r.gx * 2000).toInt().coerceIn(-32000, 32000)); o.writeShort((r.gy * 2000).toInt().coerceIn(-32000, 32000)); o.writeShort((r.gz * 2000).toInt().coerceIn(-32000, 32000))
        o.writeShort(r.skinC?.let { (it * 100).toInt() } ?: 0x7FFF)
        o.writeShort(r.steps ?: 0xFFFF)
        o.writeShort(r.skinRaw ?: 0xFFFF)
    }

    private fun read(i: DataInputStream): HistRecord? = try {
        val ts = i.readInt().toLong() and 0xFFFFFFFFL
        val hr = i.readUnsignedByte(); val contact = i.readByte().toInt()
        val n = i.readUnsignedByte()
        val rr = IntArray(n) { i.readUnsignedShort() }
        val gx = i.readShort() / 2000f; val gy = i.readShort() / 2000f; val gz = i.readShort() / 2000f
        val skin = i.readShort().toInt(); val steps = i.readUnsignedShort(); val skinRaw = i.readUnsignedShort()
        HistRecord(ts, hr, rr, contact, gx, gy, gz, if (skin == 0x7FFF) null else skin / 100f, if (steps == 0xFFFF) null else steps, if (skinRaw == 0xFFFF) null else skinRaw)
    } catch (_: java.io.EOFException) { null }
}
