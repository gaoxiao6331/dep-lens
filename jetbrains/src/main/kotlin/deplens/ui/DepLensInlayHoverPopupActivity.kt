package deplens.ui

import com.intellij.codeInsight.hint.LineTooltipRenderer
import com.intellij.codeInsight.hint.TooltipController
import com.intellij.codeInsight.hint.TooltipGroup
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import deplens.common.I18nKey
import deplens.utils.I18n
import com.intellij.ui.HintHint
import com.intellij.ui.JBColor
import com.intellij.ui.LightweightHint
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import deplens.utils.UiUtils
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.border.AbstractBorder

/**
 * Project activity that registers a global editor mouse listener 
 * to handle hover popups for DepLens declarative inlays.
 */
class DepLensInlayHoverPopupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val listener = DepLensInlayHoverPopupListener(project)
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addEditorMouseMotionListener(listener, project)
        multicaster.addEditorMouseListener(listener, project)
    }
}

/**
 * Listener to intercept mouse movements and show/hide tooltips 
 * when hovering over DepLens inlays in the editor.
 */
private class DepLensInlayHoverPopupListener(private val project: Project) : EditorMouseMotionListener, EditorMouseListener {

    companion object {

        private const val DEP_PROVIDER_PREFIX = "deplens."
        private const val TRANSITION_MARGIN_PX = 18
        private const val INLAY_SWITCH_DWELL_MILLIS = 120L
        private val TOOLTIP_GROUP = TooltipGroup("deplens.inlay.hover", 0)
    }

    private var shownHint: LightweightHint? = null
    private var shownInlay: Inlay<*>? = null
    private var shownText: String? = null
    private var shownGithubUrl: String? = null
    private var shownRetryToken: String? = null
    // Once pointer has entered tooltip, close immediately when it leaves.
    private var hintWasEntered: Boolean = false
    private var pendingSwitchInlay: Inlay<*>? = null
    private var pendingSwitchSince: Long = 0L
    private var pendingSwitchEvent: EditorMouseEvent? = null
    private var pendingSwitchSeq: Long = 0L

    override fun mouseMoved(event: EditorMouseEvent) {
        // Only process events within the current project's editing area
        if (event.editor.project !== project || event.area != EditorMouseEventArea.EDITING_AREA) {
            clearPendingSwitch()
            if (!shouldKeepHintByGeometry(event.editor)) {
                cancelTooltip(event)
            }
            return
        }

        val inlay = event.editor.inlayModel.getElementAt(event.mouseEvent.point)
        val renderer = inlay?.renderer ?: run {
            clearPendingSwitch()
            if (!shouldKeepHintByGeometry(event.editor)) {
                cancelTooltip(event)
            }
            return
        }
        if (!isDepLensDeclarativeRenderer(renderer)) {
            clearPendingSwitch()
            if (!shouldKeepHintByGeometry(event.editor)) {
                cancelTooltip(event)
            }
            return
        }

        val hoverText = extractPayloadValue(renderer, UiUtils.HOVER_TEXT_PAYLOAD_NAME)
        val githubUrl = extractPayloadValue(renderer, UiUtils.GITHUB_URL_PAYLOAD_NAME)
        val retryToken = extractPayloadValue(renderer, UiUtils.RETRY_TOKEN_PAYLOAD_NAME)

        if (hoverText.isNullOrBlank()) {
            if (!shouldKeepHintByGeometry(event.editor)) {
                cancelTooltip(event)
            }
            return
        }

        // While the current popup is still within the allowed transition corridor,
        // don't switch to another line's inlay just because the cursor crossed it.
        if (shownHint != null && shownInlay != null && inlay != shownInlay && shouldKeepHintByGeometry(event.editor)) {
            val now = System.currentTimeMillis()
            if (pendingSwitchInlay !== inlay) {
                schedulePendingSwitch(event, inlay, now)
                return
            }
            if (now - pendingSwitchSince < INLAY_SWITCH_DWELL_MILLIS) {
                return
            }
        }

        clearPendingSwitch()
        if (shownInlay == inlay && shownText == hoverText && shownGithubUrl == githubUrl && shownRetryToken == retryToken) {
            return
        }

        showHint(event, inlay, hoverText, githubUrl, retryToken)
    }

    private fun showHint(
        event: EditorMouseEvent,
        inlay: Inlay<*>,
        hoverText: String,
        githubUrl: String?,
        retryToken: String?
    ) {
        val escapedText = StringUtil.escapeXmlEntities(hoverText).replace("\n", "<br/>")
        val linkHtml = if (!githubUrl.isNullOrBlank()) {
            val escapedUrl = StringUtil.escapeXmlEntities(githubUrl)
            "<br/><br/><a href=\"$escapedUrl\">${StringUtil.escapeXmlEntities(I18n.message(I18nKey.openOnGithub))}</a>"
        } else {
            ""
        }
        val retryHtml = if (!retryToken.isNullOrBlank()) {
            val encodedToken = URLEncoder.encode(retryToken, StandardCharsets.UTF_8)
            "<br/><a href=\"deplens:retry:$encodedToken\">${StringUtil.escapeXmlEntities(I18n.message(I18nKey.retry))}</a>"
        } else {
            ""
        }
        val htmlText = "<html>$escapedText$linkHtml$retryHtml</html>"
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
        shownRetryToken = retryToken
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
        shownRetryToken = null
        hintWasEntered = false
        clearPendingSwitch()
    }

