package com.example.trackstuff.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import com.example.trackstuff.R
import com.example.trackstuff.domain.MediaKind
import com.example.trackstuff.domain.WatchStatus

/** 2:3 poster with an icon fallback when the image is missing (or offline without cache). */
@Composable
fun PosterImage(url: String?, title: String, modifier: Modifier = Modifier, cornerRadius: Int = 12) {
    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(Icons.Default.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        }
    }
}

/** Opens a URL in the browser (or the associated app, e.g. IMDb). */
fun openUrl(context: android.content.Context, url: String) {
    if (!url.startsWith("https://") && !url.startsWith("http://")) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Clickable rating chip: source, value, link to the external page. */
@Composable
fun RatingChip(label: String, value: String?, url: String?, color: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.15f),
        modifier = modifier.then(if (url != null) Modifier.clickable { openUrl(context, url) } else Modifier),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
fun KindBadge(kind: MediaKind, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(6.dp), color = kindColor(kind).copy(alpha = 0.2f), modifier = modifier) {
        Text(
            stringResource(kind.labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = kindColor(kind),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun StatusBadge(status: WatchStatus, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(6.dp), color = statusColor(status).copy(alpha = 0.2f), modifier = modifier) {
        Text(
            stringResource(status.labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = statusColor(status),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

fun kindColor(kind: MediaKind): Color = when (kind) {
    MediaKind.MOVIE -> Color(0xFF3F51B5)
    MediaKind.SERIES -> Color(0xFF009688)
    MediaKind.DOCUMENTARY -> Color(0xFF795548)
    MediaKind.ANIME -> Color(0xFFE91E63)
}

fun statusColor(status: WatchStatus): Color = when (status) {
    WatchStatus.PLANNED -> Color(0xFF607D8B)
    WatchStatus.WATCHING -> Color(0xFF2196F3)
    WatchStatus.COMPLETED -> Color(0xFF4CAF50)
}

/** Compact card for grids (library, results). */
@Composable
fun MediaCard(
    title: String,
    year: Int?,
    posterUrl: String?,
    kind: MediaKind?,
    status: WatchStatus?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /** Optional quick action shown under the status (e.g. "+1" on a series in progress). */
    action: (() -> Unit)? = null,
    actionLabel: String? = null,
) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
        PosterImage(posterUrl, title, Modifier.fillMaxWidth())
        // Fixed height (2 title lines, 1 meta line) so that every card in a row lines up.
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        val meta = listOfNotNull(year?.toString(), subtitle).joinToString(" · ")
        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // No category badge on cards (only on the page); the status stays visible in the library.
        if (status != null || action != null) Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (status != null) StatusBadge(status)
            if (action != null) Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = action)) {
                Text(actionLabel ?: "+1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
    }
}

fun formatScore(v: Double?): String? = v?.let { String.format(java.util.Locale.getDefault(), "%.1f", it) }

/** Item of a horizontal poster row. */
data class RowItem(
    val key: Any,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val kind: MediaKind?,
    val status: WatchStatus?,
    val subtitle: String? = null,
    val onClick: () -> Unit,
    /** Called when the card enters composition (e.g. to fetch a missing poster). */
    val onVisible: (() -> Unit)? = null,
    /** When [posterUrl] is null: key of a poster resolved later (see `PosterRow.posterFor`). */
    val posterKey: String? = null,
    /** Quick action on the card (label given by the row). */
    val action: (() -> Unit)? = null,
)

/**
 * Horizontal poster row with a section title (library, Discover). [posterFor] resolves the poster of an item
 * whose URL was unknown when the row was built; it is read inside each card, so only that card redraws.
 */
@Composable
fun PosterRow(title: String, items: List<RowItem>, modifier: Modifier = Modifier, subtitle: String? = null, emptyText: String? = null, posterFor: ((String) -> String?)? = null, actionLabel: String? = null) {
    Column(modifier) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (items.isNotEmpty()) Text("${items.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (items.isEmpty()) {
            Text(
                emptyText ?: stringResource(R.string.row_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.key }) { item ->
                    item.onVisible?.let { cb -> androidx.compose.runtime.LaunchedEffect(item.key) { cb() } }
                    MediaCard(
                        title = item.title,
                        year = item.year,
                        posterUrl = item.posterUrl ?: item.posterKey?.let { k -> posterFor?.invoke(k) },
                        kind = item.kind,
                        status = item.status,
                        subtitle = item.subtitle,
                        onClick = item.onClick,
                        action = item.action,
                        actionLabel = actionLabel,
                        modifier = Modifier.width(120.dp),
                    )
                }
            }
        }
    }
}

/** ISO date (yyyy-MM-dd) in the phone's format, e.g. "20 Sep 2026"; null when the date is missing or malformed. */
fun formatDate(iso: String?): String? = iso?.takeIf { it.length == 10 }?.let {
    runCatching {
        java.time.LocalDate.parse(it).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.getDefault()))
    }.getOrNull()
}
