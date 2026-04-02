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
import com.intellij.ui.LightweightHint
import com.intellij.util.Alarm
import deplens.utils.UiUtils
import java.awt.MouseInfo
import java.awt.Point
import javax.swing.SwingUtilities

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
        private const val HIDE_DELAY_MS = 450
        private const val SWITCH_DELAY_MS = 220
        private val TOOLTIP_GROUP = TooltipGroup("deplens.inlay.hover", 0)
    }

    private val hideAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)
    private val switchAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)
    private var shownHint: LightweightHint? = null
    private var shownInlay: Inlay<*>? = null
    private var shownText: String? = null

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

        if (hoverText.isNullOrBlank()) {
            scheduleHide()
            return
        }

        hideAlarm.cancelAllRequests()
        switchAlarm.cancelAllRequests()
        if (shownInlay == inlay && shownText == hoverText && shownHint != null) {
            return
        }

        if (shownHint != null && shownInlay != null && shownInlay != inlay) {
            scheduleSwitch(event, inlay, hoverText)
            return
        }

        showHint(event.editor, inlay, hoverText)
    }

    private fun showHint(editor: com.intellij.openapi.editor.Editor, inlay: Inlay<*>, hoverText: String) {
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

        val htmlText = "<html>${StringUtil.escapeXmlEntities(hoverText).replace("\n", "<br/>")}</html>"
        val lineTooltip = LineTooltipRenderer(htmlText, emptyArray<Any>())
        val hintHint = HintHint(editor.component, popupPoint).setAwtTooltip(false)
        shownHint = lineTooltip.show(editor, Point(popupPoint), false, TOOLTIP_GROUP, hintHint)
        shownInlay = inlay
        shownText = hoverText
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

    private fun scheduleSwitch(event: EditorMouseEvent, targetInlay: Inlay<*>, targetText: String) {
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
                showHint(editor, targetInlay, targetText)
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
