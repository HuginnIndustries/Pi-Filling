package industries.huginn.pifilling.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import industries.huginn.pifilling.runtime.AgentController.SessionState
import industries.huginn.pifilling.sandbox.SandboxState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiFillingApp(vm: AppViewModel) {
    val hasKey by vm.hasApiKey.collectAsStateWithLifecycle()
    val sandbox by vm.sandboxState.collectAsStateWithLifecycle()
    val session by vm.sessionState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Pi-Filling") }) },
        modifier = Modifier.fillMaxSize(),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
        ) {
            when {
                !hasKey -> ApiKeyEntry(onSave = vm::saveApiKey)
                sandbox !is SandboxState.Ready -> SandboxSetup(sandbox, onProvision = vm::provision)
                else -> SessionScreen(vm, session)
            }
        }
    }
}

@Composable
private fun ApiKeyEntry(onSave: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Anthropic API key", style = MaterialTheme.typography.titleMedium)
        Text(
            "Stored encrypted on-device (AndroidKeyStore). Never written to the sandbox filesystem; " +
                "passed to the agent only in memory at run start.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("sk-ant-...") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSave(key) }, enabled = key.isNotBlank()) {
            Text("Save key")
        }
    }
}

@Composable
private fun SandboxSetup(state: SandboxState, onProvision: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Linux sandbox", style = MaterialTheme.typography.titleMedium)
        when (state) {
            is SandboxState.NotInstalled -> {
                Text("Provision an Alpine Linux sandbox with Node + git (~one-time download).")
                Button(onClick = onProvision) { Text("Set up sandbox") }
            }
            is SandboxState.Downloading -> {
                Text("Downloading Alpine rootfs…")
                val frac = state.fraction
                if (frac != null) {
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            is SandboxState.Extracting -> ProgressRow("Extracting rootfs…")
            is SandboxState.Installing -> ProgressRow("Installing ${state.message}…")
            is SandboxState.Ready -> Text("Ready.")
            is SandboxState.Error -> {
                Text("Setup failed: ${state.message}", color = MaterialTheme.colorScheme.error)
                Button(onClick = onProvision) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun ProgressRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        Text(label)
    }
}

@Composable
private fun SessionScreen(vm: AppViewModel, session: SessionState) {
    val transcript by vm.transcript.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    // Default to a repo path inside the sandbox; a production UI lets the user pick/clone one.
    var repoPath by remember { mutableStateOf("/root/repo") }

    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        when (session) {
            is SessionState.Idle, is SessionState.Failed -> {
                OutlinedTextField(
                    value = repoPath,
                    onValueChange = { repoPath = it },
                    label = { Text("Repo path (inside sandbox)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.startSession(repoPath) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Start session")
                }
                if (session is SessionState.Failed) {
                    Spacer(Modifier.height(8.dp))
                    Text("Failed: ${session.message}", color = MaterialTheme.colorScheme.error)
                }
            }
            is SessionState.Provisioning, is SessionState.Connecting -> ProgressRow("Connecting…")
            is SessionState.Ready, is SessionState.Streaming -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(transcript) { line ->
                        Text(
                            text = (if (line.role == "you") "you: " else if (line.role == "system") "" else "") + line.text,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().imePadding(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("Ask the agent…") },
                        modifier = Modifier.weight(1f),
                    )
                    if (session is SessionState.Streaming) {
                        OutlinedButton(onClick = vm::abort) { Text("Stop") }
                    } else {
                        Button(
                            onClick = { vm.send(input); input = "" },
                            enabled = input.isNotBlank(),
                        ) { Text("Send") }
                    }
                }
                TextButton(onClick = vm::endSession) { Text("End session") }
            }
        }
    }
}
