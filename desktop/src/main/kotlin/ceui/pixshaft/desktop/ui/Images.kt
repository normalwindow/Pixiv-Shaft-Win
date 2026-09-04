package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import ceui.pixshaft.desktop.AppGraph
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.okhttp.OkHttpNetworkFetcherFactory

@Composable
fun rememberImageLoader(graph: AppGraph): ImageLoader {
    val context = LocalPlatformContext.current
    return remember(graph) {
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.imageHttp })) }
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
    AsyncImage(
        model = url,
        imageLoader = loader,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}
