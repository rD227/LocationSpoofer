package com.vincenthzr.locationspoofer.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.utils.GithubUpdateSource

@Composable
internal fun ReleaseNotesFallback(url: String = GithubUpdateSource.LATEST_PAGE) {
    val uriHandler = LocalUriHandler.current
    Column {
        Text(stringResource(R.string.release_notes_unavailable))
        TextButton(onClick = { uriHandler.openUri(url) }) {
            Text(stringResource(R.string.view_release_in_browser))
        }
    }
}
