package ge.dakalebi.web.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ge.dakalebi.core.BuildInfo
import ge.dakalebi.core.formatDateTime
import ge.dakalebi.di.catalog
import ge.dakalebi.di.session
import ge.dakalebi.di.settings
import ge.dakalebi.di.toasts
import ge.dakalebi.i18n.I18n
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.web.openExternal
import ge.dakalebi.web.ui.Eyebrow
import ge.dakalebi.web.ui.ProgressBar
import ge.dakalebi.web.ui.Scrim
import ge.dakalebi.web.ui.clickableSurface
import ge.dakalebi.web.ui.Tokens

/**
 * The account panel: what you have watched, the destructive actions, and the settings.
 *
 * A centred panel rather than the DOM app's bottom sheet. A sheet is a phone gesture — it exists
 * to be swiped — and neither the gesture nor the edge it hangs off means anything on a canvas that
 * fills a desktop window.
 */
@Composable
fun MenuSheet(
    onClose: () -> Unit,
    onResetAll: () -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    busy: Boolean,
) {
    val session = session()
    val catalog = catalog()
    val stats = catalog.stats

    Scrim(onDismiss = onClose) {
        Column(
            Modifier
                .width(420.dp)
                .heightIn(max = 620.dp)
                .clip(Tokens.radius)
                .background(Tokens.elev)
                .border(1.dp, Tokens.lineStrong, Tokens.radius)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Eyebrow(S.menu.caps)
                    Spacer(Modifier.height(3.dp))
                    Text(session.email ?: "", color = Tokens.txDim, fontSize = 12.5.sp)
                }
                ge.dakalebi.web.ui.IconButton(
                    icon = ge.dakalebi.web.ui.AppIcons.close,
                    label = S.cancel,
                    onClick = onClose,
                )
            }

            Spacer(Modifier.height(18.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("${stats.watched}/${stats.total}", S.statWatched.caps, Modifier.weight(1f))
                Stat("${stats.started}", S.statStarted.caps, Modifier.weight(1f))
                Stat("${stats.percent}%", S.statProgress.caps, Modifier.weight(1f))
            }

            Spacer(Modifier.height(12.dp))
            ProgressBar(stats.percent.toDouble(), watched = false, height = 4)

            Spacer(Modifier.height(18.dp))

            // Offered only to admins, because only they may write the episodes collection.
            if (session.isAdmin) {
                SheetItem(
                    label = if (catalog.refreshing) catalog.refreshNote ?: S.refreshing
                    else S.refreshEpisodes.caps,
                    enabled = !catalog.refreshing,
                    onClick = onRefresh,
                )
            }
            SheetItem(S.resetAllProgress.caps, enabled = !busy, destructive = true, onClick = onResetAll)
            SheetItem(S.signOut.caps, onClick = onSignOut)

            Spacer(Modifier.height(20.dp))
            SettingsSection()

            Spacer(Modifier.height(18.dp))
            Text(
                text = S.lastRefreshed(formatDateTime(catalog.meta?.lastRefreshAtMillis)),
                color = Tokens.mut,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(4.dp))
            BuildStamp()
        }
    }
}

@Composable
private fun SettingsSection() {
    val settings = settings()
    val toasts = toasts()
    val scope = rememberCoroutineScope()

    Eyebrow(S.settings.caps)
    Spacer(Modifier.height(10.dp))

    // Autoplay follows the account, not the device: it describes how someone watches, not which
    // screen they are holding.
    ToggleRow(
        title = S.autoplayTitle.caps,
        body = S.autoplayBody,
        on = settings.autoplayNext,
        onToggle = {
            settings.setAutoplayNext(scope, !settings.autoplayNext) {
                toasts.error(S.settingNotSynced)
            }
        },
    )

    Spacer(Modifier.height(10.dp))
    LanguagePicker()
}

/**
 * Language, as a segmented control rather than a dropdown.
 *
 * With two languages a select is more taps than choices. Each option is written in its own
 * language and cased by its own rules — Georgian in Mtavruli like the rest of the chrome, English
 * left alone — so the label you are looking for reads correctly whichever language is active.
 */
@Composable
private fun LanguagePicker() {
    val settings = settings()
    val toasts = toasts()
    val scope = rememberCoroutineScope()
    val active = I18n.current.tag

    Row(
        Modifier
            .fillMaxWidth()
            .clip(Tokens.radiusSmall)
            .background(Tokens.elev2)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(S.language.caps, color = Tokens.tx, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
        Row(
            Modifier.clip(Tokens.pill).background(Tokens.bg).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            I18n.available.forEach { language ->
                val selected = language.tag == active
                Text(
                    text = language.caps(language.endonym),
                    color = if (selected) Color.Black else Tokens.txDim,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickableSurface(
                            shape = Tokens.pill,
                            idle = if (selected) Tokens.tx else Color.Transparent,
                            hover = if (selected) Tokens.tx else Tokens.elev2Hover,
                            enabled = !selected,
                        ) {
                            settings.setLanguage(scope, language.tag) {
                                toasts.error(S.settingNotSynced)
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, body: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickableSurface(
                shape = Tokens.radiusSmall,
                idle = Tokens.elev2,
                hover = Tokens.elev2Hover,
                onClick = onToggle,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Tokens.tx, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(body, color = Tokens.mut, fontSize = 11.sp)
        }
        Switch(on)
    }
}

/** A switch drawn from two boxes: Material's own carries a palette this design does not use. */
@Composable
private fun Switch(on: Boolean) {
    Box(
        Modifier
            .width(40.dp)
            .height(24.dp)
            .clip(Tokens.pill)
            .background(if (on) Tokens.ok else Tokens.lineStrong)
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(18.dp).clip(Tokens.pill).background(Color.White))
    }
}

@Composable
private fun SheetItem(
    label: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = when {
            !enabled -> Tokens.mut
            destructive -> Tokens.red
            else -> Tokens.tx
        },
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickableSurface(
                shape = Tokens.radiusSmall,
                idle = Tokens.elev2,
                hover = Tokens.elev2Hover,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 13.dp),
    )
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(Tokens.radiusSmall).background(Tokens.elev2).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = Tokens.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(label, color = Tokens.mut, fontSize = 10.sp)
    }
}

/**
 * Which build is running, and when it went out.
 *
 * Two facts sit in this footer and they are easy to confuse: the line above is when the *catalog*
 * was last pulled from the provider, this one is when the *app* was deployed. The commit hash is
 * the part worth having when something looks wrong — it says exactly what code is live, which a
 * version number invented for the occasion would not.
 */
@Composable
private fun BuildStamp() {
    val whenText = formatDateTime(BuildInfo.PUBLISHED_AT_MILLIS.takeIf { it > 0 })
    // "Version dev" alongside a DEV badge says the same thing twice, so a local build shows only
    // its timestamp and lets the badge carry the meaning.
    val label = if (BuildInfo.isDevBuild) whenText
    else S.appVersion(BuildInfo.BUILD_NUMBER, whenText)
    val url = BuildInfo.commitUrl

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = Tokens.mut,
            fontSize = 11.sp,
            modifier = if (url == null) {
                Modifier
            } else {
                Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { openExternal(url) }
            },
        )
        Spacer(Modifier.width(8.dp))
        Text(BuildInfo.COMMIT, color = Tokens.lineStrong, fontSize = 11.sp)
        if (BuildInfo.isDevBuild) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "dev",
                color = Tokens.red,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(Tokens.radiusSmall)
                    .background(Tokens.redDim.copy(alpha = 0.35f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}
