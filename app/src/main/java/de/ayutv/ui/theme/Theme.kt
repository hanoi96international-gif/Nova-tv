package de.ayutv.ui.theme

import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.Border
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.launch
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Wählbares Aussehen der App. */
enum class UiTheme(val label: String) {
    NORDLICHT("Nordlicht"),
    HANNOVER("Hannover 96"),
    CLASSIC("Standard"),
}

/** Farben eines Aussehens. */
data class Palette(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val accent: Color,
    val accentSoft: Color,
    val live: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val progress: Color,
) {
    companion object {
        val CLASSIC = Palette(
            background = Color(0xFF0B0F14),
            surface = Color(0xFF151B23),
            surfaceRaised = Color(0xFF1E2631),
            accent = Color(0xFF3DA9FC),
            accentSoft = Color(0xFF235F8E),
            live = Color(0xFFE5484D),
            textPrimary = Color(0xFFF2F5F8),
            textSecondary = Color(0xFF9AA7B4),
            progress = Color(0xFF3DA9FC),
        )

        /**
         * Nordlicht: Nachthimmel in Tiefblau bis Violett, Polarlicht in Türkis. Fokus als kräftiges Indigo mit
         * weißer Schrift (auf dem Fernseher von weitem klar), Akzente und Fortschritt in leuchtendem Türkis.
         */
        val NORDLICHT = Palette(
            background = Color(0xFF070912),
            surface = Color(0xFF121628),
            surfaceRaised = Color(0xFF1C2244),
            accent = Color(0xFF5EEAD4),
            accentSoft = Color(0xFF4F46E5),
            live = Color(0xFFFF4D6D),
            textPrimary = Color(0xFFF4F6FF),
            textSecondary = Color(0xFFA3AAC8),
            progress = Color(0xFF5EEAD4),
        )

        /**
         * Rot wie das Trikot, Schwarz, Weiß und ein Hauch Grün aus den Vereinsfarben. Fokus in kräftigem Rot,
         * auf dem Fernseher von weitem zu sehen; Fortschritt in Rasengrün.
         */
        val HANNOVER = Palette(
            background = Color(0xFF0C0909),
            surface = Color(0xFF1A1213),
            surfaceRaised = Color(0xFF2A1A1C),
            accent = Color(0xFFFF3B3F),
            accentSoft = Color(0xFFB0141B),
            live = Color(0xFFFF5A3C),
            textPrimary = Color(0xFFFFF7F5),
            textSecondary = Color(0xFFBFAEAD),
            progress = Color(0xFF1FB45A),
        )

        fun of(theme: UiTheme) = when (theme) {
            UiTheme.HANNOVER -> HANNOVER
            UiTheme.NORDLICHT -> NORDLICHT
            UiTheme.CLASSIC -> CLASSIC
        }
    }
}

/**
 * Die Farben der App. Wechselt das Aussehen, zeichnet Compose alles neu, was eine Farbe liest
 * (die Palette ist ein beobachteter Zustand).
 */
object AyuColors {
    var palette by mutableStateOf(Palette.NORDLICHT)
    var theme by mutableStateOf(UiTheme.NORDLICHT)
        private set

    fun use(next: UiTheme) {
        theme = next
        palette = Palette.of(next)
    }

    val Background get() = palette.background
    val Surface get() = palette.surface
    val SurfaceRaised get() = palette.surfaceRaised
    val Accent get() = palette.accent
    // Fokusfläche: hell genug, dass man auf dem Fernseher sofort sieht, wo man ist.
    val AccentSoft get() = palette.accentSoft
    val Live get() = palette.live
    val TextPrimary get() = palette.textPrimary
    val TextSecondary get() = palette.textSecondary
    val Progress get() = palette.progress

}

