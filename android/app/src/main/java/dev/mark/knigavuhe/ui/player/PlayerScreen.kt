package dev.mark.knigavuhe.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mark.knigavuhe.playback.SleepOption
import dev.mark.knigavuhe.playback.SleepState
import dev.mark.knigavuhe.ui.components.Cover
import dev.mark.knigavuhe.util.formatDuration
import dev.mark.knigavuhe.util.formatLeft

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(viewModel: PlayerViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val sleepSheet by viewModel.sleepSheet.collectAsStateWithLifecycle()
    var speedSheet by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(Unit) { viewModel.restoreIfEmpty() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.bookTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.Close, contentDescription = "Свернуть")
                    }
                },
            )
        },
    ) { padding ->
        if (state.bookId == null) {
            Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                Text("Ничего не играет")
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            Cover(state.cover, modifier = Modifier.size(240.dp), corner = 16.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                state.chapterTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Глава ${state.chapterIndex + 1} из ${state.chapterCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { showChapters = true }.padding(4.dp),
            )

            Spacer(Modifier.height(16.dp))
            val duration = state.durationMs.coerceAtLeast(1)
            val value = scrub ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
            Slider(
                value = value,
                onValueChange = { scrub = it },
                onValueChangeFinished = {
                    scrub?.let { viewModel.seek((it * duration).toLong()) }
                    scrub = null
                },
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(state.positionMs), style = MaterialTheme.typography.labelSmall)
                Text(
                    "-${formatDuration((state.durationMs - state.positionMs).coerceAtLeast(0))}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                "Книга: осталось ${formatLeft((state.bookTotalMs - state.bookElapsedMs).coerceAtLeast(0))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconButton(onClick = viewModel::previous) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Предыдущая глава")
                }
                IconButton(onClick = viewModel::skipBack) {
                    Icon(Icons.Rounded.Replay10, contentDescription = "Назад 10 секунд")
                }
                FilledIconButton(onClick = viewModel::playPause, modifier = Modifier.size(72.dp)) {
                    if (state.isBuffering && !state.isPlaying) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (state.isPlaying) "Пауза" else "Играть",
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
                IconButton(onClick = viewModel::skipForward) {
                    Icon(Icons.Rounded.Forward30, contentDescription = "Вперёд 30 секунд")
                }
                IconButton(onClick = viewModel::next) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Следующая глава")
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AssistChip(
                    onClick = { speedSheet = true },
                    label = { Text("${state.speed}×") },
                    leadingIcon = { Icon(Icons.Rounded.Speed, contentDescription = null) },
                )
                AssistChip(
                    onClick = { viewModel.showSleepSheet(true) },
                    label = {
                        Text(
                            when (val sleep = state.sleep) {
                                is SleepState.Off -> "Таймер"
                                is SleepState.EndOfChapter -> "До конца главы"
                                is SleepState.Countdown -> formatDuration(sleep.remainingMs)
                            }
                        )
                    },
                    leadingIcon = { Icon(Icons.Rounded.Bedtime, contentDescription = null) },
                )
            }

            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (sleepSheet) {
        ModalBottomSheet(onDismissRequest = { viewModel.showSleepSheet(false) }) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                Text(
                    "Таймер сна",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                SleepOption.presets.forEach { option ->
                    val label = when (option) {
                        is SleepOption.Minutes -> "${option.value} минут"
                        is SleepOption.EndOfChapter -> "До конца главы"
                        is SleepOption.Off -> "Выключить"
                    }
                    TextButton(
                        onClick = {
                            viewModel.setSleep(option)
                            viewModel.showSleepSheet(false)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start) }
                }
                if (state.sleep != SleepState.Off) {
                    TextButton(
                        onClick = {
                            viewModel.setSleep(SleepOption.Off)
                            viewModel.showSleepSheet(false)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Выключить таймер", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start) }
                }
            }
        }
    }

    if (speedSheet) {
        ModalBottomSheet(onDismissRequest = { speedSheet = false }) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                Text(
                    "Скорость",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                val levels = state.speedLevels.ifEmpty {
                    listOf(0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)
                }
                levels.forEach { level ->
                    TextButton(
                        onClick = {
                            viewModel.setSpeed(level)
                            speedSheet = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("$level×", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start) }
                }
            }
        }
    }

    if (showChapters) {
        ModalBottomSheet(onDismissRequest = { showChapters = false }) {
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                itemsIndexed(chapters, key = { _, chapter -> chapter.id }) { index, chapter ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.playChapter(index)
                                showChapters = false
                            }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            chapter.title,
                            color = if (index == state.chapterIndex) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            formatDuration(chapter.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
