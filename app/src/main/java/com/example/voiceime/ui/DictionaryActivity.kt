package com.example.voiceime.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.example.voiceime.R
import com.example.voiceime.accessibility.ScreenContextService
import com.example.voiceime.ai.DeviceCapability
import com.example.voiceime.ai.EngineState
import com.example.voiceime.ai.GemmaInferenceManager
import com.example.voiceime.ai.ModelDownloadManager
import com.example.voiceime.ai.ModelDownloadManager.DownloadState
import com.example.voiceime.dictionary.DictionaryDao
import com.example.voiceime.dictionary.DictionaryEntry
import com.example.voiceime.preferences.ModalitySettings
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class DictionaryViewModel @Inject constructor(
    val dictionaryDao: DictionaryDao,
    val gemmaManager: GemmaInferenceManager,
    val modelDownload: ModelDownloadManager,
    val deviceCapability: DeviceCapability,
    private val modalitySettings: ModalitySettings
) : ViewModel() {

    val confirmedTerms: StateFlow<List<DictionaryEntry>> = dictionaryDao.observeConfirmed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val candidateTerms: StateFlow<List<DictionaryEntry>> = dictionaryDao.observeCandidates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var useScreenText by mutableStateOf(modalitySettings.useScreenText)
        private set

    var useScreenshot by mutableStateOf(modalitySettings.useScreenshot)
        private set

    fun setUseScreenText(enabled: Boolean) {
        useScreenText = enabled
        modalitySettings.useScreenText = enabled
    }

    fun setUseScreenshot(enabled: Boolean) {
        useScreenshot = enabled
        modalitySettings.useScreenshot = enabled
    }

    fun addTerm(term: String) = viewModelScope.launch {
        if (term.isBlank()) return@launch
        dictionaryDao.addConfirmedTerm(term.trim())
    }

    fun deleteTerm(entry: DictionaryEntry) = viewModelScope.launch {
        dictionaryDao.delete(entry)
    }

    fun acceptCandidate(entry: DictionaryEntry) = viewModelScope.launch {
        dictionaryDao.confirmCandidate(entry.id)
    }

    fun rejectCandidate(entry: DictionaryEntry) = viewModelScope.launch {
        dictionaryDao.delete(entry)
    }

    /** Trigger model init after a successful download. */
    fun initModelAfterDownload() = viewModelScope.launch(Dispatchers.IO) {
        gemmaManager.initialize()
    }
}

// ── Activity ──────────────────────────────────────────────────────────────────

@AndroidEntryPoint
class DictionaryActivity : ComponentActivity() {

    private val viewModel: DictionaryViewModel by viewModels()

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* UI re-reads permission state via LaunchedEffect */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                DictionaryScreen(
                    viewModel = viewModel,
                    onRequestMicPermission = { requestMicPermission.launch(Manifest.permission.RECORD_AUDIO) },
                    onOpenImeSettings = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                    onOpenAccessibilitySettings = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                )
            }
        }
    }
}

// ── Root screen ────────────────────────────────────────────────────────────────

