package app.loop.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import app.loop.whoop.Cmd
import app.loop.whoop.Decoder
import app.loop.whoop.Family
import app.loop.whoop.Frames
import app.loop.whoop.HistRecord
import app.loop.whoop.Msg
import app.loop.whoop.Reassembler
import app.loop.whoop.WhoopUuids
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

/** Receives strap history. [commit] must make the records durable; only then is the strap told it may free them. */
interface HistorySink {
    fun commit(records: List<HistRecord>): Boolean
    fun onSyncFinished(newRecords: Int)
}

/**
 * One Bluetooth link to a strap, band or chest belt.
 *
 *  - Anything with the standard Heart Rate service (0x180D) streams live heart rate and R-R intervals.
 *  - A WHOOP 4.0 or WHOOP 5.0/MG additionally speaks its own protocol: live heart rate without needing "HR
 *    broadcast", battery, and a download of the strap's stored history (see [startSync]).
 *
 * The link survives the app leaving the screen (it is kept alive by [app.loop.StrapService]) and reconnects
 * by itself when the strap comes back into range.
 */
@SuppressLint("MissingPermission")
class HeartRateMonitor(private val context: Context) {

    data class Device(val name: String, val address: String, val rssi: Int, val whoop: Boolean = false)
    data class Sample(val ts: Long, val bpm: Int)
    enum class Phase { IDLE, SCANNING, CONNECTING, CONNECTED, RECONNECTING }
    enum class SyncPhase { IDLE, RUNNING, DONE, ERROR }

    data class Sync(
        val phase: SyncPhase = SyncPhase.IDLE,
        val records: Int = 0,
        val chunks: Int = 0,
        val skipped: Int = 0,
        val message: String? = null,
        val finishedAt: Long = 0,
    )

    data class State(
        /** Read-only device information (model, firmware, hardware revision) once the strap has answered. */
        val deviceInfo: String? = null,
        /** True when the strap's own model or serial prefix says MG (the premium variant). Null until it has answered. */
        val isMg: Boolean? = null,
        val phase: Phase = Phase.IDLE,
        val devices: List<Device> = emptyList(),
        val device: Device? = null,
        val family: Family? = null,
        val bpm: Int? = null,
        val rmssd: Double? = null,
        val battery: Int? = null,
        val contactLost: Boolean = false,
        val error: String? = null,
        val lastBeatAt: Long = 0,
        val showingAll: Boolean = false,
        val sync: Sync = Sync(),
        /** A WHOOP 5.0/MG that isn't paired: live heart rate works, but history and the WHOOP channel need a pairing. */
        val pairingNeeded: Boolean = false,
        val pairing: Boolean = false,
        /** Wrist state reported by the strap's own events; null until one is seen. */
        val onWrist: Boolean? = null,
        /** Strap clock minus phone clock, in seconds, from live packets. Used to aim the strap alarm. */
        val clockSkewSec: Long? = null,
        /** Latest message about the strap's wake alarm or buzzer. */
        val alarmNote: String? = null,
        /** Live breathing-rate ESTIMATE (breaths/min) from heartbeat timing; null until the wearer has been still long enough. */
        val liveBreath: Double? = null,
        /** True while heart rate has been steady for about a minute, the condition for the live estimates to be meaningful. */
        val liveSteady: Boolean = false,
        /** Clean heartbeat intervals collected so far in the live buffer. */
        val liveBeats: Int = 0,
        val liveSdnn: Double? = null,
        val livePnn50: Double? = null,
        val liveMeanRr: Double? = null,
        /** Autonomic balance estimate (LF/HF) and rhythm coherence (0..100). */
        val liveLfHf: Double? = null,
        val liveCoherence: Double? = null,
        /** Heart-rate trend over the last minute, bpm per minute. */
        val liveTrend: Double? = null,
        /** Share of received heartbeat intervals that passed the cleanliness checks, 0..1. */
        val liveQuality: Double? = null,
    )

    private sealed interface Op {
        class Notify(val ch: BluetoothGattCharacteristic) : Op
        class Write(val ch: BluetoothGattCharacteristic, val data: ByteArray, val tag: String) : Op
        class Read(val ch: BluetoothGattCharacteristic) : Op
    }

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _samples = MutableSharedFlow<Sample>(extraBufferCapacity = 128)
    val samples: SharedFlow<Sample> = _samples.asSharedFlow()

