package com.alpdroid.app.terminal

/** A full terminal palette: background, default text color, the 16 ANSI colors (0-7 normal,
 *  8-15 bright), and the block-cursor color — each theme's own accent rather than a flat gray,
 *  so the cursor reads as a deliberate design choice instead of a leftover default.
 *
 *  IntArray breaks data-class equality (reference compare) — content-based here since theme
 *  switching and tests compare instances. */
data class TerminalTheme(val id: String, val label: String, val bg: Int, val fg: Int, val cursor: Int, val ansi16: IntArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TerminalTheme) return false
        return id == other.id && label == other.label && bg == other.bg && fg == other.fg &&
            cursor == other.cursor && ansi16.contentEquals(other.ansi16)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + bg
        result = 31 * result + fg
        result = 31 * result + cursor
        result = 31 * result + ansi16.contentHashCode()
        return result
    }
}

private fun c(hex: Long) = (0xFF000000 or hex).toInt()

object Themes {
    val ALPINE = TerminalTheme(
        "alpine", "Alpine (default)", c(0x0D1117), c(0xD4D4D4), c(0x3ED0B8),
        intArrayOf(
            c(0x1A1A1A), c(0xE05561), c(0x8CC265), c(0xD8A657), c(0x5A9BCF), c(0xC57BDB), c(0x5AB7B5), c(0xD4D4D4),
            c(0x6B6B6B), c(0xF07178), c(0xA6E27F), c(0xF0C674), c(0x7AB8E8), c(0xD79AEB), c(0x7ECFCE), c(0xFFFFFF),
        ),
    )
    val DRACULA = TerminalTheme(
        "dracula", "Dracula", c(0x282A36), c(0xF8F8F2), c(0xFF79C6),
        intArrayOf(
            c(0x21222C), c(0xFF5555), c(0x50FA7B), c(0xF1FA8C), c(0xBD93F9), c(0xFF79C6), c(0x8BE9FD), c(0xF8F8F2),
            c(0x6272A4), c(0xFF6E6E), c(0x69FF94), c(0xFFFFA5), c(0xD6ACFF), c(0xFF92DF), c(0xA4FFFF), c(0xFFFFFF),
        ),
    )
    val NORD = TerminalTheme(
        "nord", "Nord", c(0x2E3440), c(0xD8DEE9), c(0x88C0D0),
        intArrayOf(
            c(0x3B4252), c(0xBF616A), c(0xA3BE8C), c(0xEBCB8B), c(0x81A1C1), c(0xB48EAD), c(0x88C0D0), c(0xE5E9F0),
            c(0x4C566A), c(0xBF616A), c(0xA3BE8C), c(0xEBCB8B), c(0x81A1C1), c(0xB48EAD), c(0x8FBCBB), c(0xECEFF4),
        ),
    )
    val SOLARIZED_DARK = TerminalTheme(
        "solarized_dark", "Solarized Dark", c(0x002B36), c(0x839496), c(0x2AA198),
        intArrayOf(
            c(0x073642), c(0xDC322F), c(0x859900), c(0xB58900), c(0x268BD2), c(0xD33682), c(0x2AA198), c(0xEEE8D5),
            c(0x002B36), c(0xCB4B16), c(0x586E75), c(0x657B83), c(0x839496), c(0x6C71C4), c(0x93A1A1), c(0xFDF6E3),
        ),
    )
    val MONOKAI = TerminalTheme(
        "monokai", "Monokai", c(0x272822), c(0xF8F8F2), c(0xA6E22E),
        intArrayOf(
            c(0x272822), c(0xF92672), c(0xA6E22E), c(0xF4BF75), c(0x66D9EF), c(0xAE81FF), c(0xA1EFE4), c(0xF8F8F2),
            c(0x75715E), c(0xF92672), c(0xA6E22E), c(0xF4BF75), c(0x66D9EF), c(0xAE81FF), c(0xA1EFE4), c(0xF9F8F5),
        ),
    )
    val GRUVBOX = TerminalTheme(
        "gruvbox", "Gruvbox Dark", c(0x282828), c(0xEBDBB2), c(0xFABD2F),
        intArrayOf(
            c(0x282828), c(0xCC241D), c(0x98971A), c(0xD79921), c(0x458588), c(0xB16286), c(0x689D6A), c(0xA89984),
            c(0x928374), c(0xFB4934), c(0xB8BB26), c(0xFABD2F), c(0x83A598), c(0xD3869B), c(0x8EC07C), c(0xEBDBB2),
        ),
    )

    val ALL = listOf(ALPINE, DRACULA, NORD, SOLARIZED_DARK, MONOKAI, GRUVBOX)

    fun byId(id: String): TerminalTheme = ALL.firstOrNull { it.id == id } ?: ALPINE
}
