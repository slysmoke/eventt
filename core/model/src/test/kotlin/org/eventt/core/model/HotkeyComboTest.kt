package org.eventt.core.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HotkeyComboTest {
    @Test
    fun `parse reads any modifier mix and serialize round-trips it`() {
        val combo = HotkeyCombo.parse("CTRL+ALT+Z")
        combo shouldBe HotkeyCombo(ctrl = true, alt = true, shift = false, key = "Z")
        combo!!.label shouldBe "Ctrl+Alt+Z"
        HotkeyCombo.parse(combo.serialize()) shouldBe combo
    }

    @Test
    fun `parse treats a legacy bare letter as Ctrl plus that letter`() {
        HotkeyCombo.parse("m") shouldBe HotkeyCombo(ctrl = true, alt = false, shift = false, key = "M")
    }

    @Test
    fun `parse reads function keys and serialize round-trips them`() {
        val combo = HotkeyCombo.parse("CTRL+F11")
        combo shouldBe HotkeyCombo(ctrl = true, alt = false, shift = false, key = "F11")
        combo!!.label shouldBe "Ctrl+F11"
        HotkeyCombo.parse(combo.serialize()) shouldBe combo
        HotkeyCombo.parse("ctrl+shift+f1") shouldBe HotkeyCombo(ctrl = true, alt = false, shift = true, key = "F1")
    }

    @Test
    fun `parse rejects malformed input`() {
        HotkeyCombo.parse(null) shouldBe null
        HotkeyCombo.parse("") shouldBe null
        HotkeyCombo.parse("CTRL+1") shouldBe null
        HotkeyCombo.parse("SUPER+Z") shouldBe null
        HotkeyCombo.parse("CTRL+F13") shouldBe null
        HotkeyCombo.parse("CTRL+F0") shouldBe null
        HotkeyCombo.parse("CTRL+ZZ") shouldBe null
    }
}
