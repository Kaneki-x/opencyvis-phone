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
 * such near-misses automatically, the guard now nudges the retry toward the screen
 * centre a few times (walking it off the edge / onto the button interior) before
 * giving up and escalating to the model.
 */
class ActionRepeatGuard(
    private val tapTolerance: Int = 35,
    private val maxTapNudges: Int = 3,
    private val nudgeStep: Int = 28
) {

    sealed class Decision {
        /** Execute [action]; it may be the original candidate or a nudged retry. */
        data class Allow(val action: Action) : Decision()
        data class Block(val feedback: String) : Decision()
    }

    private var lastExecutedAction: Action? = null
    private var lastScreenBeforeAction: ScreenFingerprint? = null
    private var consecutiveBlocks: Int = 0

    // Anchor = the first tap that missed; nudges walk away from it toward centre.
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
                    val nudged = nudgeTowardCenter(candidate, anchor, tapNudgeCount)
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
     * Nudge a tap from [anchor] toward the screen centre by an escalating offset.
     * Coordinates are normalized (0..1000), so the centre is 500. The walk never
     * crosses the centre on either axis.
     */
    private fun nudgeTowardCenter(action: Action, anchor: Pair<Int, Int>, attempt: Int): Action? {
        val offset = nudgeStep * attempt
        val nx = nudgeAxis(anchor.first, offset)
        val ny = nudgeAxis(anchor.second, offset)
        if (nx == anchor.first && ny == anchor.second) return null
        return when (action) {
            is Action.Tap -> action.copy(x = nx, y = ny)
            is Action.LongPress -> action.copy(x = nx, y = ny)
            else -> null
        }
    }

    private fun nudgeAxis(value: Int, offset: Int): Int {
        val center = 500
        return when {
            value > center -> (value - offset).coerceIn(center, 1000)
            value < center -> (value + offset).coerceIn(0, center)
            else -> value
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
