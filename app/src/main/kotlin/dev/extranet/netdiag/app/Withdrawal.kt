package dev.extranet.netdiag.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.extranet.netdiag.measure.ReleasedVersion

/**
 * What a person is told when the build they are holding has been retired.
 *
 * Four things are said on purpose. The app says *which* build is retired, so somebody with two
 * copies installed can tell them apart. It says *nothing was changed and nothing was uploaded*,
 * because a stopped app is the moment somebody wonders what it did with their data. It says what
 * to do next — and it must, because a withdrawal that leaves somebody with no way forward is a
 * dead end rather than a retirement, so the newest published build is named here, with the button
 * pointing at it. And it says plainly that the app stopped of its own accord, because the one
 * thing this screen must never do is look like the phone broke.
 *
 * The link comes from the release list when there is a newer release, and from the switch file
 * otherwise — the project may have pointed the switch at a page of its own. Either way it was
 * checked for `https` before it got here, and it opens only when the person taps it.
 */
@Composable
public fun WithdrawnScreen(
    message: String?,
    url: String?,
    newest: ReleasedVersion?,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val installed = installedVersionName(context)
    val latestUrl = newest?.pageUrl ?: newest?.apk?.downloadUrl
    val destination = latestUrl ?: url

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Editorial.Bone)
            // The shell is not drawing, so nothing else is applying the status and gesture bars.
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Text(
            "extranet",
            style = MaterialTheme.typography.labelMedium,
            color = Editorial.Blue,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "This build has been withdrawn",
            style = CappedDisplay(MaterialTheme.typography.displaySmall),
            color = Editorial.Ink,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            message ?: "The project has retired this version of NetDiag. Install the current " +
                "build to carry on measuring.",
            style = MaterialTheme.typography.bodyLarge,
            color = Editorial.InkSoft,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            "Nothing on this phone was changed and no measurement was uploaded. The app asked " +
                "one question when it opened, and stopped when the answer was no.",
            style = MaterialTheme.typography.bodyMedium,
            color = Editorial.InkMid,
        )
        Spacer(Modifier.height(12.dp))
        MonoMeta("installed build: $installed", color = Editorial.InkMid)

        Spacer(Modifier.height(24.dp))
        if (newest != null) {
            Text(
                if (newest.testBuild) {
                    "Version ${newest.tag} is published as a test build."
                } else {
                    "Version ${newest.tag} is published."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Editorial.Ink,
            )
            newest.apk?.let { asset ->
                Spacer(Modifier.height(4.dp))
                MonoMeta(
                    buildString {
                        append(asset.name)
                        val size = asset.bytes
                        if (size != null && size > 0) {
                            append("  ·  ")
                            append("%.1f MB".format(size / 1_048_576.0))
                        }
                    },
                    color = Editorial.InkMid,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        if (destination != null) {
            InkButton(text = "Get the current build", onClick = { onOpen(destination) })
        } else {
            // No link means the switch file gave none we trust, which is the app's own doing, not
            // the user's. Saying so beats a button that silently does nothing.
            Text(
                "This build's switch file named no download page, so there is nowhere to send " +
                    "you from here. The project's release page is in the README.",
                style = MaterialTheme.typography.bodyMedium,
                color = Editorial.InkMid,
            )
        }
        Spacer(Modifier.height(8.dp))
        Hairline(color = Editorial.Hairline)
        Spacer(Modifier.height(8.dp))
        Text(
            "You can uninstall this app at any time from Android's settings. Android does not " +
                "let one app close another, so stopping a copy is the project's decision, made " +
                "in a file, and this screen is all it can do about it.",
            style = MaterialTheme.typography.bodyMedium,
            color = Editorial.InkMid,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}