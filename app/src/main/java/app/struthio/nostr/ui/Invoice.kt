package app.struthio.nostr.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.struthio.nostr.core.Invoice
import app.struthio.nostr.core.formatSats
import app.struthio.nostr.core.nowSeconds

/** A BOLT-11 invoice, wherever one appears. Paying always stops to ask first. */
@Composable
fun InvoiceCard(invoice: Invoice) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected by core.wallet.connected.collectAsState()
    var confirming by remember(invoice.text) { mutableStateOf(false) }
    var paying by remember(invoice.text) { mutableStateOf(false) }
    val paid = core.paidInvoices.containsKey(invoice.text)
    val left = invoice.expiresAt - nowSeconds()
    val amount = invoice.msats?.let(::formatSats)
    Surface(
        Modifier.padding(top = 10.dp).fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Bolt, null, tint = MaterialTheme.colorScheme.primary)
                Text(amount ?: "Any amount", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    when {
                        paid -> "Paid"
                        left <= 0 -> "Expired"
                        else -> "Expires in " + timeLeft(left)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Lightning invoice", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            invoice.memo?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!paid && left > 0) {
                    FilledTonalButton(
                        {
                            if (connected && invoice.msats != null) {
                                confirming = true
                            } else {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("lightning:" + invoice.text))) }
                                    .onFailure { nav.say("No wallet app found. Copy the invoice instead.") }
                            }
                        },
                        enabled = !paying,
                    ) { Text(if (paying) "Paying…" else if (connected && invoice.msats != null) "Pay" else "Open in wallet") }
                }
                OutlinedButton({
                    context.copy(invoice.text)
                    nav.say("Invoice copied")
                }) { Text("Copy") }
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Pay ${amount.orEmpty()}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    invoice.memo?.takeIf { it.isNotBlank() }?.let { Text(it) }
                    Text("This pays from your connected wallet and can't be undone.")
                }
            },
            confirmButton = {
                TextButton({
                    confirming = false
                    paying = true
                    scope.act(nav) {
                        try {
                            core.wallet.pay(invoice.text)
                            core.paidInvoices[invoice.text] = true
                            nav.say("Paid ${amount.orEmpty()}")
                        } finally {
                            paying = false
                        }
                    }
                }) { Text("Pay") }
            },
            dismissButton = { TextButton({ confirming = false }) { Text("Cancel") } },
        )
    }
}
