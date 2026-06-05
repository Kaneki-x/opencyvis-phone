package ai.opencyvis.engine

import ai.opencyvis.action.Action
import org.junit.Assert.*
import org.junit.Test

class ActionRepeatGuardTest {

    private val sameScreen = ScreenFingerprint(0x0f0f0f0f0f0f0f0fL)
    private val changedScreen = ScreenFingerprint(-1L)

    @Test
    fun `repeated identical type_text is blocked`() {
        val guard = ActionRepeatGuard()
        guard.recordExecuted(Action.TypeText("京东"), sameScreen)

        val decision = guard.evaluate(Action.TypeText("京东"), sameScreen)

        assertTrue(decision is ActionRepeatGuard.Decision.Block)
        val feedback = (decision as ActionRepeatGuard.Decision.Block).feedback
        assertTrue(feedback.isNotEmpty())
        assertTrue(feedback.contains("ask_user"))
    }

    @Test
    fun `same type_text is allowed after an intervening tap changes focus context`() {
        val guard = ActionRepeatGuard()
        guard.recordExecuted(Action.TypeText("京东"), sameScreen)
        guard.recordExecuted(Action.Tap(500, 250), sameScreen)

        val decision = guard.evaluate(Action.TypeText("京东"), sameScreen)

        assertTrue(decision is ActionRepeatGuard.Decision.Allow)
    }

    @Test
    fun `wait does not reset repeated type_text protection`() {
        val guard = ActionRepeatGuard()
        guard.recordExecuted(Action.TypeText("京东"), sameScreen)
        guard.recordExecuted(Action.Wait(), sameScreen)

        val decision = guard.evaluate(Action.TypeText("京东"), sameScreen)

        assertTrue(decision is ActionRepeatGuard.Decision.Block)
    }

    @Test
    fun `repeated bottom tap searches up then down then blocks`() {
        val guard = ActionRepeatGuard(tapTolerance = 35, maxTapNudges = 2, nudgeStep = 28)
        // Bottom-right miss (like WeChat 发送): the model may miss above OR below,
        // so the search goes toward centre first, then past the anchor downward.
        guard.recordExecuted(Action.Tap(900, 900), sameScreen)

        val first = guard.evaluate(Action.Tap(905, 905), sameScreen)
        assertTrue(first is ActionRepeatGuard.Decision.Allow)
        val nudged1 = (first as ActionRepeatGuard.Decision.Allow).action as Action.Tap
        assertTrue("first nudge moves up toward centre", nudged1.y < 900)
        assertTrue("x is pulled in from the right edge", nudged1.x < 900)
        guard.recordExecuted(nudged1, sameScreen)

        val second = guard.evaluate(Action.Tap(905, 905), sameScreen)
        assertTrue(second is ActionRepeatGuard.Decision.Allow)
        val nudged2 = (second as ActionRepeatGuard.Decision.Allow).action as Action.Tap
        assertTrue("second nudge searches the other side (below the anchor)", nudged2.y > 900)
        guard.recordExecuted(nudged2, sameScreen)

        val third = guard.evaluate(Action.Tap(905, 905), sameScreen)
        assertTrue(third is ActionRepeatGuard.Decision.Block)
        assertTrue((third as ActionRepeatGuard.Decision.Block).feedback.contains("ask_user"))
    }

    @Test
    fun `downward nudge never enters the bottom gesture strip`() {
        val guard = ActionRepeatGuard(tapTolerance = 35, maxTapNudges = 4, nudgeStep = 28, safeYMax = 935)
        guard.recordExecuted(Action.Tap(900, 950), sameScreen)
        repeat(4) {
            val d = guard.evaluate(Action.Tap(900, 950), sameScreen)
            assertTrue(d is ActionRepeatGuard.Decision.Allow)
            val t = (d as ActionRepeatGuard.Decision.Allow).action as Action.Tap
            assertTrue("nudged y must stay out of the bottom strip", t.y <= 935)
            guard.recordExecuted(t, sameScreen)
        }
    }