/**
 * Scrollen mit dem Steuerkreuz wie auf der Fire-TV-Startseite: Die Auswahl bleibt bei rund 30 % der Höhe (bzw. Breite)
 * stehen und die Liste läuft bei jedem Druck gleichmäßig um eine Zeile weiter. Vorher wanderte die Auswahl bis an den
 * Rand und die Liste sprang dann ruckartig nach; bei gehaltener Taste stauten sich solche Sprünge.
 * Am Anfang und Ende einer Liste hält sie wie gewohnt an. Touch-Bedienung (Handy) ist davon nicht betroffen.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private val PivotScroll = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // Passt der Inhalt ganz in den sichtbaren Bereich nicht (größer als der Container): nur so weit, dass der Anfang zu sehen ist.
        if (size >= containerSize) return offset
        val pivot = containerSize * PIVOT
        // Unterhalb des Fixpunkts wäre zu wenig Platz: so weit, dass das Element ganz zu sehen ist.
        val target = if (containerSize - pivot < size) containerSize - size else pivot
        return offset - target
    }
}

private const val PIVOT = 0.3f

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AyuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AyuColors.Accent,
            onPrimary = Color.Black,
            primaryContainer = AyuColors.AccentSoft,
            background = AyuColors.Background,
            onBackground = AyuColors.TextPrimary,
            surface = AyuColors.Surface,
            onSurface = AyuColors.TextPrimary,
            surfaceVariant = AyuColors.SurfaceRaised,
            onSurfaceVariant = AyuColors.TextSecondary,
            border = AyuColors.Accent,
        ),
    ) {
        // Ohne umgebende Surface wäre Text sonst schwarz auf dunklem Hintergrund.
        CompositionLocalProvider(
            LocalContentColor provides AyuColors.TextPrimary,
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides PivotScroll,
            content = content,
        )
    }
}

/**
 * Hintergrund der Menüs. Nordlicht: ein Nachthimmel von Tiefblau nach Schwarz mit Polarlicht-Bändern in Türkis,
 * Violett und einem Hauch Magenta, ein paar Sternen, Leuchten in den Ecken und ganz unten eine dunkle Hügelkette
 * mit türkisem Lichtsaum. Alles wird einmal je Bildschirmgröße berechnet (keine Kosten beim Scrollen).
 * Klassisch: schlichtes Dunkelblau.
 */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val theme = AyuColors.theme
    Box(
        modifier.fillMaxSize().background(AyuColors.Background)
            .then(
                when (theme) {
                    UiTheme.NORDLICHT -> Modifier.drawWithCache {
                        val image = rasterize { NorthernSky(it).draw(this) }
                        onDrawBehind { drawImage(image, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())) }
                    }
                    UiTheme.HANNOVER -> Modifier.drawWithCache {
                        val image = rasterize { StadiumNight(it).draw(this) }
                        onDrawBehind { drawImage(image, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())) }
                    }
                    UiTheme.CLASSIC -> Modifier
                }
            )
    ) {
        content()
    }
}

/**
 * Den Hintergrund einmal in ein Bild zeichnen. Direkt gezeichnet bestand er aus über einem Dutzend halbdurchsichtiger
 * Verläufe über den ganzen Bildschirm (Hannover: dazu über 100 Linien und eine riesige Schrift), und die Grafik musste
 * das bei jeder Fokusbewegung neu zusammensetzen: Auf dem Fire TV Stick ruckelte dadurch die ganze Oberfläche.
 * Ein fertiges Bild kostet pro Bild nur noch einmal Kopieren. Auf schwachen Geräten etwas kleiner (weich genug).
 */
private fun androidx.compose.ui.draw.CacheDrawScope.rasterize(
    paint: androidx.compose.ui.graphics.drawscope.DrawScope.(androidx.compose.ui.geometry.Size) -> Unit,
): androidx.compose.ui.graphics.ImageBitmap {
    val scale = if (de.ayutv.ui.UiPerformance.reduced) 0.75f else 1f
    val target = androidx.compose.ui.geometry.Size(
        (size.width * scale).coerceAtLeast(1f).toInt().toFloat(),
        (size.height * scale).coerceAtLeast(1f).toInt().toFloat(),
    )
    val image = androidx.compose.ui.graphics.ImageBitmap(target.width.toInt(), target.height.toInt())
    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
        this, layoutDirection, androidx.compose.ui.graphics.Canvas(image), target,
    ) { paint(target) }
    return image
}

/**
 * Hannover 96: Stadionabend, tiefrot nach schwarz, zwei Flutlichtkegel, eine breite rote Schärpe quer über den
 * Bildschirm mit Linien in Weiß und Grün, eine riesige angeschnittene 96 als Umriss, ein Fanschal-Streifen oben
 * (Schwarz/Weiß/Grün, „HANNOVER 96“, „96“), Glut in der Kurve, Nadelstreifen und unten die Linie in Schwarz-Weiß-Grün.
 */
