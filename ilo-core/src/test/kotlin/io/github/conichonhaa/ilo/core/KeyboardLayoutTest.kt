package io.github.conichonhaa.ilo.core

import io.github.conichonhaa.ilo.core.input.Hid
import io.github.conichonhaa.ilo.core.input.KeyStroke
import io.github.conichonhaa.ilo.core.input.KeyboardLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardLayoutTest {
    @Test
    fun usLayout() {
        val us = KeyboardLayout.US
        assertEquals(KeyStroke(Hid.A), us.strokeFor('a'))
        assertEquals(KeyStroke(Hid.A, shift = true), us.strokeFor('A'))
        assertEquals(KeyStroke(Hid.DIGIT_1 + 1, shift = true), us.strokeFor('@'))
        assertEquals(KeyStroke(Hid.DIGIT_0), us.strokeFor('0'))
        assertEquals(KeyStroke(Hid.SLASH, shift = true), us.strokeFor('?'))
    }

    @Test
    fun frenchAzerty() {
        val fr = KeyboardLayout.FR
        assertEquals(KeyStroke(Hid.letter('q')), fr.strokeFor('a'))
        assertEquals(KeyStroke(Hid.letter('a')), fr.strokeFor('q'))
        assertEquals(KeyStroke(Hid.SEMICOLON), fr.strokeFor('m'))
        assertEquals(KeyStroke(Hid.letter('m')), fr.strokeFor(','))
        assertEquals(KeyStroke(Hid.DIGIT_1, shift = true), fr.strokeFor('1'))
        assertEquals(KeyStroke(Hid.DIGIT_1 + 1), fr.strokeFor('é'))
        assertEquals(KeyStroke(Hid.DIGIT_0, altGr = true), fr.strokeFor('@'))
        assertEquals(KeyStroke(Hid.COMMA, shift = true), fr.strokeFor('.'))
        assertEquals(KeyStroke(Hid.SLASH), fr.strokeFor('!'))
        assertEquals(Hid.letter('q'), fr.letterUsage('a'))
    }
}
