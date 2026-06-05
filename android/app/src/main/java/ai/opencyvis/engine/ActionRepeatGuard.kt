package ai.opencyvis.engine

import ai.opencyvis.action.Action
import kotlin.math.abs

/**
 * Blocks repeated high-risk actions when the screen appears unchanged.
 *
 * The guard is intentionally generic. It does not know about app-specific labels
 * like "京东" or "安装"; it only protects non-idempotent action patterns such as
 * repeated text entry, repeated submit, and repeated same-location taps.
 *
 * For repeated taps, a single off-target tap on a small button (e.g. WeChat's
 * bottom-right "发送") used to be treated identically to a genuinely stuck loop:
 * the next near-identical retry was hard-blocked, wasting several steps until the
 * model happened to jitter the coordinate far enough on its own. To recover from
 * such near-misses automatically, the guard nudges the retry around the anchor
 * with a small bidirectional vertical search (first toward the screen centre, then
 * past the anchor the other way) before giving up and escalating to the model.
 * The model misses small buttons either above or below, so a single-direction
 * nudge can push an already-too-high tap even further off; searching both sides
 * fixes that. Downward nudges are capped at [safeYMax] so a bottom-button retry
 * never drops into the system gesture/navigation strip (which can trigger Recents).
 */
class ActionRepeatGuard(
    private val tapTolerance: Int = 35,
    private val maxTapNudges: Int = 3,
    private val nudgeStep: Int = 28,
    private val safeYMax: Int = 935
) {

    sealed class Decision {
        /** Execute [action]; it may be the original candidate or a nudged retry. */
        data class Allow(val action: Action) : Decision()
        data class Block(val feedback: String) : Decision()
    }

    private var lastExecutedAction: Action? = null
    private var lastScreenBeforeAction: ScreenFingerprint? = null
    private var consecutiveBlocks: Int = 0

    // Anchor = the first tap that missed; nudges search vertically around it.
    private var tapAnchor: Pair<Int, Int>? = null
    private var tapNudgeCount: Int = 0

    fun evaluate(candidate: Action, currentScreen: ScreenFingerprint?): Decision {
        if (candidate is Action.Wait) return Decision.Allow(candidate)
        val previous = lastExecutedAction ?: return allowOriginal(candidate)

        if (isRepeatedTypeText(previous, candidate)) {
            return block(LlmPrompts.guardFeedback("repeated_type_text"))
        }
        if (isRepeatedSubmit(previous, candidate) && screenLooksUnchanged(currentScreen)) {
            return block(LlmPrompts.guardFeedback("repeated_submit"))
        }

        val candidatePoint = tapPoint(candidate)
        if (candidatePoint != null && screenLooksUnchanged(currentScreen)) {
            val reference = tapAnchor ?: tapPoint(previous)
            if (reference != null && near(candidatePoint, reference)) {
                if (tapNudgeCount < maxTapNudges) {
                    val anchor = tapAnchor ?: reference.also { tapAnchor = it }
                    tapNudgeCount += 1
                    consecutiveBlocks = 0
                    val nudged = nudgeForRetry(candidate, anchor, tapNudgeCount)
                        ?: return block(LlmPrompts.guardFeedback("repeated_tap"))
                    return Decision.Allow(nudged)
                }
                return block(LlmPrompts.guardFeedback("repeated_tap"))
            }
        }

        return allowOriginal(candidate)
    }

    fun recordExecuted(action: Action, screenBeforeAction: ScreenFingerprint?) {
        if (action is Action.Wait) {
            return
        }
        lastExecutedAction = action
        lastScreenBeforeAction = screenBeforeAction
        consecutiveBlocks = 0
    }

    private fun allowOriginal(candidate: Action): Decision {
        tapAnchor = null
        tapNudgeCount = 0
        return Decision.Allow(candidate)
    }

    private fun block(reason: String): Decision {
        consecutiveBlocks += 1
        return Decision.Block(buildFeedback(reason))
    }

    private fun screenLooksUnchanged(currentScreen: ScreenFingerprint?): Boolean {
        val previousScreen = lastScreenBeforeAction ?: return false
        return currentScreen?.isSimilarTo(previousScreen) == true
    }

    private fun isRepeatedTypeText(previous: Action, candidate: Action): Boolean {
        return previous is Action.TypeText &&
                candidate is Action.TypeText &&
                previous.text == candidate.text
    }

    private fun isRepeatedSubmit(previous: Action, candidate: Action): Boolean {
        return previous is Action.KeyEvent &&
                candidate is Action.KeyEvent &&
                previous.key.equals("enter", ignoreCase = true) &&
                candidate.key.equals("enter", ignoreCase = true)
    }

    private fun near(a: Pair<Int, Int>, b: Pair<Int, Int>): Boolean {
        return abs(a.first - b.first) <= tapTolerance &&
                abs(a.second - b.second) <= tapTolerance
    }

    private fun tapPoint(action: Action): Pair<Int, Int>? {
        return when (action) {
            is Action.Tap -> action.x to action.y
            is Action.LongPress -> action.x to action.y
            else -> null
        }
    }

    /**
     * Nudge a tap around [anchor] with a bidirectional vertical search.
     *
     * Coordinates are normalized (0..1000), centre is 500. The vertical offset
     * grows every two attempts and alternates side: toward centre on odd attempts,
     * past the anchor on even ones (-1, +1, -2, +2, … × [nudgeStep]). This covers
     * misses both above and below a small button. The y result is capped at
     * [safeYMax] so a downward retry on a bottom button never enters the gesture
     * strip. The x is nudged once toward centre to clear the side edge, then held,
     * keeping the search essentially vertical.
     */
    private fun nudgeForRetry(action: Action, anchor: Pair<Int, Int>, attempt: Int): Action? {
        val center = 500
        val magnitude = nudgeStep * ((attempt + 1) / 2)
        val towardCenter = attempt % 2 == 1
        val baseDirY = if (anchor.second >= center) -1 else 1
        val dirY = if (towardCenter) baseDirY else -baseDirY
        val ny = (anchor.second + dirY * magnitude).coerceIn(0, safeYMax)

        val dirX = when {
            anchor.first > center -> -1
            anchor.first < center -> 1
            else -> 0
        }
        val nx = (anchor.first + dirX * nudgeStep).coerceIn(0, 1000)

        if (nx == anchor.first && ny == anchor.second) return null
        return when (action) {
            is Action.Tap -> action.copy(x = nx, y = ny)
            is Action.LongPress -> action.copy(x = nx, y = ny)
            else -> null
        }
    }

    private fun buildFeedback(reason: String): String {
        val escalation = if (consecutiveBlocks >= 2) {
            LlmPrompts.guardFeedback("escalation_high")
        } else {
            LlmPrompts.guardFeedback("escalation_low")
        }
        return "$reason$escalation"
    }
}
