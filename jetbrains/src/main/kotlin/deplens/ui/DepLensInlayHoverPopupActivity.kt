package deplens.ui

import com.intellij.codeInsight.hint.LineTooltipRenderer
import com.intellij.codeInsight.hint.TooltipGroup
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.codeInsight.hints.declarative.impl.inlayRenderer.DeclarativeInlayRendererBase
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.HintHint
import com.intellij.ui.JBColor
import com.intellij.ui.LightweightHint
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import deplens.utils.UiUtils
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.MouseInfo
import java.awt.Point
import java.awt.RenderingHints
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.border.AbstractBorder

class DepLensInlayHoverPopupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val listener = DepLensInlayHoverPopupListener(project)
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addEditorMouseMotionListener(listener, project)
        multicaster.addEditorMouseListener(listener, project)
    }
}

private class DepLensInlayHoverPopupListener(private val project: Project) : EditorMouseMotionListener, EditorMouseListener {

    companion object {

        private const val DEP_PROVIDER_PREFIX = "deplens."
        private const val HIDE_DELAY_MS = 90
        private const val SWITCH_DELAY_MS = 220
        private val TOOLTIP_GROUP = TooltipGroup("deplens.inlay.hover", 0)
    }

    private val hideAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)
    private val switchAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)
    private var shownHint: LightweightHint? = null
    private var shownInlay: Inlay<*>? = null
    private var shownText: String? = null
    private var shownGithubUrl: String? = null

    override fun mouseMoved(event: EditorMouseEvent) {
        if (event.editor.project !== project || event.area != EditorMouseEventArea.EDITING_AREA) {
            scheduleHide()
            return
        }

        val inlay = event.editor.inlayModel.getElementAt(event.mouseEvent.point)
        val renderer = inlay?.renderer as? DeclarativeInlayRendererBase<*>
        if (renderer == null || !renderer.providerId.startsWith(DEP_PROVIDER_PREFIX)) {
            scheduleHide()
            return
        }

        val hoverText = renderer.presentationLists
            .asSequence()
            .map { it.model.payloads.orEmpty() }
            .flatten()
            .firstNotNullOfOrNull { payload ->
                if (payload.payloadName != UiUtils.HOVER_TEXT_PAYLOAD_NAME) {
                    null
                } else {
                    (payload.payload as? StringInlayActionPayload)?.text
                }
            }
        val githubUrl = renderer.presentationLists
            .asSequence()
            .map { it.model.payloads.orEmpty() }
            .flatten()
            .firstNotNullOfOrNull { payload ->
                if (payload.payloadName != UiUtils.GITHUB_URL_PAYLOAD_NAME) {
                    null
                } else {
                    (payload.payload as? StringInlayActionPayload)?.text
                }
            }

        if (hoverText.isNullOrBlank()) {
            scheduleHide()
            return
        }

        hideAlarm.cancelAllRequests()
        switchAlarm.cancelAllRequests()
        if (shownInlay == inlay && shownText == hoverText && shownGithubUrl == githubUrl && shownHint != null) {
            return
        }

        if (shownHint != null && shownInlay != null && shownInlay != inlay) {
            scheduleSwitch(event, inlay, hoverText, githubUrl)
            return
        }

        showHint(event.editor, inlay, hoverText, githubUrl)
    }

    private fun showHint(editor: com.intellij.openapi.editor.Editor, inlay: Inlay<*>, hoverText: String, githubUrl: String?) {
        hideHint()

        val inlayBounds = inlay.bounds
        if (inlayBounds == null) {
            return
        }

        // Anchor to the inlay itself instead of raw mouse coordinates to avoid container-related offset.
        val anchorPoint = Point(
            inlayBounds.x + inlayBounds.width / 2,
            inlayBounds.y + inlayBounds.height
        )

        val popupPoint = editor.component.rootPane?.layeredPane?.let { layeredPane ->
            SwingUtilities.convertPoint(editor.contentComponent, anchorPoint, layeredPane)
        } ?: Point(anchorPoint)

        val escapedText = StringUtil.escapeXmlEntities(hoverText).replace("\n", "<br/>")
        val linkHtml = if (!githubUrl.isNullOrBlank()) {
            val escapedUrl = StringUtil.escapeXmlEntities(githubUrl)
            "<br/><br/><a href=\"$escapedUrl\">Open on GitHub</a>"
        } else {
            ""
        }
        val htmlText = "<html>$escapedText$linkHtml</html>"
        val lineTooltip = DepLensLineTooltipRenderer(htmlText, emptyArray())
        val hintHint = HintHint(editor.component, popupPoint).setAwtTooltip(false)
        hintHint.setComponentBorder(JBUI.Borders.empty())
        hintHint.setBorderInsets(JBUI.emptyInsets())
        shownHint = lineTooltip.show(editor, Point(popupPoint), false, TOOLTIP_GROUP, hintHint)
        shownInlay = inlay
        shownText = hoverText
        shownGithubUrl = githubUrl
    }

    override fun mouseExited(event: EditorMouseEvent) {
        scheduleHide()
    }

    override fun mousePressed(event: EditorMouseEvent) {
        hideHint()
    }

    private fun hideHint() {
        hideAlarm.cancelAllRequests()
        switchAlarm.cancelAllRequests()
        shownHint?.hide()
        shownHint = null
        shownInlay = null
        shownText = null
        shownGithubUrl = null
    }

    private fun scheduleHide() {
        hideAlarm.cancelAllRequests()
        switchAlarm.cancelAllRequests()
        hideAlarm.addRequest({
            if (!isPointerInsideHint()) {
                hideHint()
            }
        }, HIDE_DELAY_MS)
    }

    private fun scheduleSwitch(event: EditorMouseEvent, targetInlay: Inlay<*>, targetText: String, targetGithubUrl: String?) {
        if (isPointerInsideHint()) return

        switchAlarm.cancelAllRequests()
        val editor = event.editor
        switchAlarm.addRequest({
            if (isPointerInsideHint()) return@addRequest

            val pointer = MouseInfo.getPointerInfo()?.location ?: return@addRequest
            val pointInEditor = Point(pointer)
            SwingUtilities.convertPointFromScreen(pointInEditor, editor.contentComponent)

            val currentInlay = editor.inlayModel.getElementAt(pointInEditor)
            if (currentInlay == targetInlay) {
                showHint(editor, targetInlay, targetText, targetGithubUrl)
            }
        }, SWITCH_DELAY_MS)
    }

    private fun isPointerInsideHint(): Boolean {
        val hint = shownHint ?: return false
        if (!hint.isVisible) return false

        val component = hint.component
        if (!component.isShowing) return false

        val screenPoint = MouseInfo.getPointerInfo()?.location ?: return false
        val localPoint = Point(screenPoint)
        SwingUtilities.convertPointFromScreen(localPoint, component)

        return localPoint.x >= 0 &&
            localPoint.y >= 0 &&
            localPoint.x < component.width &&
            localPoint.y < component.height
    }
}

