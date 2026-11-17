package com.projetosuper.lab

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class LaunchableApp(val label: String, val packageName: String)

class MainActivity : ComponentActivity() {
    private fun loadLaunchableApps(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map {
                LaunchableApp(
                    label = it.loadLabel(packageManager).toString(),
                    packageName = it.activityInfo.packageName
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun openApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        startActivity(launchIntent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val apps = remember { mutableStateOf(loadLaunchableApps()) }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Projeto Super", style = MaterialTheme.typography.headlineMedium)
                        Text("Laboratório Android — gerenciamento transparente")
                        Text(
                            "Lista somente apps exibidos no launcher. Nenhum aplicativo é monitorado, " +
                                "bloqueado ou controlado em segundo plano."
                        )
                        Button(
                            onClick = { apps.value = loadLaunchableApps() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Atualizar lista") }
                        Text("Aplicativos encontrados: ${apps.value.size}")
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(apps.value, key = { it.packageName }) { app ->
                                Card(Modifier.fillMaxWidth()) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(app.label, style = MaterialTheme.typography.titleMedium)
                                            Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                                        }
                                        Button(onClick = { openApp(app.packageName) }) {
                                            Text("Abrir")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
