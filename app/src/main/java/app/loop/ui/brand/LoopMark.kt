package app.loop.ui.brand

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Loop mark: a real, parametric Mobius strip (one edge, one face, a single half-twist), shaded with a
 * painter's algorithm. It is the same geometry that tools/gen_logo.py bakes into the launcher icon.
 *
 *   x = (R + s cos(u/2)) cos u,  y = (R + s cos(u/2)) sin u,  z = s sin(u/2)
 */
private object Mobius {
    const val NU = 64
    const val NS = 4
    const val R = 1.0
    const val W = 0.58
    val TILT = Math.toRadians(44.0)
    const val BASE_SPIN_DEG = -52.0
    private val LIGHT = doubleArrayOf(-0.45, 0.55, 0.70)
    private val LIGHT_LEN = sqrt(LIGHT.sumOf { it * it })

    private val stops = listOf(
        0.00 to intArrayOf(0x35, 0xE0, 0xC2), 0.25 to intArrayOf(0x6C, 0x7C, 0xFF), 0.50 to intArrayOf(0xB0, 0x7C, 0xFF),
        0.75 to intArrayOf(0x6C, 0x7C, 0xFF), 1.00 to intArrayOf(0x35, 0xE0, 0xC2),
    )

    private fun grad(t: Double): IntArray {
        val x = ((t % 1.0) + 1.0) % 1.0
        for (i in 0 until stops.size - 1) {
            val (t0, c0) = stops[i]; val (t1, c1) = stops[i + 1]
            if (x in t0..t1) { val k = (x - t0) / (t1 - t0); return IntArray(3) { (c0[it] + (c1[it] - c0[it]) * k).toInt() } }
        }
        return stops.last().second
    }

    private fun surf(u: Double, s: Double): DoubleArray {
        val a = R + s * cos(u / 2)
        return doubleArrayOf(a * cos(u), a * sin(u), s * sin(u / 2))
    }

    /** Pre-rotation grid points, quad centre normals and base colours. Built once. */
    val grid: Array<Array<DoubleArray>> = Array(NU + 1) { i -> Array(NS + 1) { j -> surf(2 * PI * i / NU, -W + 2 * W * j / NS) } }
    val quadNormal: Array<DoubleArray>
    val quadBase: Array<IntArray>
    val quadEdge: DoubleArray

