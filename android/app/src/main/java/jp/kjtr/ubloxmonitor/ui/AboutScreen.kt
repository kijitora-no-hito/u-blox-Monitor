package jp.kjtr.ubloxmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jp.kjtr.ubloxmonitor.R

/** 「その他」→ このアプリについて。版・ライセンス・使っているソフトウェア・地図の出典・通信。 */
@Composable
fun AboutScreen(padding: PaddingValues, updateSection: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.about_summary), style = MaterialTheme.typography.bodyMedium)

        Heading(R.string.update_title)
        updateSection()

        Heading(R.string.about_source_title)
        val source = stringResource(R.string.about_source_url)
        TextButton(onClick = { uri.openUri(source) }, contentPadding = PaddingValues(0.dp)) { Text(source) }

        Heading(R.string.about_license_title)
        Body(R.string.about_license)
        Body(R.string.about_trademark)

        Heading(R.string.about_oss_title)
        Body(R.string.about_oss)
        val apache = stringResource(R.string.about_apache_url)
        TextButton(onClick = { uri.openUri(apache) }, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.about_apache_link))
        }

        Heading(R.string.about_data_title)
        Body(R.string.about_data)

        Heading(R.string.about_privacy_title)
        Body(R.string.about_privacy)
    }
}

@Composable
private fun Heading(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun Body(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.bodySmall)
}
