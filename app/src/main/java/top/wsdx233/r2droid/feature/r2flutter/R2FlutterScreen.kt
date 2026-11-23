package top.wsdx233.r2droid.feature.r2flutter

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.wsdx233.r2droid.R
import top.wsdx233.r2droid.core.data.prefs.SettingsManager
import top.wsdx233.r2droid.util.FlutterTargetResolver
import top.wsdx233.r2droid.util.R2FlutterInstallState
import top.wsdx233.r2droid.util.R2FlutterInstaller

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun R2FlutterScreen(
    onBack: () -> Unit,
    onOpenTarget: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installState by R2FlutterInstaller.state.collectAsState()
    var installed by remember { mutableStateOf(R2FlutterInstaller.isInstalled(context)) }
    var selectedTarget by remember { mutableStateOf<FlutterTargetResolver.Target?>(null) }
    var targetError by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        resolving = true
        targetError = null
        scope.launch {
            FlutterTargetResolver.resolve(context, uri)
                .onSuccess { selectedTarget = it }
                .onFailure { targetError = it.message ?: context.getString(R.string.common_error) }
            resolving = false
        }
    }

    LaunchedEffect(installState.status) {
        if (installState.status == R2FlutterInstallState.Status.DONE) {
            installed = R2FlutterInstaller.isInstalled(context)
        }
    }
    DisposableEffect(Unit) {
        onDispose { R2FlutterInstaller.resetState() }
    }

    val installing = installState.status in setOf(
        R2FlutterInstallState.Status.FETCHING,
        R2FlutterInstallState.Status.DOWNLOADING,
        R2FlutterInstallState.Status.VERIFYING,
        R2FlutterInstallState.Status.INSTALLING
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.r2flutter_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !installing && !resolving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.menu_back))
                    }
                },
                actions = {
                    if (installed) {
                        IconButton(
                            onClick = {
                                installed = false
                                R2FlutterInstaller.resetState()
                            },
                            enabled = !installing
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.r2flutter_reinstall))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!installed) {
                R2FlutterInstallContent(
                    state = installState,
                    installing = installing,
                    onInstall = { scope.launch { R2FlutterInstaller.install(context) } }
                )
            } else {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.r2flutter_backend_ready)) },
                    supportingContent = {
                        Text(
                            if (SettingsManager.useProotMode) {
                                stringResource(R.string.r2flutter_backend_proot)
                            } else {
                                stringResource(R.string.r2flutter_backend_native)
                            }
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                )

                FilledTonalButton(
                    onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "application/zip", "application/octet-stream", "*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !resolving
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.r2flutter_choose_target))
                }

                if (resolving) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Spacer(Modifier.size(12.dp))
                        Text(stringResource(R.string.r2flutter_resolving_target))
                    }
                }

                selectedTarget?.let { target ->
                    ListItem(
                        headlineContent = {
                            Text(target.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(target.path, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = {
                            Icon(
                                if (target.extractedFromApk) Icons.Default.Archive else Icons.Default.Extension,
                                contentDescription = null
                            )
                        }
                    )
                    Button(
                        onClick = { onOpenTarget(target.path) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.r2flutter_open_analysis))
                    }
                }

                targetError?.let { error ->
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.common_error)) },
                        supportingContent = { Text(error) },
                        leadingContent = {
                            Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun R2FlutterInstallContent(
    state: R2FlutterInstallState,
    installing: Boolean,
    onInstall: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Default.Extension,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }

    ListItem(
        headlineContent = { Text(stringResource(R.string.r2flutter_backend_not_installed)) },
        supportingContent = {
            Text(
                if (SettingsManager.useProotMode) {
                    stringResource(R.string.r2flutter_install_proot_desc)
                } else {
                    stringResource(R.string.r2flutter_install_native_desc)
                }
            )
        },
        leadingContent = { Icon(Icons.Default.CloudDownload, contentDescription = null) }
    )

    if (installing) {
        LinearProgressIndicator(
            progress = { state.progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
        Text(state.message, style = MaterialTheme.typography.bodyMedium)
    }

    if (state.status == R2FlutterInstallState.Status.ERROR) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.r2flutter_install_failed)) },
            supportingContent = { Text(state.message) },
            leadingContent = {
                Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            }
        )
    }

    Button(
        onClick = onInstall,
        modifier = Modifier.fillMaxWidth(),
        enabled = !installing
    ) {
        Text(
            if (state.status == R2FlutterInstallState.Status.ERROR) {
                stringResource(R.string.common_retry)
            } else {
                stringResource(R.string.r2flutter_install)
            }
        )
    }
    Spacer(Modifier.height(4.dp))
}