private class StadiumNight(size: androidx.compose.ui.geometry.Size) {
    private val w = size.width
    private val h = size.height
    private val green = Color(0xFF1FB45A)
    private val sky = Brush.verticalGradient(listOf(Color(0xFF45090E), Color(0xFF1A0709), Color(0xFF0C0909)), endY = h)
    private val cones = listOf(w * 0.08f to 1f, w * 0.92f to -1f).map { (x, dir) ->
        Triple(
            androidx.compose.ui.graphics.Path().apply {
                moveTo(x, -h * 0.02f)
                lineTo(x + dir * w * 0.42f, h * 1.05f)
                lineTo(x + dir * w * 0.12f, h * 1.05f)
                close()
            },
            Offset(x, 0f),
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), center = Offset(x, 0f), radius = h * 0.22f),
        )
    }
    private val coneLight = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.09f), Color.Transparent), startY = 0f, endY = h)
    private fun sash(offset: Float, thickness: Float) = androidx.compose.ui.graphics.Path().apply {
        moveTo(-w * 0.1f, h * 1.05f + offset)
        lineTo(w * 1.1f, h * 0.05f + offset)
        lineTo(w * 1.1f, h * 0.05f + offset + thickness)
        lineTo(-w * 0.1f, h * 1.05f + offset + thickness)
        close()
    }
    private val band = h * 0.34f
    private val sashes = listOf(
        sash(-band / 2, band) to Color(0xFFD2121A).copy(alpha = 0.42f),
        sash(-band / 2 - h * 0.018f, h * 0.008f) to Color.White.copy(alpha = 0.55f),
        sash(band / 2 + h * 0.010f, h * 0.008f) to green.copy(alpha = 0.65f),
    )
    private val ember = Brush.radialGradient(listOf(Color(0xFFFF3B1F).copy(alpha = 0.20f), Color.Transparent), center = Offset(w * 0.5f, h * 1.15f), radius = w * 0.6f)
    private val numberFill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(26, 210, 18, 26)
        textSize = h * 0.95f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD_ITALIC)
        textAlign = android.graphics.Paint.Align.CENTER
    }
    private val numberOutline = android.graphics.Paint(numberFill).apply {
        color = android.graphics.Color.argb(46, 255, 255, 255)
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = h * 0.006f
    }
    private val scarf = h * 0.026f
    private val scarfLabel = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = scarf * 0.72f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textAlign = android.graphics.Paint.Align.CENTER
        letterSpacing = 0.25f
    }

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope) = with(scope) {
        drawRect(sky)
        for ((cone, center, glow) in cones) {
            drawPath(cone, coneLight)
            drawCircle(glow, radius = h * 0.22f, center = center)
        }
        for ((path, color) in sashes) drawPath(path, color)
        drawCircle(ember, radius = w * 0.6f, center = Offset(w * 0.5f, h * 1.15f))
        // Trikot-Nadelstreifen
        rotate(-18f) {
            var x = -h
            while (x < w + h) {
                drawLine(Color.White.copy(alpha = 0.022f), Offset(x, -h), Offset(x, h * 2), strokeWidth = 2f)
                x += 38f
            }
        }
        val native = drawContext.canvas.nativeCanvas
        // Riesige, angeschnittene 96 rechts
        native.save()
        native.rotate(-10f, w * 0.82f, h * 0.6f)
        native.drawText("96", w * 0.86f, h * 0.92f, numberFill)
        native.drawText("96", w * 0.86f, h * 0.92f, numberOutline)
        native.restore()
        // Fanschal oben
        val block = scarf * 9
        var x = 0f
        var i = 0
        while (x < w) {
            val (background, text) = when (i % 3) {
                0 -> Color(0xFF111111) to android.graphics.Color.WHITE
                1 -> Color(0xFFF2F2F2) to android.graphics.Color.rgb(17, 17, 17)
                else -> Color(0xFF178F47) to android.graphics.Color.WHITE
            }
            drawRect(background.copy(alpha = 0.9f), Offset(x, 0f), androidx.compose.ui.geometry.Size(block, scarf))
            scarfLabel.color = text
            native.drawText(if (i % 2 == 0) "HANNOVER 96" else "96", x + block / 2, scarf * 0.76f, scarfLabel)
            x += block
            i++
        }
        // Linie in Schwarz-Weiß-Grün am unteren Rand
        val line = h * 0.006f
        drawRect(Color.Black, Offset(0f, h - line * 3), androidx.compose.ui.geometry.Size(w, line))
        drawRect(Color.White.copy(alpha = 0.85f), Offset(0f, h - line * 2), androidx.compose.ui.geometry.Size(w, line))
        drawRect(green, Offset(0f, h - line), androidx.compose.ui.geometry.Size(w, line))
    }
}

/**
 * Stadion-Intro (Design Hannover 96): Flutlicht geht an, eine große 96 springt federnd herein, dann blendet alles
 * in die App über. Knapp zwei Sekunden; die App lädt währenddessen schon dahinter.
 */
