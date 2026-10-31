package de.ayutv.desktop.player

import java.awt.Canvas
import java.awt.Color
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.JWindow
import javax.swing.SwingUtilities

/**
 * Direktausgabe: VLC zeichnet selbst mit Direct3D 11 in dieses Fenster. Hardware-Dekodierung und Anzeige bleiben dabei
 * komplett auf der Grafikkarte (kein Umweg über den Hauptspeicher). Das ist der schnellste Weg für 4K mit 50 Bildern,
 * und HDR10 erreicht einen HDR-Bildschirm unverändert (Windows-HDR an).
 *
 * Das Fenster gehört zum Hauptfenster und liegt genau über dessen Inhalt (auch im Fenstermodus, es wandert mit).
 * Tasten (Tastatur, Fire-TV-Fernbedienung) gehen an das Hauptfenster weiter; Menüs und Senderliste erscheinen dort
 * ([cover] blendet dieses Fenster so lange aus). Ein Mausklick öffnet das Menü.
 */
class DirectWindow(
    /** Hauptfenster (Tasten dorthin, Lage von dort). */
    private val owner: () -> Window?,
    /** Fenster hat den Fokus (für die Fernbedienungs-Erkennung). */
    private val onFocus: (Boolean) -> Unit,
    /** Mausklick: Menü öffnen. */
    private val onClick: () -> Unit,
) {
    val canvas = Canvas().apply { background = Color.BLACK }
    private var window: Window? = null
    /** Soll sichtbar sein (Wiedergabe direkt) und ist nicht von Menüs verdeckt. */
    private var active = false
    private var covered = false

    private val follow = object : ComponentAdapter() {
        override fun componentMoved(e: ComponentEvent?) = place()
        override fun componentResized(e: ComponentEvent?) = place()
    }

    private val forward = object : KeyListener {
        override fun keyPressed(e: KeyEvent) = pass(e)
        override fun keyReleased(e: KeyEvent) = pass(e)
        override fun keyTyped(e: KeyEvent) = pass(e)
    }

    /** Fenster anlegen (ohne es zu zeigen): VLC braucht ein vorhandenes Fenster, auch wenn gerade ein Menü offen ist. */
    fun prepare() {
        if (window?.isDisplayable == true) return
        val main = owner()
        val w: Window = if (main != null) JWindow(main) else JFrame("DnA-TV").apply { isUndecorated = true }
        w.background = Color.BLACK
        w.add(canvas)
        w.addKeyListener(forward)
        canvas.addKeyListener(forward)
        val click = object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) = onClick() }
        canvas.addMouseListener(click)
        w.addWindowFocusListener(object : WindowAdapter() {
            override fun windowGainedFocus(e: WindowEvent?) = onFocus(true)
            override fun windowLostFocus(e: WindowEvent?) = onFocus(false)
        })
        w.focusableWindowState = true
        main?.addComponentListener(follow)
        window = w
        place()
        w.addNotify()
    }

    /** Taste an das Hauptfenster geben, als wäre sie dort gedrückt worden. */
    private fun pass(e: KeyEvent) {
        val w = owner() ?: return
        val target = w.mostRecentFocusOwner ?: w.focusOwner ?: w
        target.dispatchEvent(KeyEvent(target, e.id, e.`when`, e.modifiersEx, e.keyCode, e.keyChar, e.keyLocation))
        e.consume()
    }

    fun show() {
        active = true
        prepare()
        place()
        update()
    }

    fun hide() {
        active = false
        update()
    }

    /** Menüs, Senderliste, Stream-Info offen: Fenster weg, damit sie im Hauptfenster zu sehen sind. */
    fun cover(value: Boolean) {
        if (covered == value) return
        covered = value
        update()
    }

    /** Genau über den Inhalt des Hauptfensters legen (ohne Titelleiste); ohne Hauptfenster über den ganzen Bildschirm. */
    private fun place() {
        val w = window ?: return
        val main = owner()
        val content = (main as? javax.swing.RootPaneContainer)?.contentPane
        w.bounds = when {
            content != null && content.isShowing -> java.awt.Rectangle(content.locationOnScreen, content.size)
            main != null && main.isShowing -> main.bounds
            else -> (main?.graphicsConfiguration ?: w.graphicsConfiguration).bounds
        }
    }

    private fun update() {
        val w = window ?: return
        val visible = active && !covered
        if (visible == w.isVisible) {
            if (visible) w.toFront()
            return
        }
        if (visible) {
            place()
            w.isVisible = true
            w.toFront()
            w.requestFocus()
            canvas.requestFocusInWindow()
        } else {
            w.isVisible = false
            owner()?.let { it.toFront(); it.requestFocus() }
        }
    }

    val showing: Boolean get() = window?.isVisible == true

    fun dispose() {
        active = false
        owner()?.removeComponentListener(follow)
        val w = window ?: return
        if (SwingUtilities.isEventDispatchThread()) w.dispose() else SwingUtilities.invokeLater { w.dispose() }
    }
}