    @Volatile var sink: HistorySink? = null

    private var gatt: BluetoothGatt? = null
    private var wantConnected = false
    private var target: Device? = null
    private val found = LinkedHashMap<String, Device>()
    private var lastAdvHex = ""
    private val rr = ArrayDeque<Int>()
    private val hrHist = ArrayDeque<Int>()
    private var lastBreathAt = 0L
    private var breathEma: Double? = null
    private var lfHfEma: Double? = null
    private var cohEma: Double? = null
    private var rrSeen = 0.0
    private var rrKept = 0.0
    private var lastStdHrAt = 0L

    // protocol session
    private var family: Family? = null

    private var reasm: Reassembler? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private var seq = 0
    private val ops = ArrayDeque<Op>()
    private var busy = false
    private var opId = 0
    private var handshakeQueued = false
    private var discovering = false
    private var bondReceiver: BroadcastReceiver? = null

    // history offload
    private var chunk = ArrayList<HistRecord>()
    private var syncTotal = 0
    private var syncChunks = 0
    private var syncSkipped = 0
    private var syncActive = false

    val isSupported: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true
    val isConnected: Boolean get() = _state.value.phase == Phase.CONNECTED

    fun hasPermissions(): Boolean = requiredPermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    // ------------------------------------------------------------------ scanning
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
            val whoop = WhoopUuids.W4_SERVICE in uuids || WhoopUuids.W5_SERVICE in uuids
            if (whoop) {
                // Diagnostic: log the raw advertisement whenever it changes, so a change in pairing state is visible.
                val hex = result.scanRecord?.bytes?.joinToString("") { "%02x".format(it) } ?: ""
                if (hex != lastAdvHex) { lastAdvHex = hex; Log.i(TAG, "adv ${result.device.address} rssi=${result.rssi} connectable=${result.isConnectable} $hex") }
            }
            val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
            if (name.isNullOrBlank() && _state.value.showingAll) return
            val d = Device(name ?: "Heart-rate sensor", result.device.address, result.rssi, whoop)
            found[d.address] = d
            _state.update { it.copy(devices = found.values.sortedWith(compareByDescending<Device> { x -> x.whoop }.thenByDescending { x -> x.rssi })) }
        }

        override fun onScanFailed(errorCode: Int) { _state.update { it.copy(phase = Phase.IDLE, error = "Bluetooth scan failed (code $errorCode). Try again.") } }
    }

    fun startScan(showAll: Boolean = false) {
        val a = adapter
        if (a == null) { fail("This phone doesn't support Bluetooth Low Energy."); return }
        if (!a.isEnabled) { fail("Bluetooth is off. Turn it on and try again."); return }
        if (!hasPermissions()) { fail("Loop needs Bluetooth permission to find your device."); return }
        val scanner = a.bluetoothLeScanner ?: run { fail("Bluetooth scanner unavailable."); return }
        stopScan(keepList = false)
        found.clear()
        val filters = if (showAll) null else listOf(HR_SERVICE, WhoopUuids.W4_SERVICE, WhoopUuids.W5_SERVICE).map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }
        runCatching { scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb) }
            .onFailure { fail("Couldn't start scanning: ${it.message}"); return }
        _state.update { it.copy(phase = Phase.SCANNING, devices = emptyList(), error = null, showingAll = showAll) }
        handler.removeCallbacks(stopRunnable)
        handler.postDelayed(stopRunnable, 25_000)
    }

    private val stopRunnable = Runnable { stopScan() }

    fun stopScan(keepList: Boolean = true) {
        handler.removeCallbacks(stopRunnable)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
        _state.update { if (it.phase == Phase.SCANNING) it.copy(phase = Phase.IDLE, devices = if (keepList) it.devices else emptyList()) else it }
    }

    // -------------------------------------------------------------- connection
    fun connect(device: Device) {
        val a = adapter ?: return
        if (!hasPermissions()) { fail("Loop needs Bluetooth permission to connect."); return }
        stopScan()
        closeGatt()
        wantConnected = true; target = device; retries = 0
        resetSession()
        synchronized(rr) { rr.clear() }
        hrHist.clear(); breathEma = null; lfHfEma = null; cohEma = null; rrSeen = 0.0; rrKept = 0.0
        _state.update { it.copy(phase = Phase.CONNECTING, device = device, family = null, error = null, bpm = null, rmssd = null, battery = null, sync = Sync()) }
        gatt = runCatching { a.getRemoteDevice(device.address).connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
        if (gatt == null) fail("Couldn't connect to ${device.name}.")
    }

    fun disconnect() {
        wantConnected = false
        handler.removeCallbacksAndMessages(null)
        if (syncActive) finishSync(false, "Disconnected")
        unregisterBond()
        closeGatt()
        resetSession()
        _state.update { it.copy(phase = Phase.IDLE, device = null, family = null, bpm = null, rmssd = null, battery = null, contactLost = false, onWrist = null, alarmNote = null) }
    }

    fun release() { stopScan(); disconnect(); io.shutdown() }

    private fun closeGatt() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    private fun resetSession() {
        family = null; reasm = null; cmdChar = null; ops.clear(); busy = false; handshakeQueued = false; discovering = false
        chunk = ArrayList(); syncActive = false
    }

    private var retries = 0

    private fun scheduleReconnect() {
        val t = target ?: return
        if (!wantConnected) return
        retries++
        _state.update { it.copy(phase = Phase.RECONNECTING, bpm = null, family = null) }
        resetSession()
        handler.postDelayed({
            if (!wantConnected) return@postDelayed
            closeGatt()
            // autoConnect = true lets Android reconnect whenever the strap is back in range, even with the screen off.
            gatt = runCatching { adapter?.getRemoteDevice(t.address)?.connectGatt(context, retries > 3, gattCb, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
        }, 1500)
    }

    // --------------------------------------------------------------- gatt events
    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "connectionState status=$status new=$newState")
            handler.post {
                if (g !== gatt) { if (newState == BluetoothProfile.STATE_DISCONNECTED) runCatching { g.close() }; return@post }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    discovering = false
                    if (!g.requestMtu(247)) beginDiscovery(g)
                    handler.postDelayed({ if (g === gatt) beginDiscovery(g) }, 2500) // fallback if onMtuChanged never fires
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (syncActive) finishSync(false, "Connection lost during sync")
                    if (wantConnected) { runCatching { g.close() }; if (gatt === g) gatt = null; scheduleReconnect() }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { handler.post { if (g === gatt) beginDiscovery(g) } }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { handler.post { if (g === gatt) setup(g) } }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (_state.value.phase != Phase.CONNECTED) { retries = 0; _state.update { it.copy(phase = Phase.CONNECTED, error = null) } }
                opDone()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post { if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "write ${c.uuid} status=$status"); opDone() }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { handler.post { onNotify(c.uuid, value) } }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val v = c.value ?: return
            handler.post { onNotify(c.uuid, v) }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) { handler.post { onRead(c.uuid, value); opDone() } }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION") val v = c.value
            handler.post { if (v != null) onRead(c.uuid, v); opDone() }
        }
    }

    private fun beginDiscovery(g: BluetoothGatt) {
        if (discovering) return
        discovering = true
        if (!g.discoverServices()) discovering = false
    }

    /** Services are known: pick the protocol, subscribe, then (for WHOOP) bond and run the handshake. */
    private fun setup(g: BluetoothGatt) {
        Log.i(TAG, "services: " + g.services.joinToString { it.uuid.toString().substring(0, 8) })
        val w4 = g.getService(WhoopUuids.W4_SERVICE)
        val w5 = g.getService(WhoopUuids.W5_SERVICE)
        val hr = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
        if (w4 == null && w5 == null && hr == null) {
            wantConnected = false; fail("This device doesn't offer heart rate."); closeGatt(); return
        }
        ops.clear(); busy = false
        val fam = when { w4 != null -> Family.WHOOP4; w5 != null -> Family.WHOOP5; else -> null }
        family = fam
        val bonded = g.device.bondState == BluetoothDevice.BOND_BONDED
        // The standard heart-rate service never needs a bond, so it is always subscribed first. WHOOP's own
        // channel on a 5.0/MG requires an encrypted bond: touching it unpaired makes Android drop the link.
        hr?.let { ops.add(Op.Notify(it)) }
        val useCustom = fam == Family.WHOOP4 || (fam == Family.WHOOP5 && bonded)
        if (fam != null && useCustom) {
            reasm = Reassembler(fam)
            val svc = if (fam == Family.WHOOP4) w4!! else w5!!
            cmdChar = svc.getCharacteristic(if (fam == Family.WHOOP4) WhoopUuids.W4_CMD else WhoopUuids.W5_CMD)
            (if (fam == Family.WHOOP4) WhoopUuids.W4_NOTIFY else WhoopUuids.W5_NOTIFY).forEach { u -> svc.getCharacteristic(u)?.let { ops.add(Op.Notify(it)) } }
        }
        if (fam == Family.WHOOP5 && useCustom) {
            disInfo.clear()
            g.getService(DIS_SERVICE)?.let { s -> listOf(DIS_MODEL, DIS_FIRMWARE, DIS_HARDWARE, DIS_SERIAL).forEach { u -> s.getCharacteristic(u)?.let { c -> ops.add(Op.Read(c)) } } }
        }
        if (fam == null) g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)?.let { ops.add(Op.Read(it)) }
        _state.update { it.copy(family = fam, pairingNeeded = fam == Family.WHOOP5 && !bonded) }
        pump()
        if (fam != null && useCustom) startBondThenHandshake(g)
    }

    /**
     * Pairs with a WHOOP 5.0/MG. The strap only accepts a pairing while it is in pairing mode (tap the band until the
     * lights flash blue) and not held by the official WHOOP app. Asking the OS to pair is the only way to get the
     * encrypted link that history download needs. On success Loop reconnects and runs the normal handshake.
     */
    fun pairStrap() {
        val g = gatt ?: return
        val dev = g.device
        if (dev.bondState == BluetoothDevice.BOND_BONDED) { target?.let { connect(it) }; return }
        _state.update { it.copy(pairing = true, error = null) }
        val rec = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != dev.address) return
                val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                Log.i(TAG, "bond state $st")
                if (st == BluetoothDevice.BOND_BONDED) handler.post {
                    unregisterBond(); _state.update { it.copy(pairing = false) }; target?.let { t -> connect(t) }
                } else if (st == BluetoothDevice.BOND_NONE) handler.post {
                    unregisterBond()
                    _state.update { it.copy(pairing = false, error = "The strap refused to pair. Close the WHOOP app, tap the band until its lights flash blue, then try again.") }
                }
            }
        }
        unregisterBond()
        bondReceiver = rec
        ContextCompat.registerReceiver(context, rec, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        if (!dev.createBond()) { unregisterBond(); _state.update { it.copy(pairing = false, error = "Couldn't start pairing.") } }
        handler.postDelayed({ if (bondReceiver != null) { unregisterBond(); _state.update { it.copy(pairing = false, error = "Pairing timed out. Put the strap in pairing mode and try again.") } } }, 30_000)
    }

    // ----------------------------------------------------------------- bonding
    /** Only reached when WHOOP's own channel may be used: a WHOOP 4.0, or a WHOOP 5.0/MG that is already paired. */
    private fun startBondThenHandshake(@Suppress("UNUSED_PARAMETER") g: BluetoothGatt) { queueHandshake() }

    private fun unregisterBond() { bondReceiver?.let { runCatching { context.unregisterReceiver(it) } }; bondReceiver = null }

    private fun queueHandshake() {
        val fam = family ?: return
        if (handshakeQueued) return
        handshakeQueued = true
        if (fam == Family.WHOOP5) send(null, ByteArray(0), "hello", Frames.CLIENT_HELLO5)
        send(Cmd.GET_BATTERY, byteArrayOf(0), "battery")
        if (fam == Family.WHOOP4) send(Cmd.STOP_R10_R11, byteArrayOf(0), "stopRaw")
        send(Cmd.TOGGLE_REALTIME_HR, byteArrayOf(1), "realtimeHr")
        send(Cmd.GET_DATA_RANGE, byteArrayOf(0), "range")
    }

    // ------------------------------------------------------------ op queue
    private fun send(cmd: Int?, payload: ByteArray, tag: String, raw: ByteArray? = null) {
        val ch = cmdChar ?: return
        val f = family ?: return
        val data = raw ?: Frames.command(f, cmd!!, payload, (++seq) and 0xFF)
        ops.add(Op.Write(ch, data, tag)); pump()
    }

    private fun send(cmd: Int, payload: ByteArray) = send(cmd, payload, "cmd$cmd")

    private fun pump() {
        if (busy) return
        val g = gatt ?: return
        val op = ops.removeFirstOrNull() ?: return
        busy = true
        val id = ++opId
        val ok = runCatching {
            when (op) {
                is Op.Notify -> {
                    val props = op.ch.properties
                    g.setCharacteristicNotification(op.ch, true)
                    val d = op.ch.getDescriptor(CCCD)
                    val v = if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    if (d == null) false
                    else if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
                    else { @Suppress("DEPRECATION") d.value = v; @Suppress("DEPRECATION") g.writeDescriptor(d) }
                }
                is Op.Write -> {
                    val type = if (op.ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(op.ch, op.data, type) == BluetoothStatusCodes.SUCCESS
                    else { @Suppress("DEPRECATION") op.ch.writeType = type; @Suppress("DEPRECATION") op.ch.value = op.data; @Suppress("DEPRECATION") g.writeCharacteristic(op.ch) }
                }
                is Op.Read -> g.readCharacteristic(op.ch)
            }
        }.getOrDefault(false)
        if (!ok) { busy = false; handler.post { pump() }; return }
        handler.postDelayed({ if (busy && opId == id) { busy = false; pump() } }, 4000)
    }

    private fun opDone() { busy = false; pump() }

    // --------------------------------------------------------------- incoming
    private val disInfo = LinkedHashMap<String, String>()

    private fun onRead(uuid: UUID, v: ByteArray) {
        val label = when (uuid) { DIS_MODEL -> "model"; DIS_FIRMWARE -> "fw"; DIS_HARDWARE -> "hw"; DIS_SERIAL -> "serial"; else -> null }
        if (label != null) {
            val text = String(v, Charsets.UTF_8).trim { it <= ' ' }
            // The serial identifies one strap, so only its three-letter product prefix is kept or logged.
            disInfo[label] = if (label == "serial") text.take(3) else text
            Log.i(TAG, "dis $label=${disInfo[label]}")
            val mg = disInfo["model"]?.contains("MG", ignoreCase = true) == true || disInfo["serial"].equals("5AM", ignoreCase = true)
            val answered = disInfo.containsKey("model") || disInfo.containsKey("serial")
            _state.update { it.copy(isMg = if (answered) mg else null, deviceInfo = disInfo.entries.joinToString(" · ") { e -> (if (e.key == "serial") "serial prefix " else e.key + " ") + e.value }) }
            return
        }
        if (uuid == BATTERY_LEVEL && v.isNotEmpty() && family == null) _state.update { it.copy(battery = v[0].toInt() and 0xFF) }
    }

    private fun onNotify(uuid: UUID, v: ByteArray) {
        if (uuid == HR_MEASUREMENT) { lastStdHrAt = System.currentTimeMillis(); handleHr(v); return }
        val fam = family ?: return
        val frames = reasm?.feed(v) ?: return
        for (f in frames) { val m = Decoder.decode(f, fam); if (m !is Msg.History && m !is Msg.Realtime) Log.i(TAG, "rx ${m.javaClass.simpleName} " + (if (m is Msg.Response) "cmd=${m.cmd} res=${m.result} body=${m.body.size}" else if (m is Msg.Meta) "type=${m.type}" else "")); handleMsg(m) }
    }

    private fun handleMsg(m: Msg) {
        when (m) {
            is Msg.Response -> when (m.cmd) {
                Cmd.GET_BATTERY -> if (m.result == 1) {
                    val b = m.body
                    val pct = if (family == Family.WHOOP4 && b.size >= 2) (((b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)) / 10.0)
                    else if (b.size >= 4) ((b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)).toDouble() else null
                    if (pct != null && pct in 0.0..100.0) _state.update { it.copy(battery = pct.toInt()) }
                }
                Cmd.SEND_HISTORICAL -> if ((m.result == 0 || m.result == 3) && syncActive) finishSync(false, "The strap declined to send history (code ${m.result}).")
                Cmd.SET_ALARM -> _state.update { it.copy(alarmNote = if (m.result == 1) "The strap accepted the alarm." else "The strap refused the alarm (code ${m.result}). Check that its clock is right.") }
                Cmd.DISABLE_ALARM -> _state.update { it.copy(alarmNote = if (m.result == 1) "The strap alarm was cleared." else "Couldn't clear the strap alarm (code ${m.result}).") }
                Cmd.HAPTICS_W4, Cmd.HAPTICS_W5 -> if (m.result == 0 || m.result == 3) _state.update { it.copy(alarmNote = "The strap didn't accept the buzz command (code ${m.result}).") }
            }
            is Msg.Meta -> onMeta(m)
            is Msg.History -> if (syncActive) {
                armWatchdog()
                if (m.rec != null) chunk.add(m.rec) else syncSkipped++
                if (chunk.size % 100 == 0) publishSync()
            }
            is Msg.Realtime -> handleRealtime(m)
            is Msg.Event -> when (m.number) {
                9 -> _state.update { it.copy(onWrist = true) }
                10 -> _state.update { it.copy(onWrist = false) }
                56 -> _state.update { it.copy(alarmNote = "Alarm armed on the strap.") }
                57 -> _state.update { it.copy(alarmNote = "The strap's wake alarm just fired.") }
                58 -> _state.update { it.copy(alarmNote = "Test buzz ran.") }
                60 -> _state.update { it.copy(alarmNote = "The strap buzzed.") }
                else -> {}
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------ history offload
    /** Starts downloading the strap's stored history. Safe to call repeatedly; ignored while one is running. */
    fun startSync(): Boolean {
        val fam = family
        if (fam == null || _state.value.phase != Phase.CONNECTED || cmdChar == null) {
            val msg = if (_state.value.pairingNeeded) "Pair the strap first (see below)." else "Connect to a WHOOP strap first."
            _state.update { it.copy(sync = Sync(SyncPhase.ERROR, message = msg)) }; return false
        }
        if (syncActive) return false
        chunk = ArrayList(); syncTotal = 0; syncChunks = 0; syncSkipped = 0; syncActive = true
        _state.update { it.copy(sync = Sync(SyncPhase.RUNNING, message = "Asking the strap for its history…")) }
        // A download cut off earlier (app killed, link dropped) can leave the strap holding an unfinished session that
        // ignores new requests. Closing any such session first lets the request start cleanly.
        send(Cmd.ABORT_HISTORICAL, byteArrayOf(0))
        handler.postDelayed({ if (syncActive) send(Cmd.SEND_HISTORICAL, byteArrayOf(0)) }, 1200)
        armWatchdog()
        return true
    }

    fun cancelSync() { if (syncActive) { send(Cmd.ABORT_HISTORICAL, byteArrayOf(0)); finishSync(false, "Stopped") } }

    /** Times in a row a download got no answer at all and the link was rebuilt. Reset by any download that gets data. */
    private var silentSyncs = 0

    /**
     * Failsafe for an interrupted or ignored download. Data already saved is kept (each chunk is committed before it is
     * acknowledged, so an unacknowledged chunk is simply sent again next time). If the strap says nothing at all, the
     * usual cause is a stale session on its side, and rebuilding the Bluetooth link clears it, so Loop reconnects and the
     * normal post-connect sync tries again. This is limited to two rebuilds in a row, then it waits for the 15-minute cycle.
     */
    private val watchdog = Runnable {
        if (!syncActive) return@Runnable
        send(Cmd.ABORT_HISTORICAL, byteArrayOf(0))
        if (syncTotal > 0) { silentSyncs = 0; finishSync(true, null); return@Runnable }
        if (silentSyncs < MAX_RELINKS && wantConnected && target != null) {
            silentSyncs++
            finishSync(false, "The strap didn't respond. Reconnecting to try again…")
            scheduleReconnect()
        } else finishSync(false, "The strap stopped responding. Loop will try again in a few minutes.")
    }

    private fun armWatchdog() { handler.removeCallbacks(watchdog); handler.postDelayed(watchdog, 40_000) }

    private fun publishSync() {
        _state.update { it.copy(sync = Sync(SyncPhase.RUNNING, syncTotal + chunk.size, syncChunks, syncSkipped, "Downloading history…")) }
    }

    private fun onMeta(m: Msg.Meta) {
        if (!syncActive) return
        armWatchdog()
        when (m.type) {
            Decoder.META_START -> publishSync()
            Decoder.META_END -> {
                val block = m.endBlock
                val data = chunk; chunk = ArrayList()
                if (block == null) { finishSync(false, "Unreadable history marker. Stopped without acknowledging."); return }
                val s = sink
                io.execute {
                    // Durability first: the strap may free this chunk once we acknowledge it.
                    val ok = s != null && s.commit(data)
                    handler.post {
                        if (!syncActive) return@post
                        if (!ok) { send(Cmd.ABORT_HISTORICAL, byteArrayOf(0)); finishSync(false, "Couldn't save history on this phone. Nothing was acknowledged."); return@post }
                        syncTotal += data.size; syncChunks++
                        publishSync()
                        send(Cmd.HISTORICAL_RESULT, byteArrayOf(1) + block)
                        armWatchdog()
                    }
                }
            }
            Decoder.META_COMPLETE -> { silentSyncs = 0; finishSync(true, null) }
        }
    }

    private fun finishSync(success: Boolean, message: String?) {
        if (!syncActive) return
        syncActive = false
        handler.removeCallbacks(watchdog)
        val total = syncTotal
        _state.update {
            it.copy(sync = Sync(if (success) SyncPhase.DONE else SyncPhase.ERROR, total, syncChunks, syncSkipped, message, System.currentTimeMillis()))
        }
        if (total > 0) sink?.onSyncFinished(total)
    }

    // ------------------------------------------------------------ wake alarm and buzzer
    /**
     * Arms the strap's own wake alarm for [epochMs] (a phone-clock instant). The strap fires it itself, even with the
     * phone away. The time is shifted by the strap's measured clock skew, since the strap compares against its own clock.
     * EXPERIMENTAL: an accepted command is not proof the strap will buzz.
     */
    fun setAlarm(epochMs: Long): Boolean {
        val fam = family ?: return false
        if (_state.value.phase != Phase.CONNECTED || cmdChar == null) return false
        val secs = epochMs / 1000 + (_state.value.clockSkewSec ?: 0L)
        val body: ByteArray
        if (fam == Family.WHOOP4) {
            body = byteArrayOf(1, secs.toByte(), (secs shr 8).toByte(), (secs shr 16).toByte(), (secs shr 24).toByte(), 0, 0, 0, 0)
        } else {
            body = ByteArray(20)
            body[0] = 4; body[1] = 1
            for (k in 0..3) body[2 + k] = (secs shr (8 * k)).toByte()
            byteArrayOf(47, 152.toByte(), 0, 0, 0, 0, 0, 0).copyInto(body, 8)
            body[18] = 7; body[19] = 30
        }
        send(Cmd.SET_ALARM, body)
        _state.update { it.copy(alarmNote = "Sending alarm to the strap…") }
        return true
    }

    fun disableAlarm(): Boolean {
        val fam = family ?: return false
        if (_state.value.phase != Phase.CONNECTED || cmdChar == null) return false
        send(Cmd.DISABLE_ALARM, if (fam == Family.WHOOP4) byteArrayOf(1) else byteArrayOf(2, 0xFF.toByte()))
        return true
    }

    /** A short test buzz so the wearer can feel the haptic. */
    fun buzzNow(): Boolean {
        val fam = family ?: return false
        if (_state.value.phase != Phase.CONNECTED || cmdChar == null) return false
        if (fam == Family.WHOOP4) {
            send(Cmd.HAPTICS_W4, byteArrayOf(2, 3, 0, 0, 0))
            send(Cmd.RUN_ALARM, byteArrayOf(1))
        } else {
            send(Cmd.HAPTICS_W5, byteArrayOf(1, 47, 152.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 2))
        }
        _state.update { it.copy(alarmNote = "Buzz sent. You should feel it on your wrist.") }
        return true
    }

    // ------------------------------------------------------------ live heart rate
    private fun handleRealtime(m: Msg.Realtime) {
        if (m.ts > 0) {
            val skew = m.ts - System.currentTimeMillis() / 1000
            if (abs(skew) < 30L * 86400) _state.update { it.copy(clockSkewSec = skew) }
        }
        // The standard Heart Rate service is preferred when it is also streaming.
        if (System.currentTimeMillis() - lastStdHrAt < 3000) return
        if (m.hr <= 0) return
        pushHr(m.hr, m.rr, false)
    }

    private fun handleHr(data: ByteArray) {
        val r = parse(data) ?: return
        if (r.bpm <= 0) return
        pushHr(r.bpm, r.rrMs, r.contactLost)
    }

    private fun pushHr(bpm: Int, rrMs: List<Int>, contactLost: Boolean) {
        val now = System.currentTimeMillis()
        var rmssd: Double? = null
        synchronized(rr) {
            for (ms in rrMs) {
                rrSeen = rrSeen * 0.995 + 1
                if (ms !in 300..2000) { rrKept *= 0.995; continue }
                val prev = rr.lastOrNull()
                if (prev != null && abs(ms - prev).toDouble() / prev > 0.20) { rrKept *= 0.995; continue }
                rrKept = rrKept * 0.995 + 1
                rr.addLast(ms)
                while (rr.size > 300) rr.removeFirst()
            }
            if (rr.size >= 20) {
                var sum = 0.0
                val l = rr.toList().takeLast(90)
                for (i in 1 until l.size) { val d = (l[i] - l[i - 1]).toDouble(); sum += d * d }
                rmssd = sqrt(sum / (l.size - 1))
            }
        }
        // Live breathing: needs a steady heart rate (a proxy for sitting still) and a few minutes of clean beats.
        hrHist.addLast(bpm); while (hrHist.size > 60) hrHist.removeFirst()
        val steady = hrHist.size >= 45 && (hrHist.max() - hrHist.min()) <= 14
        if (!steady) { breathEma = null; lfHfEma = null; cohEma = null }
        if (now - lastBreathAt >= 5000) {
            lastBreathAt = now
            if (steady) {
                val snap = synchronized(rr) { rr.toList() }
                app.loop.analytics.LiveHrv.spectral(snap)?.let { sp ->
                    if (sp.breathClarity >= 0.2 && sp.breathRate in 8.0..30.0) breathEma = breathEma?.let { 0.7 * it + 0.3 * sp.breathRate } ?: sp.breathRate
                    sp.lfHf?.let { v -> lfHfEma = lfHfEma?.let { 0.7 * it + 0.3 * v } ?: v }
                    cohEma = cohEma?.let { 0.7 * it + 0.3 * sp.coherence } ?: sp.coherence
                }
            }
        }
        val td = app.loop.analytics.LiveHrv.timeDomain(synchronized(rr) { rr.toList().takeLast(90) })
        val beats = synchronized(rr) { rr.size }
        val quality = if (rrSeen > 5) (rrKept / rrSeen).coerceIn(0.0, 1.0) else null
        val trend = app.loop.analytics.LiveHrv.trend(hrHist.toList())
        _state.update {
            it.copy(
                bpm = bpm, rmssd = td?.rmssd ?: rmssd ?: it.rmssd, contactLost = contactLost, lastBeatAt = now,
                liveBreath = breathEma, liveSteady = steady, liveBeats = beats,
                liveSdnn = td?.sdnn ?: it.liveSdnn, livePnn50 = td?.pnn50 ?: it.livePnn50, liveMeanRr = td?.meanRr ?: it.liveMeanRr,
                liveLfHf = lfHfEma, liveCoherence = cohEma, liveTrend = trend, liveQuality = quality,
            )
        }
        _samples.tryEmit(Sample(now / 1000, bpm))
    }

    private fun fail(msg: String) { _state.update { it.copy(phase = Phase.IDLE, error = msg) } }
    fun clearError() = _state.update { it.copy(error = null) }

    // ------------------------------------------------------------------ parsing
    data class Reading(val bpm: Int, val rrMs: List<Int>, val contactLost: Boolean)

    companion object {
        private const val TAG = "LoopBle"
        private const val MAX_RELINKS = 2
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val DIS_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val DIS_MODEL: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
        val DIS_SERIAL: UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
        val DIS_FIRMWARE: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        val DIS_HARDWARE: UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
        val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        /** Bluetooth SIG Heart Rate Measurement (0x2A37). R-R values are 1/1024 s units. */
        fun parse(data: ByteArray): Reading? {
            if (data.isEmpty()) return null
            val flags = data[0].toInt() and 0xFF
            var i = 1
            val bpm: Int
            if (flags and 0x01 != 0) {
                if (i + 1 >= data.size) return null
                bpm = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8); i += 2
            } else {
                if (i >= data.size) return null
                bpm = data[i].toInt() and 0xFF; i += 1
            }
            if (flags and 0x08 != 0) i += 2
            val rr = ArrayList<Int>()
            if (flags and 0x10 != 0) {
                while (i + 1 < data.size) {
                    val raw = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
                    rr += Math.round(raw / 1024.0 * 1000.0).toInt(); i += 2
                }
            }
            return Reading(bpm, rr, ((flags shr 1) and 0x03) == 2)
        }
    }
}