    @Test
    fun `nearby repeated tap is allowed unchanged when screen changed`() {
        val guard = ActionRepeatGuard(tapTolerance = 35)
        guard.recordExecuted(Action.Tap(500, 600), sameScreen)

        val decision = guard.evaluate(Action.Tap(520, 590), changedScreen)

        assertTrue(decision is ActionRepeatGuard.Decision.Allow)
        // A changed screen means progress, so the candidate is executed as-is.
        val allowed = (decision as ActionRepeatGuard.Decision.Allow).action as Action.Tap
        assertTrue(allowed.x == 520 && allowed.y == 590)
    }

    @Test
    fun `distant repeated tap is allowed even when screen is unchanged`() {
        val guard = ActionRepeatGuard(tapTolerance = 35)
        guard.recordExecuted(Action.Tap(100, 100), sameScreen)

        val decision = guard.evaluate(Action.Tap(800, 800), sameScreen)

        assertTrue(decision is ActionRepeatGuard.Decision.Allow)
    }

    @Test
    fun `repeated enter is blocked when screen is unchanged`() {
        val guard = ActionRepeatGuard()
        guard.recordExecuted(Action.KeyEvent("enter"), sameScreen)

        val decision = guard.evaluate(Action.KeyEvent("enter"), sameScreen)

        assertTrue(decision is ActionRepeatGuard.Decision.Block)
    }

    @Test
    fun `non-submit key event is allowed when repeated`() {
        val guard = ActionRepeatGuard()
        guard.recordExecuted(Action.KeyEvent("back"), sameScreen)

        val decision = guard.evaluate(Action.KeyEvent("back"), sameScreen)

        assertTrue(decision is ActionRepeatGuard.Decision.Allow)
    }

    @Test
    fun `open_app wait and swipe are allowed by repeat guard`() {
        val guard = ActionRepeatGuard()

        guard.recordExecuted(Action.OpenApp("应用宝"), sameScreen)
        assertTrue(guard.evaluate(Action.OpenApp("应用宝"), sameScreen) is ActionRepeatGuard.Decision.Allow)

        guard.recordExecuted(Action.Wait(), sameScreen)
        assertTrue(guard.evaluate(Action.Wait(), sameScreen) is ActionRepeatGuard.Decision.Allow)

        guard.recordExecuted(Action.Swipe("up"), sameScreen)
        assertTrue(guard.evaluate(Action.Swipe("up"), sameScreen) is ActionRepeatGuard.Decision.Allow)
    }

    @Test
    fun `wait as candidate returns allow immediately without updating guard state`() {
        val guard = ActionRepeatGuard()
        guard.recordExecuted(Action.Tap(500, 600), sameScreen)

        // Wait should always be allowed
        val decision = guard.evaluate(Action.Wait(), sameScreen)
        assertTrue(decision is ActionRepeatGuard.Decision.Allow)
    }

    @Test
    fun `repeated tap after intervening wait is still recognized as a repeat`() {
        // Scenario: tap(500,600) → wait → tap(500,600)
        // The wait must not reset the guard's memory of the previous tap, so the
        // second tap at the same position is still treated as a repeat (and thus
        // nudged rather than executed verbatim).
        val guard = ActionRepeatGuard(tapTolerance = 35)
        guard.recordExecuted(Action.Tap(500, 600), sameScreen)

        // Intervening wait — evaluate returns Allow, recordExecuted skips it
        assertTrue(guard.evaluate(Action.Wait(), sameScreen) is ActionRepeatGuard.Decision.Allow)
        guard.recordExecuted(Action.Wait(), sameScreen)

        // Same-position tap is recognized as a repeat: it is nudged off the anchor,
        // proving the wait did not wipe the guard's memory.
        val decision = guard.evaluate(Action.Tap(510, 595), sameScreen)
        assertTrue(decision is ActionRepeatGuard.Decision.Allow)
        val nudged = (decision as ActionRepeatGuard.Decision.Allow).action as Action.Tap
        assertTrue(nudged.x != 510 || nudged.y != 595)
    }

    @Test
    fun `screen fingerprints tolerate small hamming differences`() {
        val base = ScreenFingerprint(0b101010L)
        val oneBitDifferent = ScreenFingerprint(0b101011L)
        val manyBitsDifferent = ScreenFingerprint(-1L)

        assertTrue(base.isSimilarTo(oneBitDifferent))
        assertFalse(base.isSimilarTo(manyBitsDifferent))
    }
}
