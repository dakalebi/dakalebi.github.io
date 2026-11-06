package ge.dakalebi.next.foundations

import androidx.compose.runtime.Composable
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Article
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul

/**
 * The design system's foundations: the type roles, the colour roles and one surface,
 * drawn with the tokens of the shell it is on. The gallery's Base tab.
 *
 * It is how a shell's token overrides are checked by eye: the same markup is larger on
 * `/next/drive/` and scaled to the panel on `/next/tv/`. The words come from `S`, so they
 * follow the language like the app does.
 *
 * Token names are read in a loop, so this sets `var(--...)` inline instead of one class
 * per token. Components never do that: they have classes in their own sheet.
 */
@Composable
fun FoundationsGrid() {
    Div({ classes("fd-grid") }) {
        Section({ classes("fd-section") }) {
            H2({ classes("fd-section-title") }) { Text("Type") }
            TypeRamp()
        }
        Div({ classes("fd-column") }) {
            Section({ classes("fd-section") }) {
                H2({ classes("fd-section-title") }) { Text("Colour") }
                Swatches()
            }
            Section({ classes("fd-section") }) {
                H2({ classes("fd-section-title") }) { Text("Surface") }
                SampleCard()
            }
        }
    }
}

/**
 * One type role and the words it is shown with. The roles the brief marks "caps" get
 * their sample through `caps`, which is Mtavruli in Georgian and unchanged in English.
 */
private class Role(val name: String, val sample: String)

@Composable
private fun TypeRamp() {
    val roles = listOf(
        Role("display", S.appName.caps),
        Role("headline", S.seasonAndEpisode(4, 12).caps),
        Role("title", S.seriesTitle.caps),
        Role("section", S.seasons.caps),
        Role("label", S.resume.caps),
        Role("label-s", S.home),
        Role("item", S.episode(21)),
        Role("body", S.guestProgressNote),
        Role("body-s", S.waitForAdmin),
        Role("meta", S.minutesLeft(16, "27:14", "43:50")),
    )

    Ul({ classes("fd-ramp") }) {
        roles.forEach { role ->
            Li({ classes("fd-row") }) {
                Span({ classes("fd-role") }) { Text(role.name) }
                Span({
                    classes("fd-sample")
                    style {
                        property("font", "var(--type-${role.name})")
                        property("font-stretch", "var(--type-${role.name}-stretch)")
                        property("letter-spacing", "var(--type-${role.name}-tracking)")
                    }
                }) { Text(role.sample) }
            }
        }

        // The four figure sizes share one row: they are one family at one width.
        Li({ classes("fd-row") }) {
            Span({ classes("fd-role") }) { Text("figure") }
            Span({ classes("fd-figures") }) {
                listOf("xl" to "04", "l" to "42:17", "m" to "87%", "s" to "12/24").forEach { (size, sample) ->
                    Span({
                        style {
                            property("font", "var(--type-figure-$size)")
                            property("font-stretch", "var(--type-figure-stretch)")
                            property("font-variant-numeric", "var(--type-figure-numeric)")
                        }
                    }) { Text(sample) }
                }
            }
        }
    }
}

/** The semantic colours (layer 2), grounds first. */
private val COLOURS = listOf(
    "stage", "bg-deep", "bg", "surface-1", "surface-2", "surface-3",
    "line", "line-strong", "text", "text-2", "text-3", "accent",
    "accent-soft", "track", "success", "danger",
)

@Composable
private fun Swatches() {
    Ul({ classes("fd-swatches") }) {
        COLOURS.forEach { name ->
            Li({ classes("fd-swatch") }) {
                Span({
                    classes("fd-chip")
                    style { property("background", "var(--color-$name)") }
                })
                Span({ classes("fd-swatch-name") }) { Text(name) }
            }
        }
    }
}

/** A surface with a thumbnail, a title and progress: the roles working together. */
@Composable
private fun SampleCard() {
    val watched = 62

    Article({ classes("fd-card") }) {
        Div({ classes("fd-thumb") }) {
            Span({ classes("fd-thumb-figure") }) { Text("12") }
        }
        Div({ classes("fd-card-body") }) {
            P({ classes("fd-card-eyebrow") }) { Text(S.lastWatched) }
            P({ classes("fd-card-title") }) { Text(S.seasonAndEpisode(4, 12).caps) }
            Div({
                classes("fd-progress")
                attr("role", "progressbar")
                attr("aria-valuemin", "0")
                attr("aria-valuemax", "100")
                attr("aria-valuenow", "$watched")
                attr("aria-label", S.statProgress)
            }) {
                Div({
                    classes("fd-progress-fill")
                    style { width(watched.percent) }
                })
            }
            P({ classes("fd-card-meta") }) { Text(S.minutesLeft(16, "27:14", "43:50")) }
        }
    }
}