@Composable
private fun DictionaryScreen(
    viewModel: DictionaryViewModel,
    onRequestMicPermission: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    val context = LocalContext.current
    val confirmedTerms by viewModel.confirmedTerms.collectAsState()
    val candidateTerms by viewModel.candidateTerms.collectAsState()
    val downloadState by viewModel.modelDownload.state.collectAsState()
    var newTermInput by remember { mutableStateOf("") }

    val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    val imeEnabled = remember { mutableStateOf(false) }
    val accessibilityEnabled = remember { mutableStateOf(false) }

    // Re-check on every onResume so returning from system settings immediately
    // reflects the updated IME / accessibility state in the setup checklist.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val imm = context.getSystemService(InputMethodManager::class.java)
            imeEnabled.value = imm.enabledInputMethodList.any { it.packageName == context.packageName }
            accessibilityEnabled.value = ScreenContextService.isConnected()
        }
    }

    // Trigger model init once download succeeds
    LaunchedEffect(downloadState) {
        if (downloadState is DownloadState.Done) viewModel.initModelAfterDownload()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("語音輸入鍵盤", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1565C0),
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {

            // ── Model download card ───────────────────────────────────────────
            item {
                ModelDownloadCard(
                    downloadState = downloadState,
                    isInstalled = viewModel.gemmaManager.isModelInstalled()
                            || downloadState is DownloadState.Done,
                    engineState = viewModel.gemmaManager.state,
                    ramTier = viewModel.deviceCapability.tier,
                    socVendor = viewModel.gemmaManager.socVendorLabel,
                    onDownload = { viewModel.modelDownload.startDownload() },
                    onCancel = { viewModel.modelDownload.cancelDownload() }
                )
            }

            // ── Setup checklist ───────────────────────────────────────────────
            item {
                SetupChecklistCard(
                    hasMic = hasMic,
                    imeEnabled = imeEnabled.value,
                    accessibilityEnabled = accessibilityEnabled.value,
                    onRequestMicPermission = onRequestMicPermission,
                    onOpenImeSettings = onOpenImeSettings,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings
                )
            }

            // ── Modality settings card ────────────────────────────────────────
            item {
                ModalitySettingsCard(
                    useScreenText = viewModel.useScreenText,
                    useScreenshot = viewModel.useScreenshot,
                    onScreenTextChange = viewModel::setUseScreenText,
                    onScreenshotChange = viewModel::setUseScreenshot
                )
            }

            // ── Dictionary section ────────────────────────────────────────────
            item {
                Text(
                    "自訂詞彙字典",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTermInput,
                        onValueChange = { newTermInput = it },
                        label = { Text(context.getString(R.string.dictionary_hint)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (newTermInput.isNotBlank()) {
                                viewModel.addTerm(newTermInput)
                                newTermInput = ""
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("新增") }
                }
            }

            if (confirmedTerms.isEmpty() && candidateTerms.isEmpty()) {
                item {
                    Text(
                        context.getString(R.string.dictionary_empty),
                        color = Color.Gray,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            } else {
                items(confirmedTerms, key = { it.id }) { entry ->
                    TermRow(
                        term = entry.term,
                        usageCount = entry.usageCount,
                        onDelete = { viewModel.deleteTerm(entry) }
                    )
                }

                if (candidateTerms.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "AI 辨識到的新詞彙（建議加入）",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color(0xFF1565C0)
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    items(candidateTerms, key = { it.id }) { entry ->
                        CandidateRow(
                            term = entry.term,
                            onAccept = { viewModel.acceptCandidate(entry) },
                            onReject = { viewModel.rejectCandidate(entry) }
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

// ── Model download card ────────────────────────────────────────────────────────

@Composable
private fun ModelDownloadCard(
    downloadState: DownloadState,
    isInstalled: Boolean,
    engineState: EngineState,
    ramTier: Int,
    socVendor: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit
) {
    val isDownloading = downloadState is DownloadState.Downloading
    val isFailed = downloadState is DownloadState.Failed

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isInstalled -> Color(0xFFE8F5E9)
                isFailed -> Color(0xFFFFEBEE)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        isInstalled -> Icons.Default.CheckCircle
                        isFailed -> Icons.Default.Error
                        isDownloading -> Icons.Default.Downloading
                        else -> Icons.Default.Memory
                    },
                    contentDescription = null,
                    tint = when {
                        isInstalled -> Color(0xFF2E7D32)
                        isFailed -> Color(0xFFC62828)
                        isDownloading -> Color(0xFF1565C0)
                        else -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Gemma 4 E2B（LiteRT-LM）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Google 開源模型 · 約 ${ModelDownloadManager.MODEL_SIZE_GB} GB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Download progress
            if (isDownloading) {
                val dl = downloadState as DownloadState.Downloading
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${dl.downloadedMb} / ${if (dl.totalMb > 0) "${dl.totalMb} MB" else "計算中…"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${(dl.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    LinearProgressIndicator(
                        progress = { dl.progress },
                        modifier = Modifier.fillMaxWidth(),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            // Installed: show engine state
            if (isInstalled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (engineState) {
                            is EngineState.Ready -> Icons.Default.Done
                            is EngineState.Loading -> Icons.Default.HourglassEmpty
                            is EngineState.Error -> Icons.Default.Warning
                            else -> Icons.Default.HourglassEmpty
                        },
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (engineState) {
                            is EngineState.Ready -> "模型已就緒（${engineState.backend}）"
                            is EngineState.Loading -> "模型載入中…"
                            is EngineState.Error -> "載入失敗：${engineState.message}"
                            else -> "模型已安裝，首次使用鍵盤時自動載入"
                        },
                        fontSize = 12.sp,
                        color = Color(0xFF2E7D32)
                    )
                }

                // RAM tier indicator
                Text(
                    "裝置等級 Tier $ramTier（截圖解析度 ${intArrayOf(224, 336, 448)[ramTier]}px）",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
                if (socVendor.isNotEmpty()) {
                    Text("SoC：$socVendor", fontSize = 11.sp, color = Color.Gray)
                }
            }

            // Failed: show error
            if (isFailed) {
                Text(
                    "⚠ ${(downloadState as DownloadState.Failed).error}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFC62828)
                )
            }

            // Action buttons
            if (!isInstalled) {
                Button(
                    onClick = if (isDownloading) onCancel else onDownload,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = if (isDownloading)
                        ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                    else
                        ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                ) {
                    Icon(
                        imageVector = if (isDownloading) Icons.Default.Cancel else Icons.Default.Download,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            isDownloading -> "取消下載"
                            isFailed -> "重新下載"
                            else -> "一鍵下載 Gemma 4 模型（約 2 GB）"
                        },
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }
    }
}

// ── Modality settings card ────────────────────────────────────────────────────

@Composable
private fun ModalitySettingsCard(
    useScreenText: Boolean,
    useScreenshot: Boolean,
    onScreenTextChange: (Boolean) -> Unit,
    onScreenshotChange: (Boolean) -> Unit
) {
    val audioOnly = !useScreenText && !useScreenshot
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (audioOnly) Color(0xFFFFF3E0) else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "輔助模態設定",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Text(
                if (audioOnly) "純語音模式：Gemma 僅依語音辨識結果推斷文字"
                else "已啟用畫面輔助，Gemma 可參考畫面資訊修正辨識結果",
                fontSize = 12.sp,
                color = if (audioOnly) Color(0xFFE65100) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            ModalityToggleRow(
                icon = Icons.Default.TextFields,
                label = "畫面文字獲取",
                description = "透過無障礙服務讀取當前畫面文字，用於修正諧音錯誤的專有名詞",
                checked = useScreenText,
                onCheckedChange = onScreenTextChange
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            ModalityToggleRow(
                icon = Icons.Default.Screenshot,
                label = "畫面截圖獲取",
                description = "擷取低解析度截圖，讓 Gemma 理解當前使用情境（需無障礙服務）",
                checked = useScreenshot,
                onCheckedChange = onScreenshotChange
            )
        }
    }
}

@Composable
private fun ModalityToggleRow(
    icon: ImageVector,
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (checked) MaterialTheme.colorScheme.primary else Color.Gray,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal)
            Text(description, fontSize = 11.sp, color = Color.Gray, lineHeight = 14.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

// ── Setup checklist card ──────────────────────────────────────────────────────

@Composable
private fun SetupChecklistCard(
    hasMic: Boolean,
    imeEnabled: Boolean,
    accessibilityEnabled: Boolean,
    onRequestMicPermission: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    val allDone = hasMic && imeEnabled && accessibilityEnabled
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (allDone) Color(0xFFE8F5E9) else Color(0xFFFFF8E1)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (allDone) "✅ 設定完成，可以開始使用！" else "設定步驟",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            SetupRow(
                done = hasMic, icon = Icons.Default.Mic,
                label = "麥克風權限",
                actionLabel = if (!hasMic) "授予" else null,
                onAction = onRequestMicPermission
            )
            SetupRow(
                done = imeEnabled, icon = Icons.Default.Keyboard,
                label = "設為預設輸入法",
                actionLabel = if (!imeEnabled) "開啟設定" else null,
                onAction = onOpenImeSettings
            )
            SetupRow(
                done = accessibilityEnabled, icon = Icons.Default.Accessibility,
                label = "無障礙服務已啟用",
                actionLabel = if (!accessibilityEnabled) "開啟設定" else null,
                onAction = onOpenAccessibilitySettings
            )
        }
    }
}

@Composable
private fun SetupRow(
    done: Boolean,
    icon: ImageVector,
    label: String,
    actionLabel: String?,
    onAction: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (done) Icons.Default.CheckCircle else icon,
            contentDescription = null,
            tint = if (done) Color(0xFF2E7D32) else Color(0xFFFFA000),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        if (!done && actionLabel != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, fontSize = 12.sp, color = Color(0xFF1565C0))
            }
        }
    }
}

// ── Dictionary rows ───────────────────────────────────────────────────────────

@Composable
private fun TermRow(term: String, usageCount: Int, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(term, modifier = Modifier.weight(1f), fontSize = 15.sp)
        if (usageCount > 0) {
            Text("×$usageCount", fontSize = 11.sp, color = Color.Gray)
            Spacer(Modifier.width(6.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "刪除", tint = Color(0xFFE53935), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun CandidateRow(term: String, onAccept: () -> Unit, onReject: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF3F8FF), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.AutoAwesome, contentDescription = null,
            tint = Color(0xFF1565C0), modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(term, modifier = Modifier.weight(1f), fontSize = 15.sp)
        TextButton(onClick = onAccept) { Text("加入", color = Color(0xFF1565C0), fontSize = 13.sp) }
        TextButton(onClick = onReject) { Text("略過", color = Color.Gray, fontSize = 13.sp) }
    }
}
