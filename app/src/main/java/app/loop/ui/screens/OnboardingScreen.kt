package app.loop.ui.screens

import app.loop.ui.theme.isDark
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.loop.model.Category
import app.loop.model.DataMode
import app.loop.ui.AppViewModel
import app.loop.ui.Route
import app.loop.ui.brand.LoopMark
import app.loop.ui.components.IconBadge
import app.loop.ui.components.LoopCard
import app.loop.ui.components.PrimaryButton
import app.loop.ui.components.icon
import app.loop.ui.theme.color
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(vm: AppViewModel) {
    val pages = 4
    val pager = rememberPagerState(pageCount = { pages })
    val scope = rememberCoroutineScope()
    val c = MaterialTheme.colorScheme

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.surfaceContainerHigh.copy(alpha = if (c.isDark) 0.55f else 0.9f), c.background))),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (pager.currentPage < pages - 1) {
                    TextButton({ scope.launch { pager.animateScrollToPage(pages - 1) } }) { Text("Skip", color = c.onSurfaceVariant) }
                }
            }

            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> AreasPage()
                    2 -> PrivacyPage()
                    else -> StartPage(vm)
                }
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.Center) {
                repeat(pages) { i ->
                    val on = pager.currentPage == i
                    val col by animateColorAsState(if (on) c.primary else c.outlineVariant, label = "dot")
                    Box(Modifier.padding(horizontal = 4.dp).height(8.dp).width(if (on) 24.dp else 8.dp).clip(CircleShape).background(col))
                }
            }
            if (pager.currentPage < pages - 1) {
                PrimaryButton("Continue", { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } })
            } else Spacer(Modifier.height(52.dp))
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PageShell(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { content() }
}

@Composable
private fun WelcomePage() = PageShell {
    LoopMark(size = 210.dp, animate = true)
    Spacer(Modifier.height(20.dp))
    Text("Loop", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(10.dp))
    Text(
        "Understand your body's rhythm of recovery, sleep and effort, privately, on your phone.",
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
    )
}

@Composable
private fun AreasPage() = PageShell {
    Text("Everything has a place", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
    Spacer(Modifier.height(6.dp))
    Text("Loop sorts every number by what it tells you, so you always know where to look.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    Spacer(Modifier.height(22.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        app.loop.model.Families.categories.forEach { cat ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(cat.icon(), cat.color(), 42.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(cat.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(cat.tagline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PrivacyPage() = PageShell {
    IconBadge(Icons.Rounded.Lock, MaterialTheme.colorScheme.secondary, 88.dp)
    Spacer(Modifier.height(24.dp))
    Text("Your data stays yours", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Bullet("No account and no cloud. Everything is calculated and stored on this phone.")
        Bullet("No ads, no tracking, no analytics.")
        Bullet("Export all of it as a spreadsheet, or wipe it, whenever you like.")
    }
    Spacer(Modifier.height(16.dp))
    Text("Loop gives wellness estimates, not medical advice.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
}

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 8.dp).size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StartPage(vm: AppViewModel) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text("How would you like to start?", style = MaterialTheme.typography.headlineMedium, color = c.onSurface)
        Spacer(Modifier.height(6.dp))
        Text("You can change this at any time in the You tab.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        StartOption(Icons.Rounded.PlayCircle, "Explore with sample data", "See every screen filled with 90 days of example data. The quickest way to learn Loop.", c.primary, recommended = true) {
            vm.finishOnboarding(DataMode.DEMO)
        }
        Spacer(Modifier.height(12.dp))
        StartOption(Icons.Rounded.Bluetooth, "Connect a heart-rate strap", "Pair a WHOOP, Polar, Garmin or any Bluetooth heart-rate device for live data and workouts.", c.secondary) {
            vm.finishOnboarding(DataMode.REAL); vm.push(Route.Devices)
        }
        Spacer(Modifier.height(12.dp))
        StartOption(Icons.Rounded.FileDownload, "Import my WHOOP history", "Bring years of recovery, sleep and workouts in from your WHOOP data export.", c.tertiary) {
            vm.finishOnboarding(DataMode.REAL); vm.push(Route.ImportData)
        }
    }
}

@Composable
private fun StartOption(icon: ImageVector, title: String, body: String, color: Color, recommended: Boolean = false, onClick: () -> Unit) {
    LoopCard(onClick = onClick, accent = if (recommended) color else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, color, 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f, fill = false))
                    if (recommended) {
                        Spacer(Modifier.width(8.dp))
                        Text("START HERE", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp), color = color)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
