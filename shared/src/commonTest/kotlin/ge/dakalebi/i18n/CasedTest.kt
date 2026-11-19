package ge.dakalebi.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CasedTest {

    @Test
    fun georgian_text_is_set_in_mtavruli() {
        assertEquals("ᲥᲐᲠᲗᲣᲚᲘ", Ka.cased("ქართული"))
        assertEquals("ᲩᲔᲛᲘ ᲪᲝᲚᲘᲡ ᲓᲐᲥᲐᲚᲔᲑᲘ", Ka.cased("ჩემი ცოლის დაქალები"))
    }

    @Test
    fun the_whole_mkhedruli_alphabet_maps_to_the_letter_cased_by_the_platform() {
        val letters = ('ა'..'ჺ') + ('ჽ'..'ჿ')
        for (c in letters) {
            assertEquals(c.toString().uppercase(), Ka.cased(c.toString()), "U+${c.code.toString(16)}")
        }
    }

    @Test
    fun latin_digits_and_punctuation_keep_their_case() {
        assertEquals("Google-ᲘᲗ ᲨᲔᲡᲕᲚᲐ", Ka.cased("Google-ით შესვლა"))
        assertEquals("E12 ⋅ 3:05 ⋅ a1b2c3d", Ka.cased("E12 ⋅ 3:05 ⋅ a1b2c3d"))
        assertEquals("someone@example.com", Ka.cased("someone@example.com"))
    }

    @Test
    fun georgian_marks_without_a_capital_are_left_alone() {
        assertEquals("჻ჼ", Ka.cased("჻ჼ"))
    }

    @Test
    fun text_with_no_georgian_is_returned_as_is() {
        val text = "Season 3"
        assertSame(text, Ka.cased(text))
    }

    @Test
    fun english_leaves_every_text_alone() {
        assertEquals("ქართული and English", En.cased("ქართული and English"))
    }

    @Test
    fun chrome_caps_still_uppercases_latin_too() {
        assertEquals("GOOGLE-ᲘᲗ ᲨᲔᲡᲕᲚᲐ", Ka.caps("Google-ით შესვლა"))
    }
}
