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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voiceime.R
import com.example.voiceime.accessibility.ScreenContextService
import com.example.voiceime.ai.EngineState
import com.example.voiceime.ai.GemmaInferenceManager
import com.example.voiceime.dictionary.DictionaryDao
import com.example.voiceime.dictionary.DictionaryEntry
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class DictionaryViewModel @Inject constructor(
    val dictionaryDao: DictionaryDao,
    val gemmaManager: GemmaInferenceManager
) : ViewModel() {

    val confirmedTerms: StateFlow<List<DictionaryEntry>> = dictionaryDao.observeConfirmed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val candidateTerms: StateFlow<List<DictionaryEntry>> = dictionaryDao.observeCandidates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
}

// ── Activity ──────────────────────────────────────────────────────────────────

@AndroidEntryPoint
class DictionaryActivity : ComponentActivity() {

    private val viewModel: DictionaryViewModel by viewModels()

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* UI will re-read permission state via LaunchedEffect */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DictionaryScreen(
                    viewModel = viewModel,
                    onRequestMicPermission = { requestMicPermission.launch(Manifest.permission.RECORD_AUDIO) },
                    onOpenImeSettings = { openImeSettings() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() }
                )
            }
        }
    }

    private fun openImeSettings() {
        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

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
    var newTermInput by remember { mutableStateOf("") }

    // Live permission / service status
    val hasMic by remember {
        derivedStateOf {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }
    val imeEnabled = remember { mutableStateOf(false) }
    val accessibilityEnabled = remember { mutableStateOf(false) }
    val modelInstalled = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val imm = context.getSystemService(InputMethodManager::class.java)
        imeEnabled.value = imm.enabledInputMethodList.any {
            it.packageName == context.packageName
        }
        accessibilityEnabled.value = ScreenContextService.isConnected()
        modelInstalled.value = viewModel.gemmaManager.isModelInstalled()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("語音輸入鍵盤") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1976D2),
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

            // ── Setup status ──────────────────────────────────────────────────
            item {
                SetupCard(
                    hasMic = hasMic,
                    imeEnabled = imeEnabled.value,
                    accessibilityEnabled = accessibilityEnabled.value,
                    modelInstalled = modelInstalled.value,
                    onRequestMicPermission = onRequestMicPermission,
                    onOpenImeSettings = onOpenImeSettings,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings
                )
            }

            // ── Model status ──────────────────────────────────────────────────
            item {
                val state = viewModel.gemmaManager.state
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = when (state) {
                                    is EngineState.Ready -> Icons.Default.CheckCircle
                                    is EngineState.Error -> Icons.Default.Error
                                    else -> Icons.Default.HourglassEmpty
                                },
                                contentDescription = null,
                                tint = when (state) {
                                    is EngineState.Ready -> Color(0xFF4CAF50)
                                    is EngineState.Error -> Color(0xFFE53935)
                                    else -> Color(0xFFFFA000)
                                }
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Gemma 4 E2B（LiteRT-LM）", fontWeight = FontWeight.Medium)
                                Text(
                                    when (state) {
                                        is EngineState.Ready -> "已就緒（${state.backend}）"
                                        is EngineState.Error -> state.message
                                        is EngineState.Loading -> "載入中…"
                                        else -> "未初始化（首次使用鍵盤時自動載入）"
                                    },
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                        if (state is EngineState.Ready) {
                            Text(
                                "音訊：Android 離線 ASR → 粗轉錄 → Gemma 4 校正\n" +
                                "影像：畫面截圖 → Content.ImageBytes（Issue #1874 已修復）\n" +
                                "文字：無障礙服務擷取畫面上下文",
                                fontSize = 11.sp,
                                color = Color.Gray,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
            }

            // ── Add new term ──────────────────────────────────────────────────
            item {
                Text("自訂詞彙字典", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTermInput,
                        onValueChange = { newTermInput = it },
                        label = { Text(context.getString(R.string.dictionary_hint)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (newTermInput.isNotBlank()) {
                                viewModel.addTerm(newTermInput)
                                newTermInput = ""
                            }
                        }
                    ) { Text("新增") }
                }
            }

            // ── Confirmed terms list ──────────────────────────────────────────
            if (confirmedTerms.isEmpty()) {
                item {
                    Text(
                        context.getString(R.string.dictionary_empty),
                        color = Color.Gray,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
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
            }

            // ── Candidate terms (AI-suggested) ────────────────────────────────
            if (candidateTerms.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "AI 辨識到的新詞彙（建議加入）",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFF1976D2)
                    )
                }
                items(candidateTerms, key = { it.id }) { entry ->
                    CandidateRow(
                        term = entry.term,
                        onAccept = { viewModel.acceptCandidate(entry) },
                        onReject = { viewModel.rejectCandidate(entry) }
                    )
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun SetupCard(
    hasMic: Boolean,
    imeEnabled: Boolean,
    accessibilityEnabled: Boolean,
    modelInstalled: Boolean,
    onRequestMicPermission: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    val allDone = hasMic && imeEnabled && accessibilityEnabled && modelInstalled
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (allDone) Color(0xFFE8F5E9) else Color(0xFFFFF8E1)
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("設定檢查", fontWeight = FontWeight.Bold)

            SetupRow(
                done = hasMic,
                label = "麥克風權限",
                actionLabel = if (!hasMic) "授予" else null,
                onAction = onRequestMicPermission
            )
            SetupRow(
                done = imeEnabled,
                label = "已設為輸入法",
                actionLabel = if (!imeEnabled) "開啟設定" else null,
                onAction = onOpenImeSettings
            )
            SetupRow(
                done = accessibilityEnabled,
                label = "無障礙服務已啟用",
                actionLabel = if (!accessibilityEnabled) "開啟設定" else null,
                onAction = onOpenAccessibilitySettings
            )
            SetupRow(
                done = modelInstalled,
                label = "Gemma 模型已安裝",
                actionLabel = null,
                onAction = {}
            )
            if (!modelInstalled) {
                Text(
                    "請將 model.litertlm 複製到：\nAndroid/data/com.example.voiceime/files/",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun SetupRow(done: Boolean, label: String, actionLabel: String?, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (done) Color(0xFF4CAF50) else Color(0xFFFFA000),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        if (!done && actionLabel != null) {
            TextButton(onClick = onAction) { Text(actionLabel, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun TermRow(term: String, usageCount: Int, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(term, modifier = Modifier.weight(1f), fontSize = 15.sp)
        if (usageCount > 0) {
            Text("×$usageCount", fontSize = 11.sp, color = Color.Gray)
            Spacer(Modifier.width(8.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "刪除", tint = Color(0xFFE53935))
        }
    }
}

@Composable
private fun CandidateRow(term: String, onAccept: () -> Unit, onReject: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF3F8FF), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.AutoAwesome, contentDescription = null,
            tint = Color(0xFF1976D2), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(term, modifier = Modifier.weight(1f), fontSize = 15.sp)
        TextButton(onClick = onAccept) { Text("加入", color = Color(0xFF1976D2)) }
        TextButton(onClick = onReject) { Text("略過", color = Color.Gray) }
    }
}
