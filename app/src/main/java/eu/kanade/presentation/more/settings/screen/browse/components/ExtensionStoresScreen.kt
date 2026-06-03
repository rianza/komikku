package eu.kanade.presentation.more.settings.screen.browse.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import eu.kanade.presentation.category.components.CategoryFloatingActionButton
import eu.kanade.presentation.components.AppBar
<<<<<<< HEAD:app/src/main/java/eu/kanade/presentation/more/settings/screen/browse/components/ExtensionReposScreen.kt
import eu.kanade.presentation.more.settings.screen.browse.RepoScreenState
import eu.kanade.tachiyomi.util.system.openInBrowser
import kotlinx.collections.immutable.persistentSetOf
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo.Companion.KOMIKKU_SIGNATURE
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo.Companion.REPO_HELP
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo.Companion.REPO_SIGNATURE
import mihon.domain.extensionrepo.model.ExtensionRepo
=======
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoreScreenState
import mihon.domain.extension.model.ExtensionStore
>>>>>>> a0ae52671f (Change extension repo to extension store and add support for newer extension index format (#3349)):app/src/main/java/eu/kanade/presentation/more/settings/screen/browse/components/ExtensionStoresScreen.kt
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.plus

@Composable
fun ExtensionStoresScreen(
    state: ExtensionStoreScreenState.Success,
    onClickCreate: () -> Unit,
<<<<<<< HEAD:app/src/main/java/eu/kanade/presentation/more/settings/screen/browse/components/ExtensionReposScreen.kt
    onOpenWebsite: (ExtensionRepo) -> Unit,
    onClickDelete: (String) -> Unit,
    // KMK -->
    onClickEnable: (String) -> Unit,
    onClickDisable: (String) -> Unit,
    // KMK <--
=======
    onCopy: (ExtensionStore) -> Unit,
    onOpenWebsite: (ExtensionStore) -> Unit,
    onOpenDiscord: (ExtensionStore) -> Unit,
    onClickDelete: (ExtensionStore) -> Unit,
>>>>>>> a0ae52671f (Change extension repo to extension store and add support for newer extension index format (#3349)):app/src/main/java/eu/kanade/presentation/more/settings/screen/browse/components/ExtensionStoresScreen.kt
    onClickRefresh: () -> Unit,
    navigateUp: () -> Unit,
) {
    val lazyListState = rememberLazyListState()
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                navigateUp = navigateUp,
                title = stringResource(MR.strings.extensionStores),
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = onClickRefresh) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(resource = MR.strings.action_webview_refresh),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            CategoryFloatingActionButton(
                lazyListState = lazyListState,
                onCreate = onClickCreate,
            )
        },
    ) { paddingValues ->
        if (state.isEmpty) {
            val context = LocalContext.current
            EmptyScreen(
                MR.strings.extensionStoresScreen_emptyLabel,
                modifier = Modifier.padding(paddingValues),
                // KMK -->
                help = {
                    TextButton(
                        onClick = { context.openInBrowser(REPO_HELP) },
                        modifier = Modifier.padding(top = MaterialTheme.padding.small),
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Outlined.Help, contentDescription = null)
                        Spacer(modifier = Modifier.width(MaterialTheme.padding.extraSmall))
                        Text(text = stringResource(MR.strings.label_help))
                    }
                },
                // KMK <--
            )
            return@Scaffold
        }

        ExtensionStoresContent(
            repos = state.stores,
            lazyListState = lazyListState,
            paddingValues = paddingValues + topSmallPaddingValues +
                PaddingValues(horizontal = MaterialTheme.padding.medium),
            onCopy = onCopy,
            onOpenWebsite = onOpenWebsite,
            onOpenDiscord = onOpenDiscord,
            onClickDelete = onClickDelete,
            // KMK -->
            onClickEnable = onClickEnable,
            onClickDisable = onClickDisable,
            disabledRepos = state.disabledRepos,
            // KMK <--
        )
    }
}

// KMK -->
@Preview
@Composable
private fun ExtensionReposScreenPreview() {
    val state = RepoScreenState.Success(
        repos = persistentSetOf(
            ExtensionRepo("https://repo", "Komikku", "", "", KOMIKKU_SIGNATURE),
            ExtensionRepo("https://repo", "Repo", "", "", REPO_SIGNATURE),
            ExtensionRepo("https://repo", "Other", "", "", "key2"),
        ),
        disabledRepos = setOf("https://repo"),
    )
    ExtensionReposScreen(
        state = state,
        onClickCreate = { },
        onOpenWebsite = { },
        onClickDelete = { },
        onClickEnable = { },
        onClickDisable = { },
        onClickRefresh = { },
        navigateUp = { },
    )
}

@Preview
@Composable
private fun ExtensionReposScreenEmptyPreview() {
    val state = RepoScreenState.Success(repos = persistentSetOf())
    ExtensionReposScreen(
        state = state,
        onClickCreate = { },
        onOpenWebsite = { },
        onClickDelete = { },
        onClickEnable = { },
        onClickDisable = { },
        onClickRefresh = { },
        navigateUp = { },
    )
}
// KMK <--
