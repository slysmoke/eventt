package org.eventt.core.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HotkeyComboTest {
    @Test
    fun `parse reads any modifier mix and serialize round-trips it`() {
        val combo = HotkeyCombo.parse("CTRL+ALT+Z")
        combo shouldBe HotkeyCombo(ctrl = true, alt = true, shift = false, letter = 'Z')
        combo!!.label shouldBe "Ctrl+Alt+Z"
        HotkeyCombo.parse(combo.serialize()) shouldBe combo
    }

    @Test
    fun `parse treats a legacy bare letter as Ctrl plus that letter`() {
        HotkeyCombo.parse("m") shouldBe HotkeyCombo(ctrl = true, alt = false, shift = false, letter = 'M')
    }

    @Test
    fun `parse rejects malformed input`() {
        HotkeyCombo.parse(null) shouldBe null
        HotkeyCombo.parse("") shouldBe null
        HotkeyCombo.parse("CTRL+1") shouldBe null
        HotkeyCombo.parse("SUPER+Z") shouldBe null
    }
}
