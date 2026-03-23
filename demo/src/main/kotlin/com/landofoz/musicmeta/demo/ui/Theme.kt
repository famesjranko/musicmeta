package com.landofoz.musicmeta.demo.ui

/**
 * Visual theme defining colors, symbols, and spinner frames.
 * Swap [Default] for [Plain] to disable ANSI codes (piped output, CI, etc.).
 */
data class Theme(
    val primary: String,
    val success: String,
    val warning: String,
    val error: String,
    val muted: String,
    val accent: String,
    val info: String,
    val bold: String,
    val reset: String,
    val check: String,
    val cross: String,
    val dot: String,
    val warn: String,
    val arrow: String,
    val bullet: String,
    val boxTL: String, val boxTR: String,
    val boxBL: String, val boxBR: String,
    val boxH: String, val boxV: String,
    val headingBar: String,
    val thinBar: String,
    val spinnerFrames: List<String>,
) {
    companion object {
        val Default = Theme(
            primary = "\u001b[36m",    // cyan
            success = "\u001b[32m",    // green
            warning = "\u001b[33m",    // yellow
            error = "\u001b[31m",      // red
            muted = "\u001b[2m",       // dim
            accent = "\u001b[35m",     // magenta
            info = "\u001b[34m",       // blue
            bold = "\u001b[1m",        // bold
            reset = "\u001b[0m",       // reset
            check = "\u2713", cross = "\u2717", dot = "\u00b7",
            warn = "\u25b2", arrow = "\u203a", bullet = "\u2022",
            boxTL = "\u2554", boxTR = "\u2557",
            boxBL = "\u255a", boxBR = "\u255d",
            boxH = "\u2550", boxV = "\u2551",
            headingBar = "\u2550",
            thinBar = "\u2500",
            spinnerFrames = listOf(
                "\u280b", "\u2819", "\u2839", "\u2838",
                "\u283c", "\u2834", "\u2826", "\u2827",
                "\u2807", "\u280f",
            ),
        )

        val Plain = Theme(
            primary = "", success = "", warning = "", error = "",
            muted = "", accent = "", info = "", bold = "", reset = "",
            check = "+", cross = "x", dot = ".", warn = "!",
            arrow = ">", bullet = "*",
            boxTL = "+", boxTR = "+", boxBL = "+", boxBR = "+",
            boxH = "=", boxV = "|", headingBar = "=", thinBar = "-",
            spinnerFrames = listOf("|", "/", "-", "\\"),
        )

        fun detect(): Theme {
            // System.console() is null when launched by Gradle even with a real terminal.
            // Fall back to checking TERM env var and explicit NO_COLOR convention.
            if (System.getenv("NO_COLOR") != null) return Plain
            val term = System.getenv("TERM")
            return if (term != null && term != "dumb") Default else Plain
        }
    }
}