    init {
        val n = NU * NS
        quadNormal = Array(n) { DoubleArray(3) }; quadBase = Array(n) { IntArray(3) }; quadEdge = DoubleArray(n)
        val e = 1e-4
        for (i in 0 until NU) for (j in 0 until NS) {
            val um = 2 * PI * (i + 0.5) / NU; val sm = -W + 2 * W * (j + 0.5) / NS
            val a = surf(um + e, sm); val b = surf(um - e, sm); val c = surf(um, sm + e); val d = surf(um, sm - e)
            val pu = DoubleArray(3) { (a[it] - b[it]) / (2 * e) }; val ps = DoubleArray(3) { (c[it] - d[it]) / (2 * e) }
            val nx = pu[1] * ps[2] - pu[2] * ps[1]; val ny = pu[2] * ps[0] - pu[0] * ps[2]; val nz = pu[0] * ps[1] - pu[1] * ps[0]
            val l = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 0 } ?: 1.0
            val q = i * NS + j
            quadNormal[q][0] = nx / l; quadNormal[q][1] = ny / l; quadNormal[q][2] = nz / l
            quadBase[q] = grad(um / (2 * PI)); quadEdge[q] = abs(sm) / W
        }
    }

    // Scratch buffers reused every frame (the mark is drawn on the UI thread only).
    private val px = DoubleArray((NU + 1) * (NS + 1)); private val py = DoubleArray((NU + 1) * (NS + 1)); private val pz = DoubleArray((NU + 1) * (NS + 1))
    private val order = arrayOfNulls<Int>(NU * NS)
    private val depth = DoubleArray(NU * NS)
    private val shade = Array(NU * NS) { IntArray(3) }

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, spinDeg: Double, scale: Float, path: Path) {
        val sp = Math.toRadians(spinDeg); val cs = cos(sp); val sn = sin(sp); val ct = cos(TILT); val st = sin(TILT)
        for (i in 0..NU) for (j in 0..NS) {
            val p = grid[i][j]; val k = i * (NS + 1) + j
            val x = p[0] * cs - p[1] * sn; val y0 = p[0] * sn + p[1] * cs
            px[k] = x; py[k] = y0 * ct - p[2] * st; pz[k] = y0 * st + p[2] * ct
        }
        for (i in 0 until NU) for (j in 0 until NS) {
            val q = i * NS + j
            val k00 = i * (NS + 1) + j; val k10 = (i + 1) * (NS + 1) + j; val k11 = k10 + 1; val k01 = k00 + 1
            depth[q] = (pz[k00] + pz[k10] + pz[k11] + pz[k01]) / 4
            val n = quadNormal[q]
            val nx = n[0] * cs - n[1] * sn; val ny0 = n[0] * sn + n[1] * cs
            val ny = ny0 * ct - n[2] * st; val nz = ny0 * st + n[2] * ct
            val lam = abs(nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2]) / LIGHT_LEN
            var sh = 0.62 + 0.62 * lam
            sh *= 1.0 - 0.10 * (1 - quadEdge[q])
            val b = quadBase[q]
            for (c in 0..2) shade[q][c] = (b[c] * sh + (255 - b[c]) * maxOf(0.0, sh - 1) * 0.6).toInt().coerceIn(0, 255)
            order[q] = q
        }
        val sorted = order.filterNotNull().sortedBy { depth[it] }
        val cx = scope.size.width / 2f; val cy = scope.size.height / 2f
        for (q in sorted) {
            val i = q / NS; val j = q % NS
            val k00 = i * (NS + 1) + j; val k10 = (i + 1) * (NS + 1) + j; val k11 = k10 + 1; val k01 = k00 + 1
            path.reset()
            path.moveTo(cx + (px[k00] * scale).toFloat(), cy - (py[k00] * scale).toFloat())
            path.lineTo(cx + (px[k10] * scale).toFloat(), cy - (py[k10] * scale).toFloat())
            path.lineTo(cx + (px[k11] * scale).toFloat(), cy - (py[k11] * scale).toFloat())
            path.lineTo(cx + (px[k01] * scale).toFloat(), cy - (py[k01] * scale).toFloat())
            path.close()
            val col = Color(shade[q][0], shade[q][1], shade[q][2])
            scope.drawPath(path, col)
            scope.drawPath(path, col, style = Stroke(width = 0.8f))
        }
    }
}

/** The Loop logo. Static by default; set [animate] to let the strip slowly turn. */
@Composable
fun LoopMark(modifier: Modifier = Modifier, size: Dp = 96.dp, animate: Boolean = false, pulse: Float = 1f) {
    val path = remember { Path() }
    val spin = if (animate) {
        val t = rememberInfiniteTransition(label = "loop-spin")
        val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(22000, easing = LinearEasing), RepeatMode.Restart), label = "spin")
        a.toDouble() + Mobius.BASE_SPIN_DEG
    } else Mobius.BASE_SPIN_DEG
    Canvas(modifier.size(size)) {
        val s = min(this.size.width, this.size.height) / (2f * (Mobius.R + Mobius.W).toFloat()) * 0.98f * pulse
        Mobius.draw(this, spin, s, path)
    }
}

/** Mark + "Loop" wordmark. */
@Composable
fun LoopWordmark(modifier: Modifier = Modifier, markSize: Dp = 36.dp, textSize: Int = 26) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        LoopMark(size = markSize)
        Spacer(Modifier.width(10.dp))
        Text("Loop", style = MaterialTheme.typography.headlineMedium.copy(fontSize = textSize.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp), color = MaterialTheme.colorScheme.onSurface)
    }
}