@Composable
fun StadiumIntro(onDone: () -> Unit) {
    val lights = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val badge = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0.4f) }
    val fade = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.coroutineScope {
            launch { lights.animateTo(1f, androidx.compose.animation.core.tween(500)) }
            kotlinx.coroutines.delay(250)
            launch { badge.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 260f)) }
        }
        kotlinx.coroutines.delay(500)
        fade.animateTo(0f, androidx.compose.animation.core.tween(450))
        onDone()
    }
    Box(
        Modifier.fillMaxSize().drawWithCache {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD_ITALIC)
                textAlign = android.graphics.Paint.Align.CENTER
            }
            onDrawBehind {
                val w = size.width
                val h = size.height
                val a = fade.value
                drawRect(Color.Black.copy(alpha = a))
                drawRect(Brush.verticalGradient(listOf(Color(0xFF6A0A12).copy(alpha = 0.9f * a * lights.value), Color.Transparent), endY = h))
                for (x in listOf(w * 0.08f, w * 0.92f)) {
                    drawCircle(
                        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f * a * lights.value), Color.Transparent), center = Offset(x, 0f), radius = h * 0.5f),
                        radius = h * 0.5f,
                        center = Offset(x, 0f),
                    )
                }
                val scale = badge.value
                paint.color = android.graphics.Color.argb((255 * a).toInt(), 230, 30, 38)
                paint.textSize = h * 0.42f * scale
                drawContext.canvas.nativeCanvas.drawText("96", w / 2, h / 2 + h * 0.14f * scale, paint)
            }
        }
    )
}

/** Vorberechneter Nachthimmel für eine Bildschirmgröße. */
private class NorthernSky(size: androidx.compose.ui.geometry.Size) {
    private val w = size.width
    private val h = size.height
    private val base = Brush.verticalGradient(listOf(Color(0xFF0E1330), Color(0xFF080B1A), Color(0xFF05060C)), endY = h)
    private val glows = listOf(
        Triple(Offset(w * 0.08f, h * 0.02f), w * 0.55f, Color(0xFF6D4BFF).copy(alpha = 0.32f)),
        Triple(Offset(w * 0.92f, h * 0.05f), w * 0.45f, Color(0xFF14B8A6).copy(alpha = 0.24f)),
        Triple(Offset(w * 0.78f, h * 1.05f), w * 0.45f, Color(0xFFDB2777).copy(alpha = 0.10f)),
    ).map { (center, radius, color) -> Triple(center, radius, Brush.radialGradient(listOf(color, Color.Transparent), center = center, radius = radius)) }

