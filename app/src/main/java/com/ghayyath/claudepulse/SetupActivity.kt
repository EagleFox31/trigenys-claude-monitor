package com.ghayyath.claudepulse

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.concurrent.Executors

/** Phone-only setup for four isolated Claude OAuth sessions. */
class SetupActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var picker: Spinner
    private lateinit var status: TextView
    private lateinit var codeInput: EditText
    private lateinit var verifyButton: Button
    private var accounts = listOf<AccountVault.Account>()
    private var selectedId = "ibrahim"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.rgb(16, 22, 34))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(label("TRIGENYS • CLAUDE MONITOR", 20f, Color.WHITE))
        root.addView(label("Quatre comptes indépendants, un seul widget.", 13f, 0xFFB0BFCE.toInt()))
        root.addView(label("Compte à connecter", 15f, Color.WHITE))

        picker = Spinner(this).apply { setBackgroundColor(Color.WHITE) }
        root.addView(picker)
        picker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                accounts.getOrNull(position)?.let { selectedId = it.id }
                updateStatus()
            }
        }

        root.addView(button("Renommer le compte") { renameSelected() })
        root.addView(button("Se connecter à Claude") {
            try {
                val url = TokenManager.buildLoginUrl(this, selectedId)
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                status.text = "Autorise la connexion puis copie le code complet, avec #state."
            } catch (_: Exception) { status.text = "Impossible de démarrer la connexion." }
        })
        root.addView(label("Code d'autorisation du compte sélectionné", 12f, Color.WHITE))
        codeInput = EditText(this).apply {
            hint = "Coller code#state"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(Color.BLACK)
            setHintTextColor(Color.DKGRAY)
            setBackgroundColor(Color.WHITE)
        }
        root.addView(codeInput)
        verifyButton = button("Valider la connexion") { validateCode() }
        root.addView(verifyButton)
        root.addView(button("Actualiser les quatre comptes") {
            PulseWidget.scheduleRefresh(this)
            PulseWidget.requestRefresh(this)
            status.text = "Actualisation demandée. Le widget conservera la dernière mesure fiable."
        })
        root.addView(button("Déconnecter ce compte") { confirmDisconnect() })
        status = label("", 13f, 0xFFC9DDEB.toInt())
        root.addView(status)
        root.addView(label(
            "Sessions chiffrées sur ce téléphone. Aucune VM, aucun serveur. " +
                "Rafraîchissement Android périodique, non garanti à l'heure exacte. " +
                "API de quotas Anthropic non documentée.",
            12f, 0xFF94A5BB.toInt()
        ))
        try {
            AccountVault.ensureDefaults(this)
            reloadAccounts()
            PulseWidget.scheduleRefresh(this)
        } catch (_: Exception) {
            status.text = "Android Keystore indisponible. Les identifiants ne sont pas effacés."
            verifyButton.isEnabled = false
        }
    }

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun button(value: String, onPress: () -> Unit) = Button(this).apply {
        text = value
        setOnClickListener { onPress() }
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()

    private fun reloadAccounts() {
        accounts = AccountVault.read(this)
        picker.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, accounts.map { it.label }
        )
        picker.setSelection(accounts.indexOfFirst { it.id == selectedId }.coerceAtLeast(0))
        updateStatus()
    }

    private fun updateStatus() {
        if (!::status.isInitialized) return
        val account = try { AccountVault.find(this, selectedId) } catch (_: Exception) { null }
        if (account == null) {
            status.text = "Coffre non accessible"
            return
        }
        val last = UsageCache.get(this, account.id)
        val err = UsageCache.error(this, account.id)
        val session = last?.limits?.firstOrNull { it.label == "Session" }
        val week = last?.limits?.firstOrNull { it.label == "Weekly" }
        status.text = account.label + " — " + (if (account.connected) "Connecté" else "Non connecté") +
            "\nSession : " + (session?.percent?.toString()?.plus("%") ?: "—") +
            "  •  Semaine : " + (week?.percent?.toString()?.plus("%") ?: "—") +
            "\nDernière mesure : " + TimeLabels.age(last?.cachedAt) +
            (if (err != null) "\nErreur : " + err else "")
    }

    private fun renameSelected() {
        val account = AccountVault.find(this, selectedId) ?: return
        val input = EditText(this).apply { setText(account.label); setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Nom du compte").setView(input)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Enregistrer") { _, _ ->
                val name = input.text.toString().trim().take(40)
                if (name.isNotBlank()) {
                    AccountVault.update(this, account.id) { it.copy(label = name) }
                    reloadAccounts()
                    PulseWidget.updateAll(this)
                }
            }.show()
    }

    private fun validateCode() {
        val id = selectedId
        val code = codeInput.text.toString().trim()
        if (!code.contains("#")) {
            status.text = "Colle le code complet au format code#state."
            return
        }
        verifyButton.isEnabled = false
        status.text = "Validation en cours…"
        val ctx = applicationContext
        executor.execute {
            val error = try { TokenManager.completeLogin(ctx, id, code) }
                catch (_: Exception) { "login_unavailable" }
            val reading = if (error == null) ApiClient.fetchUsage(ctx, id) else null
            if (reading != null) {
                if (reading.error == null) UsageCache.save(ctx, id, reading)
                else UsageCache.setError(ctx, id, reading.error)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                verifyButton.isEnabled = true
                if (error == null) {
                    codeInput.text.clear()
                    PulseWidget.scheduleRefresh(ctx)
                    PulseWidget.updateAll(ctx)
                    status.text = if (reading?.error == null)
                        "Compte connecté. Quotas récupérés." else
                        "Compte connecté. Quotas temporairement indisponibles."
                } else {
                    status.text = "Connexion échouée : " + error
                }
            }
        }
    }

    private fun confirmDisconnect() {
        val account = AccountVault.find(this, selectedId) ?: return
        AlertDialog.Builder(this).setTitle("Déconnecter " + account.label + " ?")
            .setMessage("Les jetons et les relevés de ce compte seront supprimés.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Déconnecter") { _, _ ->
                TokenManager.disconnect(this, account.id)
                UsageCache.clear(this, account.id)
                updateStatus()
                PulseWidget.updateAll(this)
            }.show()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) updateStatus()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
