package dev.yucj.videosplitter

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yucj.videosplitter.split.SplitJobState
import dev.yucj.videosplitter.split.SplitMode
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen(viewModel)
            }
        }
    }
}

@Composable
private fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val jobState by viewModel.jobState.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()

    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        uri?.let(viewModel::onVideoPicked)
    }

    val requestNotificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // 通知權限只影響進度通知顯示，拒絕也照樣開工。
        viewModel.startSplit()
    }

    val isRunning = jobState is SplitJobState.Running

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val update = updateState) {
                UpdateUiState.Hidden, UpdateUiState.Checking, UpdateUiState.UpToDate -> Row {
                    OutlinedButton(
                        onClick = viewModel::checkForUpdate,
                        enabled = update != UpdateUiState.Checking,
                    ) {
                        Text(
                            stringResource(
                                when (update) {
                                    UpdateUiState.Checking -> R.string.update_checking
                                    UpdateUiState.UpToDate -> R.string.update_up_to_date
                                    else -> R.string.update_check
                                },
                            ),
                        )
                    }
                }

                is UpdateUiState.Available -> Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.update_available, update.info.version))
                        Button(onClick = viewModel::downloadAndInstallUpdate) {
                            Text(stringResource(R.string.update_action))
                        }
                    }
                }

                is UpdateUiState.Downloading -> Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.update_downloading, update.progress))
                        LinearProgressIndicator(
                            progress = { update.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                is UpdateUiState.Failed -> Column {
                    Text(
                        stringResource(R.string.update_failed, update.message),
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = viewModel::checkForUpdate) {
                        Text(stringResource(R.string.update_check))
                    }
                }
            }

            Button(
                onClick = {
                    pickVideo.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.VideoOnly,
                        ),
                    )
                },
                enabled = !isRunning,
            ) {
                Text(stringResource(R.string.pick_video))
            }

            uiState.loadError?.let {
                Text(stringResource(R.string.load_error, it), color = MaterialTheme.colorScheme.error)
            }

            uiState.durationMs?.let { durationMs ->
                Text(stringResource(R.string.video_duration, durationMs / 1000.0))

                Text(stringResource(R.string.max_seconds_label, uiState.maxSegmentSec))
                Slider(
                    value = uiState.maxSegmentSec.toFloat(),
                    onValueChange = { viewModel.onMaxSecondsChanged(it.roundToInt()) },
                    valueRange = 15f..90f,
                    steps = 74,
                    enabled = !isRunning,
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = uiState.mode == SplitMode.EVEN,
                        onClick = { viewModel.onModeChanged(SplitMode.EVEN) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        enabled = !isRunning,
                    ) { Text(stringResource(R.string.mode_even)) }
                    SegmentedButton(
                        selected = uiState.mode == SplitMode.FIXED,
                        onClick = { viewModel.onModeChanged(SplitMode.FIXED) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        enabled = !isRunning,
                    ) { Text(stringResource(R.string.mode_fixed)) }
                }

                val segments = uiState.segments
                if (segments.isNotEmpty()) {
                    val avgSec = segments.map { it.durationMs }.average() / 1000.0
                    Text(
                        when (uiState.mode) {
                            SplitMode.EVEN ->
                                stringResource(R.string.preview_even, segments.size, avgSec)

                            SplitMode.FIXED ->
                                stringResource(
                                    R.string.preview_fixed,
                                    segments.size,
                                    uiState.maxSegmentSec,
                                    segments.last().durationMs / 1000.0,
                                )
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                if (!isRunning) {
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.startSplit()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.start_split))
                    }
                }
            }

            when (val state = jobState) {
                is SplitJobState.Running -> {
                    Text(
                        stringResource(
                            R.string.running_progress,
                            state.currentSegment,
                            state.totalSegments,
                            state.segmentProgress,
                        ),
                    )
                    LinearProgressIndicator(
                        progress = {
                            ((state.currentSegment - 1) * 100f + state.segmentProgress) /
                                (state.totalSegments * 100f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = viewModel::cancelSplit) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }

                is SplitJobState.Finished -> {
                    val okCount = state.results.count { it.success }
                    Text(
                        stringResource(R.string.finished_summary, okCount, state.results.size),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.results, key = { it.index }) { result ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(modifier = Modifier.padding(12.dp)) {
                                    Text(if (result.success) "✅ " else "❌ ")
                                    Column {
                                        Text(result.fileName)
                                        if (!result.success) {
                                            Text(
                                                result.error.orEmpty(),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Text(stringResource(R.string.saved_location_hint))
                    OutlinedButton(onClick = viewModel::resetJobState) {
                        Text(stringResource(R.string.action_reset))
                    }
                }

                is SplitJobState.Failed -> {
                    Text(
                        stringResource(R.string.job_failed, state.message),
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = viewModel::resetJobState) {
                        Text(stringResource(R.string.action_reset))
                    }
                }

                SplitJobState.Cancelled -> {
                    Text(stringResource(R.string.job_cancelled))
                    OutlinedButton(onClick = viewModel::resetJobState) {
                        Text(stringResource(R.string.action_reset))
                    }
                }

                SplitJobState.Idle -> Unit
            }
        }
    }
}
