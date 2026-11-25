package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.media.DetailsUiState
import com.example.media.MediaDetails
import com.example.media.MediaSearchResult
import com.example.media.MediaType
import com.example.media.SeasonInfo
import com.example.media.SourceSearchTarget

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaDetailsScreen(
    summary: MediaSearchResult,
    detailsState: DetailsUiState,
    isFavorite: Boolean,
    strings: AppStrings,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onToggleFavorite: (MediaSearchResult) -> Unit,
    onSearchSources: (SourceSearchTarget) -> Unit,
    onOpenUrl: (String, String) -> Unit,
    onShareMedia: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val details = (detailsState as? DetailsUiState.Success)?.details
    val activeSummary = details?.toSummary() ?: summary
    var expandedSeasonNum by remember { mutableStateOf<Int?>(null) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "details_hero") {
                val context = LocalContext.current
                val heroBackdropUrl = details?.backdropUrl ?: details?.posterUrl ?: summary.posterUrl
                val rating = details?.imdbRating ?: summary.imdbRating

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            if (!heroBackdropUrl.isNullOrBlank()) {
                                val backdropRequest = remember(heroBackdropUrl, context) {
                                    ImageRequest.Builder(context)
                                        .data(heroBackdropUrl)
                                        .size(780, 440)
                                        .crossfade(true)
                                        .build()
                                }
                                AsyncImage(
                                    model = backdropRequest,
                                    contentDescription = activeSummary.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                Color.Black.copy(alpha = 0.45f),
                                                Color.Transparent,
                                                MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                                            )
                                        )
                                    ),
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                                ) {
                                    IconButton(
                                        onClick = onBack,
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("details_back_button"),
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Rounded.ArrowBack,
                                            contentDescription = strings.discover.detailsBack,
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                                    ) {
                                        IconButton(
                                            onClick = { onToggleFavorite(activeSummary) },
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("details_favorite_button"),
                                        ) {
                                            Icon(
                                                imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                                contentDescription = strings.discover.favoritesTitle,
                                                tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    }
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                                    ) {
                                        IconButton(
                                            onClick = {
                                                val text = buildString {
                                                    append(activeSummary.title)
                                                    activeSummary.releaseYear?.let { append(" ($it)") }
                                                    details?.imdbUrl?.let { append("\n$it") }
                                                }
                                                onShareMedia(text)
                                            },
                                            modifier = Modifier.minimumInteractiveComponentSize(),
                                        ) {
                                            Icon(
                                                Icons.Rounded.Share,
                                                contentDescription = strings.discover.detailsShare,
                                                tint = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    }
                                }
                            }

                            if (rating != null) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)),
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(12.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            Icons.Rounded.Star,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = String.format(java.util.Locale.US, "%.1f", rating),
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            PosterImage(
                                posterUrl = details?.posterUrl ?: summary.posterUrl,
                                mediaType = summary.mediaType,
                                contentDescription = summary.title,
                                modifier = Modifier
                                    .width(108.dp)
                                    .height(160.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                            )

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = details?.title ?: summary.title,
                                    style = MaterialTheme.typography.titleLarge,
                                )

                                val faTitle = details?.persianTitle ?: summary.persianTitle
                                if (!faTitle.isNullOrBlank() && !faTitle.equals(details?.title ?: summary.title, ignoreCase = true)) {
                                    Text(
                                        text = faTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }

                                val orig = details?.originalTitle ?: summary.originalTitle
                                if (!orig.isNullOrBlank() && !orig.equals(details?.title ?: summary.title, ignoreCase = true)) {
                                    Text(
                                        text = orig,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                    ) {
                                        Text(
                                            text = if (summary.mediaType == MediaType.TV) strings.discover.badgeTvShow else strings.discover.badgeMovie,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        )
                                    }

                                    val yearText = buildYearRangeText(details, summary)
                                    if (yearText.isNotEmpty()) {
                                        Text(
                                            text = yearText,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }

                                    val runtime = details?.runtimeMinutes ?: details?.episodeRuntimeMinutes ?: summary.runtimeMinutes
                                    if (runtime != null) {
                                        Text(
                                            text = "• ${strings.discover.labelRuntimeMinutes(runtime)}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                val genres = details?.genres?.takeIf { it.isNotEmpty() } ?: summary.genres
                                if (genres.isNotEmpty()) {
                                    Text(
                                        text = genres.joinToString(" • "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                if (details?.totalSeasons != null || details?.totalEpisodes != null) {
                                    val parts = buildList {
                                        details.totalSeasons?.let { add(strings.discover.labelSeasonsCount(it)) }
                                        details.totalEpisodes?.let { add(strings.discover.labelEpisodesCount(it)) }
                                    }
                                    Text(
                                        text = parts.joinToString(" • "),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Action Buttons: Search Web Sources + External Links
            item(key = "details_actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onSearchSources(
                                SourceSearchTarget(
                                    mediaId = activeSummary.id,
                                    mediaType = activeSummary.mediaType,
                                    title = activeSummary.title,
                                    persianTitle = activeSummary.persianTitle,
                                    releaseYear = activeSummary.releaseYear,
                                )
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp)
                            .testTag("details_search_sources_button"),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (summary.mediaType == MediaType.TV) strings.discover.detailsSearchSeries else strings.discover.detailsSearchSources,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val imdbUrl = details?.imdbUrl ?: summary.imdbId?.let { "https://www.imdb.com/title/$it/" }
                        if (imdbUrl != null) {
                            OutlinedButton(
                                onClick = { onOpenUrl(imdbUrl, "${activeSummary.title} - IMDb") },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.discover.detailsOpenImdb)
                            }
                        }

                        val tmdbUrl = details?.tmdbUrl
                        if (tmdbUrl != null) {
                            OutlinedButton(
                                onClick = { onOpenUrl(tmdbUrl, "${activeSummary.title} - TMDB") },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.discover.detailsOpenTmdb)
                            }
                        }
                    }
                }
            }

            if (detailsState is DetailsUiState.Loading) {
                item(key = "details_loading") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(strings.discover.browserLoading, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else if (detailsState is DetailsUiState.Error) {
                item(key = "details_error") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = detailsState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(strings.discover.actionRetrySearch)
                            }
                        }
                    }
                }
            }

            // Synopsis & Metadata Fields
            item(key = "details_info_card") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val faSummary = details?.persianOverview ?: summary.persianOverview
                        val enSummary = details?.overview ?: summary.overview
                        if (!faSummary.isNullOrBlank()) {
                            Text(strings.discover.detailsPersianOverview, style = MaterialTheme.typography.titleSmall)
                            Text(faSummary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!enSummary.isNullOrBlank()) {
                            Text(
                                text = if (!faSummary.isNullOrBlank()) strings.discover.detailsEnglishOverview else strings.discover.detailsOverview,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(enSummary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        val directors = details?.directors?.takeIf { it.isNotEmpty() } ?: summary.directors
                        if (directors.isNotEmpty()) {
                            MetadataLine(strings.discover.labelDirector, directors.joinToString(", "))
                        }
                        val creators = details?.creators.orEmpty()
                        if (creators.isNotEmpty()) {
                            MetadataLine(strings.discover.labelCreators, creators.joinToString(", "))
                        }
                        details?.releaseDate?.let { MetadataLine(strings.discover.detailsReleaseDate, it) }
                        details?.country?.let { MetadataLine(strings.discover.detailsCountry, it) }
                        details?.originalLanguage?.let { MetadataLine(strings.discover.detailsLanguage, it) }
                        details?.status?.let { MetadataLine(strings.discover.detailsStatus, it) }

                        val castList = details?.cast?.takeIf { it.isNotEmpty() }
                            ?: summary.topCast.map { com.example.media.CastMember(it) }
                        if (castList.isNotEmpty()) {
                            Text(strings.discover.labelCast, style = MaterialTheme.typography.titleSmall)
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(castList.take(15)) { member ->
                                    Surface(
                                        shape = MaterialTheme.shapes.medium,
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .widthIn(min = 104.dp, max = 160.dp)
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(
                                                text = member.name,
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (!member.character.isNullOrBlank()) {
                                                Text(
                                                    text = member.character,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (details != null && details.sourceAttribution.isNotBlank()) {
                            Text(
                                text = strings.discover.detailsMetadataSource(details.sourceAttribution),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }

            // TV Show Seasons & Episodes
            val seasons = details?.seasons.orEmpty()
            if (summary.mediaType == MediaType.TV && seasons.isNotEmpty()) {
                item(key = "seasons_header") {
                    Text(
                        text = strings.discover.detailsSeasonsTitle,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                items(seasons, key = { "season_${it.seasonNumber}" }) { season ->
                    val isExpanded = expandedSeasonNum == season.seasonNumber
                    SeasonAccordionCard(
                        season = season,
                        isExpanded = isExpanded,
                        strings = strings,
                        onToggleExpand = {
                            expandedSeasonNum = if (isExpanded) null else season.seasonNumber
                        },
                        onSearchSeason = {
                            onSearchSources(
                                SourceSearchTarget(
                                    mediaId = activeSummary.id,
                                    mediaType = MediaType.TV,
                                    title = activeSummary.title,
                                    persianTitle = activeSummary.persianTitle,
                                    releaseYear = activeSummary.releaseYear,
                                    seasonNumber = season.seasonNumber,
                                )
                            )
                        },
                        onSearchEpisode = { epNum, epTitle ->
                            onSearchSources(
                                SourceSearchTarget(
                                    mediaId = activeSummary.id,
                                    mediaType = MediaType.TV,
                                    title = activeSummary.title,
                                    persianTitle = activeSummary.persianTitle,
                                    releaseYear = activeSummary.releaseYear,
                                    seasonNumber = season.seasonNumber,
                                    episodeNumber = epNum,
                                    episodeTitle = epTitle,
                                )
                            )
                        },
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun MetadataLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SeasonAccordionCard(
    season: SeasonInfo,
    isExpanded: Boolean,
    strings: AppStrings,
    onToggleExpand: () -> Unit,
    onSearchSeason: () -> Unit,
    onSearchEpisode: (Int, String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = season.title,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = buildString {
                            append(strings.discover.labelEpisodesCount(season.episodeCount))
                            season.airDate?.take(4)?.let { append(" • $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = onSearchSeason,
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) {
                        Text(strings.discover.detailsSearchSeason(season.seasonNumber), style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded && season.episodes.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    season.episodes.forEach { ep ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = "${ep.episodeNumber}. ${ep.title}",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                )
                                val meta = listOfNotNull(
                                    ep.airDate,
                                    ep.runtimeMinutes?.let { strings.discover.labelRuntimeMinutes(it) },
                                ).joinToString(" • ")
                                if (meta.isNotBlank()) {
                                    Text(
                                        text = meta,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!ep.overview.isNullOrBlank()) {
                                    Text(
                                        text = ep.overview,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = { onSearchEpisode(ep.episodeNumber, ep.title) },
                                modifier = Modifier.heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp),
                            ) {
                                Text(
                                    text = strings.discover.detailsSearchEpisode(season.seasonNumber, ep.episodeNumber),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildYearRangeText(details: MediaDetails?, summary: MediaSearchResult): String {
    if (summary.mediaType == MediaType.TV && details?.startYear != null) {
        return if (details.endYear != null && details.endYear != details.startYear) {
            "${details.startYear}–${details.endYear}"
        } else {
            details.startYear.toString()
        }
    }
    return (details?.releaseYear ?: summary.releaseYear)?.toString().orEmpty()
}
