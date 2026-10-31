package de.ayutv.desktop.player

import java.awt.Canvas
import java.awt.Color
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.WindowConstants

/**
 * Vollbildfenster für die Direktausgabe (HDR-Bildschirm): VLC zeichnet hier selbst mit Direct3D 11. Nur so erreicht HDR10
 * den Bildschirm unverändert (Windows-HDR an); in der normalen Oberfläche wird HDR auf SDR umgerechnet.
 *
 * Die Bedienung bleibt in DnA-TV: Tasten (Tastatur, Fire-TV-Fernbedienung) gehen an das Hauptfenster weiter, Menüs und
 * Senderliste erscheinen dort ([cover] blendet dieses Fenster so lange aus). Ein Mausklick holt die Oberfläche zurück.
 */
class DirectWindow(
    /** Hauptfenster (Tasten dorthin, Bildschirm von dort). */
    private val owner: () -> Window?,
    /** Fenster hat den Fokus (für die Fernbedienungs-Erkennung). */
    private val onFocus: (Boolean) -> Unit,
    /** Mausklick: Oberfläche zeigen (z. B. Info). */
    private val onClick: () -> Unit,
) {
    val canvas = Canvas().apply { background = Color.BLACK }
    private val frame = JFrame("DnA-TV").apply {
        isUndecorated = true
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        contentPane.background = Color.BLACK
        add(canvas)
    }
    /** Soll sichtbar sein (Wiedergabe direkt) und ist nicht von Menüs verdeckt. */
    private var active = false
    private var covered = false

    init {
        val forward = object : KeyListener {
            override fun keyPressed(e: KeyEvent) = pass(e)
            override fun keyReleased(e: KeyEvent) = pass(e)
            override fun keyTyped(e: KeyEvent) = pass(e)
        }
        frame.addKeyListener(forward)
        canvas.addKeyListener(forward)
        val click = object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) = onClick() }
        frame.addMouseListener(click)
        canvas.addMouseListener(click)
        frame.addWindowFocusListener(object : WindowAdapter() {
            override fun windowGainedFocus(e: WindowEvent?) = onFocus(true)
            override fun windowLostFocus(e: WindowEvent?) = onFocus(false)
        })
    }

    /** Taste an das Hauptfenster geben, als wäre sie dort gedrückt worden. */
    private fun pass(e: KeyEvent) {
        val w = owner() ?: return
        val target = w.mostRecentFocusOwner ?: w.focusOwner ?: w
        target.dispatchEvent(KeyEvent(target, e.id, e.`when`, e.modifiersEx, e.keyCode, e.keyChar, e.keyLocation))
        e.consume()
    }

    /** Fenster über den ganzen Bildschirm des Hauptfensters legen und zeigen (vor dem Abspielen: VLC braucht es sichtbar). */
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

    /** Fenster anlegen, ohne es zu zeigen (VLC braucht ein vorhandenes Fenster, auch wenn gerade ein Menü offen ist). */
    fun prepare() {
        if (!frame.isDisplayable) {
            place()
            frame.addNotify()
        }
    }

    private fun place() {
        val config = owner()?.graphicsConfiguration ?: frame.graphicsConfiguration
        frame.bounds = config.bounds
    }

    private fun update() {
        val visible = active && !covered
        if (visible == frame.isVisible) {
            if (visible) frame.toFront()
            return
        }
        if (visible) {
            frame.isVisible = true
            frame.toFront()
            frame.requestFocus()
            canvas.requestFocusInWindow()
        } else {
            frame.isVisible = false
            owner()?.let { it.toFront(); it.requestFocus() }
        }
    }

    val showing: Boolean get() = frame.isVisible

    fun dispose() {
        active = false
        frame.dispose()
    }
}