private class DepLensLineTooltipRenderer(text: String, comparable: Array<Any>) : LineTooltipRenderer(text, comparable) {

    override fun fillPanel(
        editor: com.intellij.openapi.editor.Editor,
        component: JPanel,
        hint: LightweightHint,
        hintHint: HintHint,
        actions: MutableList<in com.intellij.openapi.actionSystem.AnAction>,
        reloader: LineTooltipRenderer.TooltipReloader,
        expand: Boolean
    ) {
        super.fillPanel(editor, component, hint, hintHint, actions, reloader, expand)
        component.border = JBUI.Borders.compound(
            RoundedHintBorder(),
            JBUI.Borders.empty(8, 12)
        )
        clearBorders(component)
    }

    private fun clearBorders(panel: JPanel) {
        for (child in panel.components) {
            when (child) {
                is JScrollPane -> {
                    child.border = JBUI.Borders.empty()
                    child.viewportBorder = JBUI.Borders.empty()
                    val view = child.viewport?.view
                    if (view is JEditorPane) {
                        view.border = JBUI.Borders.empty()
                    }
                }
                is JEditorPane -> {
                    child.border = JBUI.Borders.empty()
                }
                is JPanel -> clearBorders(child)
            }
        }
    }
}

private class RoundedHintBorder : AbstractBorder() {

    private val arc = JBUI.scale(10)

    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val g2 = g as? Graphics2D ?: return
        val oldAntialias = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
        val oldColor = g2.color
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = UIManager.getColor("Tooltip.borderColor") ?: JBColor.GRAY
        g2.drawRoundRect(x, y, width - 1, height - 1, arc, arc)
        g2.color = oldColor
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAntialias)
    }
}
