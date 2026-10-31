package de.ayutv.ui.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.ayutv.data.parse.StreamingApps
import de.ayutv.ui.LocalContainer
import de.ayutv.ui.components.Surface
import de.ayutv.ui.theme.AyuColors
import de.ayutv.ui.theme.AyuFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Start und Wechsel zu den offiziellen Apps. Gestartet wird die App in einer eigenen Aufgabe: Schließt man sie mit
 * Zurück, kehrt Android zu DnA-TV zurück (an dieselbe Stelle). Die Home-Taste führt wie immer zum Startbildschirm.
 */
object AppLauncher {
    /** Fire TV / Android TV (Fernseh-Fassungen bevorzugen) oder Handy. */
    fun isTv(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        context.packageManager.hasSystemFeature("amazon.hardware.fire_tv")

    private fun candidates(context: Context, app: StreamingApps.App) =
        if (isTv(context)) app.tvPackages + app.phonePackages else app.phonePackages + app.tvPackages

    private fun launchIntent(context: Context, pkg: String): Intent? {
        val pm = context.packageManager
        return runCatching { pm.getLeanbackLaunchIntentForPackage(pkg) }.getOrNull()
            ?: runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
    }

    /** Installierte Fassung, null = nicht installiert. */
    fun installed(context: Context, app: StreamingApps.App): String? = candidates(context, app).firstOrNull { launchIntent(context, it) != null }

    fun icon(context: Context, pkg: String): ImageBitmap? = runCatching {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(pkg, 0)
        val drawable = runCatching { pm.getApplicationBanner(info) }.getOrNull() ?: pm.getApplicationIcon(info)
        val w = drawable.intrinsicWidth.coerceIn(1, 640)
        val h = drawable.intrinsicHeight.coerceIn(1, 360)
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        bitmap.asImageBitmap()
    }.getOrNull()

    /** App starten; true = gestartet. */
    fun launch(context: Context, app: StreamingApps.App): Boolean {
        val pkg = installed(context, app) ?: return false
        val intent = launchIntent(context, pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** Nicht installiert: zum Appstore (Fire TV: Amazon Appstore, Handy: Play Store), sonst zur Webseite. */
    fun openStore(context: Context, app: StreamingApps.App) {
        val tv = isTv(context)
        val pkg = (if (tv) app.tvPackages else app.phonePackages).first()
        val tries = buildList {
            if (tv) add(Uri.parse("amzn://apps/android?p=$pkg"))
            add(Uri.parse("market://details?id=$pkg"))
            if (!tv) add(Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
            add(Uri.parse(app.web))
        }
        for (uri in tries) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }
}

/**
 * Reiter „Apps“: Netflix, YouTube, Prime Video, Spotify. OK startet die App (oder bietet die Installation an), OK
 * halten: nach vorn, nach hinten, ausblenden.
 */
@Composable
fun AppsScreen() {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val order = settings?.appsOrder.orEmpty().split(',').filter { it.isNotBlank() }
    val hidden = settings?.appsHidden.orEmpty().split(',').filter { it.isNotBlank() }.toSet()
    val apps = remember(order, hidden) { StreamingApps.arrange(order, hidden) }
    // Beim Zurückkommen (z. B. nach einer Installation) neu prüfen, was installiert ist.
    var refresh by remember { mutableStateOf(0) }
    val installed = remember(apps, refresh) { apps.associate { it.id to AppLauncher.installed(context, it) } }
    val icons = remember(installed) { installed.mapValues { (_, pkg) -> pkg?.let { AppLauncher.icon(context, it) } } }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            refresh++
        }
    }
    var menu by remember { mutableStateOf<StreamingApps.App?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun save(newOrder: List<String>, newHidden: Set<String>) = scope.launch {
        container.settings.update { it.copy(appsOrder = newOrder.joinToString(","), appsHidden = newHidden.joinToString(",")) }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 32.dp)) {
        Text("Apps", style = MaterialTheme.typography.headlineMedium)
        Text(
            "OK startet die App, Zurück bringt dich wieder hierher. OK halten: Reihenfolge oder ausblenden.",
            color = AyuColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )
        notice?.let { Text(it, color = AyuColors.Accent, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp)) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(240.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(apps, key = { it.id }) { app ->
                val pkg = installed[app.id]
                AppTile(
                    app, icons[app.id], installed = pkg != null,
                    onClick = {
                        if (!AppLauncher.launch(context, app)) {
                            notice = "${app.name} ist nicht installiert – öffne den Appstore …"
                            AppLauncher.openStore(context, app)
                        }
                    },
                    onLongClick = { menu = app },
                )
            }
            if (hidden.isNotEmpty()) item {
                Surface(
                    onClick = { save(order, emptySet()) },
                    modifier = Modifier.fillMaxWidth().height(135.dp),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = AyuColors.Surface, focusedContainerColor = AyuColors.AccentSoft),
                    border = AyuFocus.border,
                ) {
                    Text("Ausgeblendete zeigen (${hidden.size})", modifier = Modifier.align(Alignment.Center), color = AyuColors.TextSecondary)
                }
            }
        }
    }

    menu?.let { app ->
        AppMenu(
            app,
            onMove = { step -> save(StreamingApps.move(order, app.id, step), hidden); menu = null },
            onHide = { save(order, hidden + app.id); menu = null },
            onDismiss = { menu = null },
        )
    }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(6_000)
            notice = null
        }
    }
}

@Composable
private fun AppTile(app: StreamingApps.App, icon: ImageBitmap?, installed: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth().height(135.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(app.color), focusedContainerColor = Color(app.color)),
        border = AyuFocus.border,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        if (icon != null) {
            // Banner bzw. Symbol der installierten App (so, wie es auch der Fire-TV-Startbildschirm zeigt).
            Image(icon, contentDescription = app.name, modifier = Modifier.fillMaxSize().background(Color.Black))
        } else {
            Text(app.name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }
        if (!installed) {
            Text(
                "Installieren",
                color = Color.White, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun AppMenu(app: StreamingApps.App, onMove: (Int) -> Unit, onHide: () -> Unit, onDismiss: () -> Unit) {
    androidx.activity.compose.BackHandler { onDismiss() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.background(AyuColors.Surface, RoundedCornerShape(16.dp)).padding(24.dp).size(width = 380.dp, height = 300.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(app.name, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            MenuButton("◀ Nach vorn", first = true) { onMove(-1) }
            MenuButton("▶ Nach hinten") { onMove(1) }
            MenuButton("Ausblenden") { onHide() }
            MenuButton("Abbrechen") { onDismiss() }
        }
    }
}

@Composable
private fun MenuButton(label: String, first: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        focusFirst = first,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = AyuColors.SurfaceRaised, focusedContainerColor = AyuColors.AccentSoft),
        border = AyuFocus.border,
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}