    private fun clearPendingSwitch() {
        pendingSwitchSeq += 1
        pendingSwitchInlay = null
        pendingSwitchSince = 0L
        pendingSwitchEvent = null
    }

    /**
     * Schedules a delayed switch to a new inlay tooltip to prevent flickering
     * when the mouse briefly passes over another line.
     */
    private fun schedulePendingSwitch(event: EditorMouseEvent, inlay: Inlay<*>, now: Long) {
        pendingSwitchInlay = inlay
        pendingSwitchSince = now
        pendingSwitchEvent = event
        pendingSwitchSeq += 1
        val seq = pendingSwitchSeq

        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            {
                ApplicationManager.getApplication().invokeLater(
                    {
                        if (project.isDisposed) return@invokeLater
                        if (seq != pendingSwitchSeq) return@invokeLater

                        val switchEvent = pendingSwitchEvent ?: return@invokeLater
                        val targetInlay = pendingSwitchInlay ?: return@invokeLater
                        val pointer = MouseInfo.getPointerInfo()?.location ?: return@invokeLater
                        val editorPoint = Point(pointer.x, pointer.y)
                        SwingUtilities.convertPointFromScreen(editorPoint, switchEvent.editor.contentComponent)
                        val currentInlay = switchEvent.editor.inlayModel.getElementAt(editorPoint) ?: return@invokeLater
                        if (currentInlay != targetInlay) return@invokeLater

                        val renderer = currentInlay.renderer
                        if (!isDepLensDeclarativeRenderer(renderer)) return@invokeLater

                        val hoverText = extractPayloadValue(renderer, UiUtils.HOVER_TEXT_PAYLOAD_NAME)
                        if (hoverText.isNullOrBlank()) return@invokeLater
                        val githubUrl = extractPayloadValue(renderer, UiUtils.GITHUB_URL_PAYLOAD_NAME)
                        val retryToken = extractPayloadValue(renderer, UiUtils.RETRY_TOKEN_PAYLOAD_NAME)

                        clearPendingSwitch()
                        if (shownInlay == currentInlay &&
                            shownText == hoverText &&
                            shownGithubUrl == githubUrl &&
                            shownRetryToken == retryToken
                        ) {
                            return@invokeLater
                        }
                        showHint(switchEvent, currentInlay, hoverText, githubUrl, retryToken)
                    },
                    ModalityState.defaultModalityState()
                )
            },
            INLAY_SWITCH_DWELL_MILLIS,
            java.util.concurrent.TimeUnit.MILLISECONDS
        )
    }

    /**
     * Determines whether the tooltip should remain visible based on mouse position
     * relative to the inlay and the tooltip popup area.
     */
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

    // Access declarative renderer payloads by reflection to avoid binary coupling to internal classes.
    /**
     * Checks if the given renderer belongs to DepLens. 
     * Uses reflection to call `getProviderId()` on the renderer object.
     */
    private fun isDepLensDeclarativeRenderer(renderer: Any): Boolean {
        val providerId = invokeNoArg(renderer, "getProviderId") as? String ?: return false
        return providerId.startsWith(DEP_PROVIDER_PREFIX)
    }

    /**
     * Uses reflection to extract payload values from the internal declarative renderer classes,
     * avoiding direct binary coupling to internal IDE APIs.
     * Iterates through presentation lists, models, and payloads to find the matching payloadName.
     */
    private fun extractPayloadValue(renderer: Any, payloadName: String): String? {
        // Fetch presentation lists from the renderer
        val lists = invokeNoArg(renderer, "getPresentationLists") as? Iterable<*> ?: return null
        for (listItem in lists) {
            if (listItem == null) continue
            // Extract the model for each presentation list item
            val model = invokeNoArg(listItem, "getModel") ?: continue
            // Extract the payloads associated with the model
            val payloads = invokeNoArg(model, "getPayloads") as? Iterable<*> ?: continue
            for (payload in payloads) {
                if (payload == null) continue
                // Get the payload name to check if it matches the target
                val name = invokeNoArg(payload, "getPayloadName") as? String ?: continue
                if (name != payloadName) continue
                // Extract the actual payload object
                val payloadObj = invokeNoArg(payload, "getPayload") ?: continue
                if (payloadObj is StringInlayActionPayload) {
                    return payloadObj.text
                }
                // Fallback to calling getText() if not a StringInlayActionPayload
                val text = invokeNoArg(payloadObj, "getText") as? String
                if (!text.isNullOrBlank()) return text
            }
        }
        return null
    }

    /**
     * Helper function to safely invoke a no-argument method via reflection.
     * @param target The target object instance.
     * @param methodName The name of the method to invoke.
     * @return The result of the method invocation, or null if it fails or the method is not found.
     */
    private fun invokeNoArg(target: Any, methodName: String): Any? {
        return runCatching {
            target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }?.invoke(target)
        }.getOrNull()
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
