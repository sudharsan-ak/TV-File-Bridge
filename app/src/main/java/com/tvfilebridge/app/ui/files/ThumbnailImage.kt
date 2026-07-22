package com.tvfilebridge.app.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tvfilebridge.app.AppContainer
import com.tvfilebridge.app.files.RemoteFile
import java.io.File

private val THUMBNAIL_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

fun isThumbnailable(entry: RemoteFile): Boolean =
    !entry.isDirectory && entry.name.substringAfterLast('.', "").lowercase() in THUMBNAIL_EXTENSIONS

@Composable
fun ThumbnailImage(
    entry: RemoteFile,
    container: AppContainer,
    modifier: Modifier = Modifier,
    isVideo: Boolean = false,
) {
    val cacheFile by produceState<File?>(initialValue = null, entry.path) {
        value = container.thumbnailRepository.getThumbnail(entry)
    }

    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        when {
            cacheFile != null -> AsyncImage(
                model = cacheFile,
                contentDescription = entry.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            else -> if (isVideo) {
                Icon(Icons.Filled.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}
