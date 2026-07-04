package eu.kanade.presentation.more.settings.screen.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import mihon.domain.extension.model.ExtensionStore
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.icons.CustomIcons
import tachiyomi.presentation.core.icons.Discord

@Composable
fun ExtensionStoresContent(
    repos: List<ExtensionStore>,
    lazyListState: LazyListState,
    paddingValues: PaddingValues,
    onCopy: (ExtensionStore) -> Unit,
    onOpenWebsite: (ExtensionStore) -> Unit,
    onOpenDiscord: (ExtensionStore) -> Unit,
    onClickDelete: (ExtensionStore) -> Unit,
    // KMK -->
    onClickEnable: (ExtensionStore) -> Unit,
    onClickDisable: (ExtensionStore) -> Unit,
    disabledRepos: Set<String>,
    // KMK <--
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = lazyListState,
        contentPadding = paddingValues,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        modifier = modifier,
    ) {
        repos.forEach {
            item {
                ExtensionStoresListItem(
                    modifier = Modifier.animateItem(),
                    store = it,
                    onOpenWebsite = { onOpenWebsite(it) },
                    onOpenDiscord = { onOpenDiscord(it) },
                    onCopy = { onCopy(it) },
                    onDelete = { onClickDelete(it) },
                    // KMK -->
                    onEnable = { onClickEnable(it) },
                    onDisable = { onClickDisable(it) },
                    isDisabled = it.indexUrl in disabledRepos,
                    // KMK <--
                )
            }
        }
    }
}

@Composable
private fun ExtensionStoresListItem(
    store: ExtensionStore,
    onOpenWebsite: () -> Unit,
    onOpenDiscord: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    // KMK -->
    isDisabled: Boolean,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    // KMK <--
) {
    ElevatedCard(
        modifier = modifier,
    ) {
        // KMK -->
        Row(
            modifier = Modifier
                .padding(start = MaterialTheme.padding.medium),
        ) {
            Icon(imageVector = Icons.AutoMirrored.Outlined.Label, contentDescription = null)
            Column {
                // KMK <--
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = MaterialTheme.padding.medium,
                            top = MaterialTheme.padding.medium,
                            end = MaterialTheme.padding.medium,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = store.name,
                        style = MaterialTheme.typography.titleMedium,
                        // KMK -->
                        color = LocalContentColor.current.let { if (isDisabled) it.copy(alpha = 0.6f) else it },
                        textDecoration = TextDecoration.LineThrough.takeIf { isDisabled },
                        // KMK <--
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(onClick = onOpenWebsite) {
                        Icon(
                            imageVector = Icons.Outlined.Public,
                            contentDescription = stringResource(MR.strings.action_open_in_browser),
                        )
                    }

                    if (store.contact.discord != null) {
                        IconButton(onClick = onOpenDiscord) {
                            Icon(
                                imageVector = CustomIcons.Discord,
                                contentDescription = null,
                            )
                        }
                    }

                    IconButton(onClick = onCopy) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = stringResource(MR.strings.action_copy_to_clipboard),
                        )
                    }

                    // KMK -->
                    IconButton(onClick = if (isDisabled) onEnable else onDisable) {
                        Icon(
                            imageVector = if (isDisabled) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                            contentDescription = stringResource(MR.strings.action_disable),
                        )
                    }
                    // KMK <--

                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(MR.strings.action_delete),
                        )
                    }
                }
            }
        }
    }
}
