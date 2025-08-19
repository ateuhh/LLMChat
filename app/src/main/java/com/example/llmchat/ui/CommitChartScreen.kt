package com.example.llmchat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.widget.Toast

@Composable
fun CommitChartScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var building by remember { mutableStateOf(false) }
    var imgUrl by remember {
        // 10.0.2.2 — доступ к «localhost» хоста из Android-эмулятора
        mutableStateOf("http://10.0.2.2:8766/commit_chart.png")
    }

    Column(modifier.padding(16.dp)) {
        Button(
            onClick = {
                if (building) return@Button
                building = true
                scope.launch {
                    val ok = triggerBuildChart()
                    building = false
                    if (ok) {
                        // Обновить URL, чтобы обойти кеш
                        imgUrl = "http://10.0.2.2:8766/commit_chart.png?ts=${System.currentTimeMillis()}"
                        Toast.makeText(ctx, "График обновлён", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, "Не удалось построить график", Toast.LENGTH_LONG).show()
                    }
                }
            },
            enabled = !building
        ) {
            Text(if (building) "Строю…" else "Построить график коммитов")
        }

        Spacer(Modifier.height(16.dp))

        AsyncImage(
            model = imgUrl,
            contentDescription = "Commits per day (last 7 days)",
            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp)
        )
    }
}

private suspend fun triggerBuildChart(): Boolean = withContext(Dispatchers.IO) {
    val client = OkHttpClient()
    val body = "{}".toRequestBody("application/json".toMediaType())
    val req = Request.Builder()
        .url("http://10.0.2.2:8766/build-chart")
        .post(body)
        .build()
    try {
        client.newCall(req).execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }
}
