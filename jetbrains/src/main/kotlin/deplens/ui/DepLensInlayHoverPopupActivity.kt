package deplens.ui

import com.intellij.codeInsight.hint.LineTooltipRenderer
import com.intellij.codeInsight.hint.TooltipController
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
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import deplens.utils.UiUtils
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
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
        private const val TRANSITION_MARGIN_PX = 18
        private val TOOLTIP_GROUP = TooltipGroup("deplens.inlay.hover", 0)
    }

    private var shownHint: LightweightHint? = null
    private var shownInlay: Inlay<*>? = null
    private var shownText: String? = null
    private var shownGithubUrl: String? = null
    // Once pointer has entered tooltip, close immediately when it leaves.
    private var hintWasEntered: Boolean = false

    override fun mouseMoved(event: EditorMouseEvent) {
        if (event.editor.project !== project || event.area != EditorMouseEventArea.EDITING_AREA) {
            if (!shouldKeepHintByGeometry(event.editor)) {
                cancelTooltip(event)
            }
            return
        }

        val inlay = event.editor.inlayModel.getElementAt(event.mouseEvent.point)
        val renderer = inlay?.renderer as? DeclarativeInlayRendererBase<*>
        if (renderer == null || !renderer.providerId.startsWith(DEP_PROVIDER_PREFIX)) {
            if (!shouldKeepHintByGeometry(event.editor)) {
                cancelTooltip(event)
            }
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
            if (!shouldKeepHintByGeometry(event.editor)) {
                cancelTooltip(event)
            }
            return
        }

        if (shownInlay == inlay && shownText == hoverText && shownGithubUrl == githubUrl) {
            return
        }

        showHint(event, inlay, hoverText, githubUrl)
    }

    private fun showHint(event: EditorMouseEvent, inlay: Inlay<*>, hoverText: String, githubUrl: String?) {
        val escapedText = StringUtil.escapeXmlEntities(hoverText).replace("\n", "<br/>")
        val linkHtml = if (!githubUrl.isNullOrBlank()) {
            val escapedUrl = StringUtil.escapeXmlEntities(githubUrl)
            "<br/><br/><a href=\"$escapedUrl\">Open on GitHub</a>"
        } else {
            ""
        }
        val htmlText = "<html>$escapedText$linkHtml</html>"
        val lineTooltip = DepLensLineTooltipRenderer(htmlText, emptyArray<Any>())
        val hintHint = HintHint(event.mouseEvent).setAwtTooltip(false)
        hintHint.setComponentBorder(JBUI.Borders.empty())
        hintHint.setBorderInsets(JBUI.emptyInsets())
        shownHint = TooltipController.getInstance().showTooltipByMouseMove(
            event.editor,
            RelativePoint(event.mouseEvent),
            lineTooltip,
            false,
            TOOLTIP_GROUP,
            hintHint
        )
        shownInlay = inlay
        shownText = hoverText
        shownGithubUrl = githubUrl
        hintWasEntered = false
    }

    override fun mouseExited(event: EditorMouseEvent) {
        if (!shouldKeepHintByGeometry(event.editor)) {
            cancelTooltip(event)
        }
    }

    override fun mousePressed(event: EditorMouseEvent) {
        cancelTooltip(event)
    }

    private fun cancelTooltip(event: EditorMouseEvent) {
        TooltipController.getInstance().cancelTooltip(TOOLTIP_GROUP, event.mouseEvent, false)
        shownHint = null
        shownInlay = null
        shownText = null
        shownGithubUrl = null
        hintWasEntered = false
    }

    private fun shouldKeepHintByGeometry(editor: com.intellij.openapi.editor.Editor): Boolean {
        val pointer = MouseInfo.getPointerInfo()?.location ?: return false
        val hintRect = getHintScreenRect() ?: return false

        // Keep alive while pointer is inside the tooltip content.
        if (hintRect.contains(pointer)) {
            hintWasEntered = true
            return true
        }

        // After first entry, no transition grace: leaving tooltip closes it.
        if (hintWasEntered) {
            return false
        }

        // Before first entry, allow movement inside the inlay->tooltip corridor.
        val inlayRect = getInlayScreenRect(editor) ?: return false
        val transition = inlayRect.union(hintRect)
        transition.grow(TRANSITION_MARGIN_PX, TRANSITION_MARGIN_PX)
        return transition.contains(pointer)
    }

    private fun getHintScreenRect(): Rectangle? {
        val hint = shownHint ?: return null
        if (!hint.isVisible) return null

        val component = hint.component
        if (!component.isShowing) return null

        val origin = component.locationOnScreen
        return Rectangle(origin.x, origin.y, component.width, component.height)
    }

    private fun getInlayScreenRect(editor: com.intellij.openapi.editor.Editor): Rectangle? {
        val inlayBounds = shownInlay?.bounds ?: return null
        val topLeft = Point(inlayBounds.x, inlayBounds.y)
        SwingUtilities.convertPointToScreen(topLeft, editor.contentComponent)
        return Rectangle(topLeft.x, topLeft.y, inlayBounds.width, inlayBounds.height)
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
        // Keep the dark popup style but remove default hard borders and add rounded container spacing.
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
