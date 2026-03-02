package top.wsdx233.r2droid.feature.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.wsdx233.r2droid.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginManagerScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val catalog by PluginManager.catalog.collectAsState()
    val installed by PluginManager.installed.collectAsState()
    val installableCatalog = remember(catalog) {
        catalog.filter { item ->
            item.installed == null || item.hasUpgrade
        }
    }
    val repositories by PluginManager.repositorySources.collectAsState()
    val status by PluginManager.status.collectAsState()
    val isWorking by PluginManager.isWorking.collectAsState()
    val logs by PluginManager.logs.collectAsState()

    var sourceInput by remember { mutableStateOf("") }
    var selectedPage by remember { mutableStateOf<Pair<String, PluginPage>?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        PluginManager.initialize(context)
    }

    selectedPage?.let { (pluginId, page) ->
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("${stringResource(R.string.plugin_open_page)}: $pluginId") },
                    navigationIcon = {
                        IconButton(onClick = { selectedPage = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    }
                )
            }
        ) { padding ->
            PluginPageRenderer(
                pluginId = pluginId,
                page = page,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_plugins_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { scope.launch { PluginManager.refreshCatalog() } },
                        enabled = !isWorking
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.plugin_status_prefix, status ?: "-"),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            val tabs = listOf(
                stringResource(R.string.plugin_tab_plugins),
                stringResource(R.string.plugin_tab_sources),
                stringResource(R.string.plugin_tab_logs)
            )
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (selectedTab) {
                0 -> PluginListTab(
                    installableCatalog = installableCatalog,
                    installed = installed,
                    isWorking = isWorking,
                    onInstall = { entry -> scope.launch { PluginManager.install(entry) } },
                    onDelete = { pluginId -> scope.launch { PluginManager.delete(pluginId) } },
                    onSetEnabled = { pluginId, enabled -> scope.launch { PluginManager.setEnabled(pluginId, enabled) } },
                    onOpenPage = { pluginId, page -> selectedPage = pluginId to page }
                )

                1 -> SourceManageTab(
                    repositories = repositories,
                    sourceInput = sourceInput,
                    onSourceInputChange = { sourceInput = it },
                    isWorking = isWorking,
                    onAdd = {
                        scope.launch {
                            PluginManager.addRepositorySource(sourceInput)
                            sourceInput = ""
                        }
                    },
                    onRemove = { repo -> scope.launch { PluginManager.removeRepositorySource(repo) } }
                )

                else -> LogsTab(
                    logs = logs,
                    onClear = { PluginManager.clearLogs() }
                )
            }
        }
    }
}

@Composable
private fun PluginListTab(
    installableCatalog: List<PluginCatalogItem>,
    installed: List<InstalledPlugin>,
    isWorking: Boolean,
    onInstall: (PluginIndexEntry) -> Unit,
    onDelete: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onOpenPage: (String, PluginPage) -> Unit
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.plugin_installed_title),
                style = MaterialTheme.typography.titleMedium
            )
        }

        items(installed, key = { "installed_${it.state.id}" }) { plugin ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val uiOptions = plugin.manifest?.ui ?: PluginUiOptions()
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = plugin.manifest?.name ?: plugin.state.id,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${plugin.state.id} @ ${plugin.state.version}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (uiOptions.showEnableStatus) {
                            Text(
                                text = if (plugin.state.enabled) {
                                    stringResource(R.string.plugin_state_enabled)
                                } else {
                                    stringResource(R.string.plugin_state_disabled)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (plugin.state.enabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    val isBundledAsset = plugin.state.sourceUrl.startsWith("asset://plugins/packages/", ignoreCase = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (uiOptions.showEnableToggle) {
                            IconButton(
                                onClick = { onSetEnabled(plugin.state.id, !plugin.state.enabled) },
                                enabled = !isWorking
                            ) {
                                Icon(
                                    Icons.Default.PowerSettingsNew,
                                    contentDescription = stringResource(R.string.plugin_enabled),
                                    tint = if (plugin.state.enabled) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                        if (!isBundledAsset) {
                            IconButton(
                                onClick = { onDelete(plugin.state.id) },
                                enabled = !isWorking
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.plugin_uninstall)
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                val entry = plugin.manifest?.entry
                                val openPage = entry?.page
                                val terminal = entry?.terminal
                                when {
                                    openPage != null -> onOpenPage(plugin.state.id, openPage)
                                    terminal != null -> {
                                        val intent = android.content.Intent(context, top.wsdx233.r2droid.activity.TerminalActivity::class.java)
                                            .putExtra("startup_command", terminal.command)
                                        context.startActivity(intent)
                                    }
                                }
                            },
                            enabled = plugin.manifest?.entry?.page != null || plugin.manifest?.entry?.terminal != null
                        ) {
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = stringResource(R.string.plugin_open_page)
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.plugin_catalog_title),
                style = MaterialTheme.typography.titleMedium
            )
        }

        items(installableCatalog, key = { "catalog_${it.indexEntry.id}" }) { item ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.indexEntry.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = item.indexEntry.description.ifBlank { item.indexEntry.id },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    val installedVersion = item.installed?.state?.version
                    val actionText = when {
                        installedVersion == null -> stringResource(R.string.plugin_install)
                        item.hasUpgrade -> stringResource(R.string.plugin_upgrade)
                        else -> stringResource(R.string.plugin_reinstall)
                    }
                    Button(
                        onClick = { onInstall(item.indexEntry) },
                        enabled = !isWorking
                    ) {
                        Text(actionText)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceManageTab(
    repositories: List<String>,
    sourceInput: String,
    onSourceInputChange: (String) -> Unit,
    isWorking: Boolean,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.plugin_repositories_title),
                style = MaterialTheme.typography.titleMedium
            )
        }

        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = sourceInput,
                    onValueChange = onSourceInputChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(R.string.plugin_add_source_hint)) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onAdd,
                    enabled = sourceInput.isNotBlank() && !isWorking
                ) {
                    Text(stringResource(R.string.plugin_add_source))
                }
            }
        }

        items(repositories, key = { "repo_$it" }) { repo ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = repo,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { onRemove(repo) }) {
                        Text(stringResource(R.string.plugin_remove_source))
                    }
                }
            }
        }
    }
}

@Composable
private fun LogsTab(
    logs: List<String>,
    onClear: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.plugin_logs_title),
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.common_clear))
                }
            }
        }

        items(logs.takeLast(120), key = { "log_${it.hashCode()}_${it.length}" }) { line ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }
    }
}
