package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import ceui.pixshaft.desktop.AppGraph
import ceui.pixshaft.desktop.AppPaths
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath

data class ImagePolicy(
    val largeThumbnail: Boolean = false,
    val originalDetail: Boolean = false,
)

val LocalImagePolicy = staticCompositionLocalOf { ImagePolicy() }

@Composable
fun rememberImageLoader(graph: AppGraph): ImageLoader {
    val context = LocalPlatformContext.current
    val settings = graph.settings.current
    val cachePath = settings.cachePath
    return remember(graph, cachePath, settings.diskCacheEnabled, settings.diskCacheLimitMb) {
        val dir = AppPaths.imageCacheDir(cachePath)
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.imageHttp })) }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .apply {
                if (settings.diskCacheEnabled) {
                    diskCache {
                        DiskCache.Builder()
                            .directory(dir.toFile().toOkioPath())
                            .maxSizeBytes(settings.diskCacheLimitMb.toLong() * 1024L * 1024L)
                            .build()
                    }
                }
            }
            .build()
    }
}

@Composable
fun PixivImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    loader: ImageLoader,
) {
    if (url.isNullOrBlank()) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
        return
    }
    val context = LocalPlatformContext.current
    val request = remember(url, context) {
        ImageRequest.Builder(context)
            .data(url)
            .memoryCacheKey(url)
            .diskCacheKey(url)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(180)
            .build()
    }
    AsyncImage(
        model = request,
        imageLoader = loader,
        contentDescription = contentDescription,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentScale = contentScale,
    )
}

/** 带占位与加载指示的图片（详情页大图用）。 */
@Composable
fun LoadableImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    loader: ImageLoader,
) {
    if (url.isNullOrBlank()) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
        return
    }
    val context = LocalPlatformContext.current
    val request = remember(url, context) {
        ImageRequest.Builder(context)
            .data(url)
            .memoryCacheKey(url)
            .diskCacheKey(url)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(180)
            .build()
    }
    coil3.compose.SubcomposeAsyncImage(
        model = request,
        imageLoader = loader,
        contentDescription = contentDescription,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentScale = contentScale,
        loading = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(30.dp),
                )
            }
        },
        error = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载失败", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}