    /** Ein Polarlicht-Band als geschwungene Fläche; mehrfach verbreitert und schwächer gezeichnet = weicher Rand. */
    private class Band(val path: androidx.compose.ui.graphics.Path, val brush: Brush, val alpha: Float)
    private val bands: List<Band> = buildList {
        val colors = listOf(Color(0xFF2DD4BF), Color(0xFF34D399), Color(0xFF818CF8), Color(0xFFC084FC), Color(0xFF2DD4BF))
        fun ribbon(top: Float, amplitude: Float, thickness: Float, phase: Float, alpha: Float) {
            for ((k, spread) in listOf(1f, 1.8f, 2.8f).withIndex()) {
                val t = thickness * spread
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(-w * 0.05f, top + amplitude * kotlin.math.sin(phase))
                    val steps = 6
                    for (i in 1..steps) {
                        val x = -w * 0.05f + w * 1.1f * i / steps
                        val y = top + amplitude * kotlin.math.sin(phase + i * 1.15f)
                        val cx = x - w * 1.1f / steps / 2
                        val cy = top + amplitude * kotlin.math.sin(phase + (i - 0.5f) * 1.15f) - amplitude * 0.4f
                        quadraticTo(cx, cy, x, y)
                    }
                    lineTo(w * 1.05f, top + t + amplitude)
                    for (i in 6 downTo 0) {
                        val x = -w * 0.05f + w * 1.1f * i / 6
                        lineTo(x, top + t + amplitude * kotlin.math.sin(phase + i * 1.15f + 0.6f) * 0.7f)
                    }
                    close()
                }
                val brush = Brush.verticalGradient(
                    listOf(Color.Transparent, Color.White, Color.Transparent),
                    startY = top - amplitude, endY = top + t + amplitude,
                )
                add(Band(path, Brush.horizontalGradient(colors), alpha / (k * 1.8f + 1f)))
                if (k == 0) add(Band(path, brush, alpha * 0.18f))
            }
        }
        ribbon(top = h * 0.10f, amplitude = h * 0.06f, thickness = h * 0.05f, phase = 0.3f, alpha = 0.20f)
        ribbon(top = h * 0.24f, amplitude = h * 0.05f, thickness = h * 0.035f, phase = 2.1f, alpha = 0.13f)
    }

    private val stars: List<Pair<Offset, Float>> = kotlin.random.Random(7).let { r ->
        List(90) { Offset(r.nextFloat() * w, r.nextFloat() * h * 0.62f) to (0.05f + r.nextFloat() * 0.28f) }
    }

    /** Dunkle Hügelkette unten, mit türkisem Lichtsaum oben. */
    private val ridge = androidx.compose.ui.graphics.Path().apply {
        moveTo(0f, h)
        lineTo(0f, h * 0.86f)
        val points = listOf(0.12f to 0.80f, 0.24f to 0.85f, 0.38f to 0.78f, 0.52f to 0.84f, 0.66f to 0.79f, 0.80f to 0.86f, 0.92f to 0.81f, 1.0f to 0.84f)
        var previous = 0f to 0.86f
        for ((x, y) in points) {
            quadraticTo(w * (previous.first + x) / 2, h * minOf(previous.second, y) - h * 0.015f, w * x, h * y)
            previous = x to y
        }
        lineTo(w, h)
        close()
    }
    private val ridgeFill = Brush.verticalGradient(listOf(Color(0xFF0B0F22), Color(0xFF04050A)), startY = h * 0.78f, endY = h)
    private val vignette = Brush.radialGradient(
        listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
        center = Offset(w * 0.5f, h * 0.45f), radius = maxOf(w, h) * 0.75f,
    )

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope) = with(scope) {
        drawRect(base)
        for ((center, radius, brush) in glows) drawCircle(brush, radius, center)
        for ((position, alpha) in stars) drawCircle(Color.White.copy(alpha = alpha), radius = 1.6f, center = position)
        for (band in bands) drawPath(band.path, band.brush, alpha = band.alpha)
        drawPath(ridge, Color(0xFF2DD4BF).copy(alpha = 0.35f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = h * 0.004f))
        drawPath(ridge, ridgeFill)
        drawRect(vignette)
    }
}

/**
 * Kurzes Intro beim Start (nur im Nordlicht-Design): Das Polarlicht leuchtet auf, „DnA·TV“ zieht mit sich
 * schließendem Buchstabenabstand herein, dann blendet alles in die App über. Knapp zwei Sekunden; die App lädt
 * währenddessen schon dahinter.
 */
@Composable
fun AuroraIntro(onDone: () -> Unit) {
    val light = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val word = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val fade = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.coroutineScope {
            launch { light.animateTo(1f, androidx.compose.animation.core.tween(700)) }
            kotlinx.coroutines.delay(200)
            launch { word.animateTo(1f, androidx.compose.animation.core.tween(800, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
        }
        kotlinx.coroutines.delay(350)
        fade.animateTo(0f, androidx.compose.animation.core.tween(450))
        onDone()
    }
    Box(
        Modifier.fillMaxSize().drawWithCache {
            val sky = NorthernSky(size)
            onDrawBehind {
                val a = fade.value
                drawRect(Color.Black.copy(alpha = a))
                if (a > 0f) {
                    val layer = light.value * a
                    drawIntoCanvas { canvas ->
                        val paint = androidx.compose.ui.graphics.Paint().apply { alpha = layer }
                        canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, size), paint)
                        sky.draw(this)
                        canvas.restore()
                    }
                    val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.argb((255 * a * word.value).toInt(), 244, 246, 255)
                        textSize = size.height * 0.13f
                        typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
                        textAlign = android.graphics.Paint.Align.CENTER
                        letterSpacing = 0.6f - 0.45f * word.value
                    }
                    val glow = android.graphics.Paint(text).apply {
                        color = android.graphics.Color.argb((120 * a * word.value).toInt(), 94, 234, 212)
                        maskFilter = android.graphics.BlurMaskFilter(size.height * 0.02f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                    }
                    drawContext.canvas.nativeCanvas.apply {
                        drawText("DnA·TV", size.width / 2, size.height * 0.53f, glow)
                        drawText("DnA·TV", size.width / 2, size.height * 0.53f, text)
                    }
                }
            }
        }
    )
}

/** Gut sichtbare Fokusmarkierung für Listen und Raster: heller Rahmen zusätzlich zur Fokusfarbe. */
object AyuFocus {
    val border: ClickableSurfaceBorder
        @Composable get() = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, AyuColors.Accent)),
        )
}
