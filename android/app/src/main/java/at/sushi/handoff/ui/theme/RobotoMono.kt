package at.sushi.handoff.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import at.sushi.handoff.R

/** The design reference specifies Roboto Mono for every frequency/squawk-code/technical readout
 *  -- distinct from Android's generic `FontFamily.Monospace` (a system fallback, historically
 *  Droid Sans Mono derived), which critically renders zero identical to the letter O. Roboto
 *  Mono's zero has a distinguishing dot, which is the entire point of choosing a monospace font
 *  for frequencies/codes in the first place -- ambiguous 0/O at a glance is exactly the failure
 *  mode a cockpit readout can't have. Bundled as real font files (res/font/), since Roboto Mono
 *  is not preinstalled on Android the way Roboto proper is. */
val RobotoMono = FontFamily(
    Font(R.font.roboto_mono_regular, FontWeight.Normal),
    Font(R.font.roboto_mono_medium, FontWeight.Medium),
    Font(R.font.roboto_mono_bold, FontWeight.Bold)
)
