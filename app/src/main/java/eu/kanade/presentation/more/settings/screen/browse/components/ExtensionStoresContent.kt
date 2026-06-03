package eu.kanade.presentation.more.settings.screen.browse.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
<<<<<<< HEAD:app/src/main/java/eu/kanade/presentation/more/settings/screen/browse/components/ExtensionReposContent.kt
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
=======
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Public
>>>>>>> a0ae52671f (Change extension repo to extension store and add support for newer extension index format (#3349)):app/src/main/java/eu/kanade/presentation/more/settings/screen/browse/components/ExtensionStoresContent.kt
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
<<<<<<< HEAD:app/src/main/java/eu/kanade/presentation/more/settings/screen/browse/components/ExtensionReposContent.kt
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.copyToClipboard
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo.Companion.KOMIKKU_SIGNATURE
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo.Companion.REPO_SIGNATURE
import mihon.domain.extensionrepo.model.ExtensionRepo
=======
import mihon.domain.extension.model.ExtensionStore
>>>>>>> a0ae52671f (Change extension repo to extension store and add support for newer extension index format (#3349)):app/src/main/java/eu/kanade/presentation/more/settings/screen/browse/components/ExtensionStoresContent.kt
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
<<<<<<< HEAD:app/src/main/java/eu/kanade/presentation/more/settings/screen/browse/components/ExtensionReposContent.kt
    onOpenWebsite: (ExtensionRepo) -> Unit,
    onClickDelete: (String) -> Unit,
    // KMK -->
    onClickEnable: (String) -> Unit,
    onClickDisable: (String) -> Unit,
    disabledRepos: Set<String>,
    // KMK <--
=======
    onCopy: (ExtensionStore) -> Unit,
    onOpenWebsite: (ExtensionStore) -> Unit,
    onOpenDiscord: (ExtensionStore) -> Unit,
    onClickDelete: (ExtensionStore) -> Unit,
>>>>>>> a0ae52671f (Change extension repo to extension store and add support for newer extension index format (#3349)):app/src/main/java/eu/kanade/presentation/more/settings/screen/browse/components/ExtensionStoresContent.kt
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
<<<<<<< HEAD:app/src/main/java/eu/kanade/presentation/more/settings/screen/browse/components/ExtensionReposContent.kt
                    onDelete = { onClickDelete(it.baseUrl) },
                    // KMK -->
                    onEnable = { onClickEnable(it.baseUrl) },
                    onDisable = { onClickDisable(it.baseUrl) },
                    isDisabled = it.baseUrl in disabledRepos,
                    // KMK <--
=======
                    onOpenDiscord = { onOpenDiscord(it) },
                    onCopy = { onCopy(it) },
                    onDelete = { onClickDelete(it) },
>>>>>>> a0ae52671f (Change extension repo to extension store and add support for newer extension index format (#3349)):app/src/main/java/eu/kanade/presentation/more/settings/screen/browse/components/ExtensionStoresContent.kt
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
<<<<<<< HEAD:app/src/main/java/eu/kanade/presentation/more/settings/screen/browse/components/ExtensionReposContent.kt
            val resId = repoResId(repo.signingKeyFingerprint)
            Image(
                bitmap = ImageBitmap.imageResource(id = resId),
                contentDescription = null,
                alpha = if (isDisabled) 0.4f else 1f,
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .align(Alignment.CenterVertically),
=======
            Icon(imageVector = Icons.AutoMirrored.Outlined.Label, contentDescription = null)
            Text(
                text = store.name,
                modifier = Modifier.padding(start = MaterialTheme.padding.medium),
                style = MaterialTheme.typography.titleMedium,
>>>>>>> a0ae52671f (Change extension repo to extension store and add support for newer extension index format (#3349)):app/src/main/java/eu/kanade/presentation/more/settings/screen/browse/components/ExtensionStoresContent.kt
            )
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
                        text = repo.name,
                        // KMK: modifier = Modifier.padding(start = MaterialTheme.padding.medium),
                        style = MaterialTheme.typography.titleMedium,
                        // KMK -->
                        color = LocalContentColor.current.let { if (isDisabled) it.copy(alpha = 0.6f) else it },
                        textDecoration = TextDecoration.LineThrough.takeIf { isDisabled },
                        // KMK <--
                    )
                }

<<<<<<< HEAD:app/src/main/java/eu/kanade/presentation/more/settings/screen/browse/components/ExtensionReposContent.kt
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(onClick = onOpenWebsite) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = stringResource(MR.strings.action_open_in_browser),
                        )
                    }

                    IconButton(
                        onClick = {
                            val url = "${repo.baseUrl}/index.min.json"
                            context.copyToClipboard(url, url)
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = stringResource(MR.strings.action_copy_to_clipboard),
                        )
                    }
=======
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
>>>>>>> a0ae52671f (Change extension repo to extension store and add support for newer extension index format (#3349)):app/src/main/java/eu/kanade/presentation/more/settings/screen/browse/components/ExtensionStoresContent.kt

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

// KMK -->
fun repoResId(signKey: String) = when (signKey) {
    KOMIKKU_SIGNATURE -> R.mipmap.komikku
    REPO_SIGNATURE -> R.mipmap.repo
    else -> R.mipmap.extension
}

@Preview
@Composable
fun ExtensionReposContentPreview() {
    val repos = setOf(
        ExtensionRepo("https://repo", "Komikku", "", "", KOMIKKU_SIGNATURE),
        ExtensionRepo("https://repo", "Repo", "", "", REPO_SIGNATURE),
        ExtensionRepo("https://repo", "Other", "", "", "key2"),
    )
    ExtensionReposContent(
        repos = repos,
        lazyListState = LazyListState(),
        paddingValues = PaddingValues(),
        onOpenWebsite = {},
        onClickDelete = {},
        onClickEnable = {},
        onClickDisable = {},
        disabledRepos = setOf("https://repo"),
    )
}
// KMK <--
