package app.struthio.nostr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.NumberFormat

@Composable
fun WalletScreen(pending: String?, onConsumed: () -> Unit) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val wallet = core.wallet
    val connected by wallet.connected.collectAsState()
    val balance by wallet.balance.collectAsState()
    val transactions by wallet.transactions.collectAsState()
    var sheet by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pending) {
        if (pending != null) {
            scope.act(nav) { wallet.connect(pending) }
            onConsumed()
        }
    }
    LaunchedEffect(connected) {
        // A saved connection that could not be reached at launch gets another try here.
        scope.act(nav) { if (connected) wallet.refresh() else wallet.restore() }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Wallet", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
        if (!connected) {
            ConnectWallet()
            return@Column
        }
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceBright,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text("Balance", style = MaterialTheme.typography.labelLarge)
                Text(
                    balance?.let { NumberFormat.getIntegerInstance().format(it / 1000) + " sats" } ?: "—",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ sheet = "send" }, Modifier.weight(1f)) { Text("Send") }
                    FilledTonalButton({ sheet = "receive" }, Modifier.weight(1f)) { Text("Receive") }
                }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(transactions) { index, t ->
                CardRow(
                    segmentShape(index, transactions.size),
                    null,
                    headlineContent = {
                        Text(NumberFormat.getIntegerInstance().format(t.msats / 1000) + " sats")
                    },
                    supportingContent = t.description.takeIf { it.isNotBlank() }?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    leadingContent = {
                        Icon(
                            if (t.incoming) Icons.AutoMirrored.Rounded.CallReceived else Icons.AutoMirrored.Rounded.CallMade,
                            if (t.incoming) "Received" else "Sent",
                        )
                    },
                    trailingContent = { Text(relativeTime(t.createdAt)) },
                )
            }
            item {
                TextButton({ wallet.disconnect() }, Modifier.padding(8.dp)) { Text("Disconnect wallet") }
            }
        }
    }
    when (sheet) {
        "send" -> SendSheet { sheet = null }
        "receive" -> ReceiveSheet { sheet = null }
    }
}

@Composable
private fun ConnectWallet() {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceBright,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connect a Lightning wallet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Paste a Nostr Wallet Connect link from your wallet. Your funds stay there; Struthio only asks it to pay or create invoices.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(uri, { uri = it }, label = { Text("nostr+walletconnect://…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    busy = true
                    scope.act(nav) {
                        try {
                            core.wallet.connect(uri)
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = uri.isNotBlank() && !busy,
            ) { Text(if (busy) "Connecting…" else "Connect") }
        }
    }
}

@Composable
private fun SendSheet(onDismiss: () -> Unit) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Send", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(target, { target = it }, label = { Text("Invoice or Lightning address") }, modifier = Modifier.fillMaxWidth())
            if (target.contains('@')) {
                OutlinedTextField(
                    amount,
                    { amount = it.filter(Char::isDigit).take(9) },
                    label = { Text("Amount in sats") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                onClick = {
                    busy = true
                    scope.act(nav) {
                        try {
                            core.wallet.send(target, amount.toLongOrNull())
                            nav.say("Payment sent")
                            onDismiss()
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = target.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Sending…" else "Pay") }
        }
    }
}

@Composable
private fun ReceiveSheet(onDismiss: () -> Unit) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var amount by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    var invoice by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Receive", style = MaterialTheme.typography.titleLarge)
            val ready = invoice
            if (ready == null) {
                OutlinedTextField(
                    amount,
                    { amount = it.filter(Char::isDigit).take(9) },
                    label = { Text("Amount in sats") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(memo, { memo = it }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = { scope.act(nav) { invoice = core.wallet.makeInvoice(amount.toLong(), memo) } },
                    enabled = (amount.toLongOrNull() ?: 0) > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Create invoice") }
            } else {
                Text(ready, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
                Row {
                    Button({
                        context.copy(ready)
                        nav.say("Invoice copied")
                    }) { Text("Copy invoice") }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}
