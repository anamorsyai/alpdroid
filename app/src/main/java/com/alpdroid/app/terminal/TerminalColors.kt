package com.alpdroid.app.terminal

/**
 * Global, mutable current palette. A single process-wide terminal doesn't need per-instance
 * theming, so [applyTheme] just swaps these fields in place — every call site here and in
 * [TerminalEmulator]/[TerminalView] reads them fresh each time rather than caching a value, so
 * a theme change takes effect immediately for anything drawn or reset after the switch.
 */
object TerminalColors {
    var DEFAULT_FG = Themes.ALPINE.fg
        private set
    var DEFAULT_BG = Themes.ALPINE.bg
        private set
    var CURSOR = Themes.ALPINE.cursor
        private set

    /** Standard 16-color ANSI palette (0-7 normal, 8-15 bright) for the current theme. */
    var ANSI16 = Themes.ALPINE.ansi16
        private set

    fun applyTheme(theme: TerminalTheme) {
        DEFAULT_FG = theme.fg
        DEFAULT_BG = theme.bg
        CURSOR = theme.cursor
        ANSI16 = theme.ansi16
    }

    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    /** xterm's 256-color cube: 0-15 the ANSI16 above, 16-231 a 6x6x6 RGB cube, 232-255 a gray ramp. */
    fun ansi256(n: Int): Int {
        return when {
            n < 16 -> ANSI16[n]
            n < 232 -> {
                val i = n - 16
                val r = i / 36
                val g = (i / 6) % 6
                val b = i % 6
                fun lvl(x: Int) = if (x == 0) 0 else 55 + x * 40
                rgb(lvl(r), lvl(g), lvl(b))
            }
            else -> {
                val gray = 8 + (n - 232) * 10
                rgb(gray, gray, gray)
            }
        }
    }
}
