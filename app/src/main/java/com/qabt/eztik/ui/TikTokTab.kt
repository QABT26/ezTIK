package com.qabt.eztik.ui

import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.qabt.eztik.R
import kotlinx.coroutines.delay

/** Guest feed playback. Two bounded ExoPlayer instances: active and next prebuffer. */
@Composable
internal fun TikTokTab(padding: PaddingValues) {
    val context = LocalContext.current
    val hostView = LocalView.current
    DisposableEffect(hostView) {
        val prior = hostView.keepScreenOn
        hostView.keepScreenOn = true
        onDispose { hostView.keepScreenOn = prior }
    }
    var clips by remember { mutableStateOf<List<TikTokFeedClip>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var lastRequestedSize by remember { mutableIntStateOf(-1) }
    val pager = rememberPagerState(pageCount = { clips.size })
    var currentId by remember { mutableStateOf("") }
    var playbackError by remember { mutableStateOf("") }
    var paused by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0f) }
    var duration by remember { mutableStateOf(0f) }

    // Two players only: the spare buffers the next clip while the active one plays.
    val players = remember(context) {
        List(2) {
            val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://www.tiktok.com/",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
                )
            )
            val dataSource = DefaultDataSource.Factory(context, http)
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
                .build().apply {
                    repeatMode = Player.REPEAT_MODE_ONE
                    playWhenReady = false
                }
        }
    }
    val preparedIds = remember { mutableStateListOf("", "") }
    var activeSlot by remember { mutableIntStateOf(0) }

    DisposableEffect(players) {
        val listeners = players.mapIndexed { slot, player ->
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.w("ezTikPlayer", "lab_playback slot=$slot " + error.errorCodeName)
                    if (slot == activeSlot) playbackError = error.errorCodeName
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        Log.i("ezTikPlayer", "lab_media3_ready slot=$slot")
                    }
                }
            }.also { player.addListener(it) }
        }
        onDispose {
            players.forEachIndexed { index, player ->
                player.removeListener(listeners[index])
                player.release()
            }
        }
    }

    LaunchedEffect(revision) {
        busy = true
        error = ""
        val previousIds = if (revision > 0) clips.mapTo(mutableSetOf()) { it.id } else emptySet()
        if (revision > 0) {
            players.forEach { it.playWhenReady = false; it.stop() }
            preparedIds[0] = ""
            preparedIds[1] = ""
            currentId = ""
            pager.scrollToPage(0)
            clips = emptyList()
        }
        runCatching {
            val first = TikTokLabFeed.load()
            if (revision == 0) first else {
                val fresh = first.filterNot { it.id in previousIds }
                val additional = if (fresh.size < 4) TikTokLabFeed.load() else emptyList()
                val unique = (fresh + additional.filterNot { it.id in previousIds })
                    .distinctBy { it.id }
                (unique + first).distinctBy { it.id }
            }
        }.onSuccess { batch ->
            clips = batch.distinctBy { it.id }
            lastRequestedSize = -1
            Log.i("ezTikFeed", "lab_" + (if (revision == 0) "initial" else "refresh") + "=" + clips.size)
        }.onFailure {
            error = it.message.orEmpty().take(130)
            Log.w("ezTikFeed", "lab_load_failed", it)
        }
        busy = false
    }

    // Preserve the active decoder on feed append, switch to a prebuffered next player.
    LaunchedEffect(pager.settledPage, clips) {
        val pageIndex = pager.settledPage
        val clip = clips.getOrNull(pageIndex) ?: return@LaunchedEffect
        if (clip.id != currentId) {
            val spare = 1 - activeSlot
            val targetSlot = if (preparedIds[spare] == clip.id) spare else activeSlot
            players.forEach { it.playWhenReady = false }
            if (preparedIds[targetSlot] != clip.id || players[targetSlot].playbackState == Player.STATE_IDLE) {
                players[targetSlot].stop()
                players[targetSlot].setMediaItem(MediaItem.fromUri(clip.mediaUrl))
                preparedIds[targetSlot] = clip.id
                players[targetSlot].prepare()
                Log.i("ezTikPlayer", "lab_cold_page=" + pageIndex)
            } else {
                Log.i("ezTikPlayer", "lab_preloaded_page=" + pageIndex)
            }
            activeSlot = targetSlot
            currentId = clip.id
            playbackError = ""
            paused = false
            position = 0f
            duration = 0f
            players[activeSlot].playWhenReady = true
            Log.i("ezTikPlayer", "active_page=" + pageIndex)
        }
        val next = clips.getOrNull(pageIndex + 1)
        val spareSlot = 1 - activeSlot
        if (next != null && preparedIds[spareSlot] != next.id) {
            players[spareSlot].playWhenReady = false
            players[spareSlot].stop()
            players[spareSlot].setMediaItem(MediaItem.fromUri(next.mediaUrl))
            preparedIds[spareSlot] = next.id
            players[spareSlot].prepare()
            Log.i("ezTikPlayer", "lab_preload_next=" + (pageIndex + 1))
        }
    }

    LaunchedEffect(players, currentId, activeSlot) {
        while (true) {
            val player = players[activeSlot]
            val d = player.duration
            duration = if (d > 0L) d.toFloat() else 0f
            position = player.currentPosition.coerceAtLeast(0L).toFloat()
            delay(350)
        }
    }

    LaunchedEffect(pager.currentPage, clips.size, busy) {
        if (clips.isNotEmpty() && !busy && clips.size < 160 &&
            pager.currentPage >= clips.size - 3 &&
            lastRequestedSize != clips.size) {
            lastRequestedSize = clips.size
            busy = true
            runCatching { TikTokLabFeed.load() }
                .onSuccess { batch ->
                    val seen = clips.mapTo(mutableSetOf()) { it.id }
                    val unique = batch.filter { seen.add(it.id) }
                    clips = clips + unique
                    Log.i("ezTikFeed", "lab_new=" + unique.size + " total=" + clips.size)
                }
                .onFailure {
                    error = it.message.orEmpty().take(130)
                    Log.w("ezTikFeed", "lab_next_failed", it)
                }
            busy = false
        }
    }

    Box(Modifier.fillMaxSize().padding(padding).background(Color.Black)) {
        if (clips.isNotEmpty()) {
            VerticalPager(
                state = pager,
                key = { clips[it].id },
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1
            ) { index ->
                val active = pager.settledPage == index && currentId == clips[index].id
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (active) {
                        AndroidView(
                            factory = { viewContext ->
                                PlayerView(viewContext).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                                    this.player = players[activeSlot]
                                }
                            },
                            update = { it.player = players[activeSlot] },
                            modifier = Modifier.fillMaxSize()
                        )
                        // Tap to pause/resume without exposing Media3's full control panel.
                        Box(Modifier.fillMaxSize().pointerInput(currentId) {
                            detectTapGestures {
                                paused = !paused
                                players[activeSlot].playWhenReady = !paused
                            }
                        })
                        if (paused) {
                            Text(
                                stringResource(R.string.tiktok_feed_paused),
                                modifier = Modifier.align(Alignment.Center)
                                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                                color = Color.White
                            )
                        }
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center).size(28.dp),
                            color = Color.White
                        )
                    }

                    Column(
                        modifier = Modifier.align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.50f),
                                        Color.Black.copy(alpha = 0.82f)
                                    )
                                )
                            )
                            .padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = 5.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            clips[index].title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                        if (active && playbackError.isNotBlank()) {
                            Text(playbackError, color = Color(0xFFFFB4AB))
                        }
                        if (active && duration > 0f) {
                            val progress = (position / duration).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier.fillMaxWidth().height(18.dp)
                                    .pointerInput(duration, currentId) {
                                        detectTapGestures { tap ->
                                            val fraction = (tap.x / size.width.toFloat())
                                                .coerceIn(0f, 1f)
                                            players[activeSlot].seekTo((duration * fraction).toLong())
                                        }
                                    },
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Box(
                                    Modifier.fillMaxWidth().height(3.dp)
                                        .background(Color.White.copy(alpha = 0.35f))
                                )
                                if (progress > 0f) {
                                    Box(
                                        Modifier.fillMaxWidth(progress).height(3.dp)
                                            .background(Color.White)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            IconButton(
                onClick = { if (!busy) revision++ },
                enabled = !busy,
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.52f), RoundedCornerShape(50))
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.tiktok_feed_refresh),
                    tint = Color.White
                )
            }
            if (busy) CircularProgressIndicator(
                modifier = Modifier.align(Alignment.TopCenter).padding(10.dp).size(22.dp),
                color = Color.White
            )
            if (error.isNotBlank()) Text(
                error,
                modifier = Modifier.align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(8.dp),
                color = Color(0xFFFFB4AB)
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.tiktok_feed_title),
                    style = MaterialTheme.typography.titleLarge, color = Color.White
                )
                if (busy) {
                    CircularProgressIndicator(color = Color.White)
                    Text(stringResource(R.string.tiktok_feed_loading), color = Color.White)
                } else {
                    Text(stringResource(R.string.tiktok_feed_unavailable),
                        color = Color(0xFFFFB4AB))
                    if (error.isNotBlank()) Text(error, color = Color.White)
                    Button(onClick = {
                        lastRequestedSize = -1
                        currentId = ""
                        clips = emptyList()
                        revision++
                    }) { Text(stringResource(R.string.tiktok_feed_retry)) }
                }
                Text(
                    stringResource(R.string.tiktok_feed_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray
                )
            }
        }
    }
}
