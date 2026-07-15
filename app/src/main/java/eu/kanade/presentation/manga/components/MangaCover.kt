@file:Suppress("PropertyName")

package eu.kanade.presentation.manga.components

import androidx.annotation.ColorInt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import eu.kanade.presentation.manga.components.MangaCover.Companion.COVER_TEMPLATE_SIZE_BIG
import eu.kanade.presentation.manga.components.MangaCover.Companion.COVER_TEMPLATE_SIZE_MEDIUM
import eu.kanade.presentation.manga.components.MangaCover.Companion.COVER_TEMPLATE_SIZE_NORMAL
import eu.kanade.presentation.manga.components.MangaCover.Size
import eu.kanade.tachiyomi.R
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.manga.model.MangaCover as DomainMangaCover

enum class MangaCover(val ratio: Float) {
    Square(1f / 1f),
    Book(2f / 3f),

    // KMK -->
    Panorama(3f / 2f),
    // KMK <--
    ;

    enum class Size {
        Normal,
        Medium,
        Big,
    }

    @Composable
    operator fun invoke(
        data: Any?,
        modifier: Modifier = Modifier,
        contentDescription: String = "",
        shape: Shape = MaterialTheme.shapes.extraSmall,
        onClick: (() -> Unit)? = null,
        // KMK -->
        alpha: Float = 1f,
        bgColor: Color? = null,
        @ColorInt tint: Int? = null,
        /** Perform action when cover loaded, specifically generating color map. If the cover doesn't update, it won't be called */
        onCoverLoaded: ((DomainMangaCover, result: AsyncImagePainter.State.Success) -> Unit)? = null,
        size: Size = Size.Normal,
        scale: ContentScale = ContentScale.Crop,
        // KMK <--
    ) {
        val modifierColored = modifier
            .aspectRatio(ratio)
            .clip(shape)
            // KMK -->
            .alpha(alpha)
            .background(bgColor ?: CoverPlaceholderColor)
            // KMK <--
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )

        AsyncImage(
            model = data,
            onSuccess = { result ->
                if (onCoverLoaded != null) {
                    when (data) {
                        is Manga -> onCoverLoaded(data.asMangaCover(), result)
                        is DomainMangaCover -> onCoverLoaded(data, result)
                    }
                }
            },
            contentDescription = contentDescription,
            modifier = modifierColored,
            contentScale = scale,
        )
    }

    companion object {
        val COVER_TEMPLATE_SIZE_BIG = 16.dp
        val COVER_TEMPLATE_SIZE_MEDIUM = 24.dp
        val COVER_TEMPLATE_SIZE_NORMAL = 32.dp
    }
}

enum class MangaCoverHide(private val ratio: Float) {
    Square(1f / 1f),
    Book(2f / 3f),
    ;

    @Composable
    operator fun invoke(
        modifier: Modifier = Modifier,
        contentDescription: String = "",
        shape: Shape = MaterialTheme.shapes.extraSmall,
        onClick: (() -> Unit)? = null,
        // KMK -->
        /** background color, which used for loading/error indicator */
        bgColor: Color? = CoverPlaceholderColor,
        /** onBackground color, which used for loading/error indicator */
        @ColorInt tint: Int? = null,
        size: Size = Size.Normal,
    ) {
        val modifierColored = modifier
            .aspectRatio(ratio)
            .clip(shape)
            .background(bgColor ?: CoverPlaceholderColor)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )

        Box(
            modifier = modifierColored,
        ) {
            Image(
                imageVector = ImageVector.vectorResource(R.drawable.ic_baseline_menu_book_24),
                contentDescription = contentDescription,
                modifier = Modifier
                    .size(
                        when (size) {
                            Size.Big -> COVER_TEMPLATE_SIZE_BIG
                            Size.Medium -> COVER_TEMPLATE_SIZE_MEDIUM
                            else -> COVER_TEMPLATE_SIZE_NORMAL
                        },
                    )
                    .align(Alignment.Center),
                colorFilter = ColorFilter.tint(
                    tint?.let { Color(it) } ?: CoverPlaceholderOnBgColor,
                ),
            )
        }
    }
}

internal const val RatioSwitchToPanorama = 0.75f

internal val CoverPlaceholderColor = Color(0x1F888888)
internal val CoverPlaceholderOnBgColor = Color(0x8F888888)
