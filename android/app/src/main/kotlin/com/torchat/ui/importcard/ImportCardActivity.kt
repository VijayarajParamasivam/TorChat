package com.torchat.ui.importcard

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.torchat.MainActivity
import com.torchat.R
import com.torchat.core.card.Card
import com.torchat.core.card.PeerCard
import com.torchat.databinding.ActivityImportBinding
import com.torchat.service.Session
import com.torchat.service.TorChatService
import com.torchat.ui.common.IdenticonDrawable
import com.torchat.ui.common.toast
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A contact code shared to TorChat from another app.
 *
 * This is how codes usually arrive: a friend sends one over WhatsApp or email,
 * and "Share → TorChat" lands here. The code is verified before anything is
 * shown — a forged or truncated code never gets an Add button.
 */
class ImportCardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImportBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setFinishOnTouchOutside(true)

        binding.cancel.setOnClickListener { finish() }

        if (Session.loadIdentity(this) == null) {
            showMessage(getString(R.string.import_no_identity))
            binding.confirm.setText(R.string.open_torchat)
            binding.confirm.setOnClickListener {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            return
        }

        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val card = extractCard(text)?.let { runCatching { Card.parse(it) to it }.getOrNull() }
        if (card == null) {
            showMessage(getString(R.string.import_bad))
            binding.confirm.isVisible = false
            return
        }

        show(card.first)
        binding.confirm.setOnClickListener { add(card.second, card.first) }
    }

    private fun show(peer: PeerCard) {
        binding.avatar.setImageDrawable(IdenticonDrawable(peer.id))
        binding.name.text = peer.name
        binding.peerId.text = peer.id
    }

    private fun add(code: String, peer: PeerCard) {
        binding.confirm.isEnabled = false
        binding.progress.isVisible = true

        lifecycleScope.launch {
            // We are in the foreground, so starting the node here is allowed.
            if (Session.engine == null) TorChatService.start(this@ImportCardActivity)
            val engine = Session.engine ?: withTimeoutOrNull(START_WAIT_MS) {
                Session.engineFlow.filterNotNull().first()
            }

            val result = engine?.let { runCatching { it.addFriend(code) } }
            binding.progress.isVisible = false

            if (result?.isSuccess == true) {
                toast(getString(R.string.added_friend, peer.name))
                finish()
            } else {
                binding.confirm.isEnabled = true
                showMessage(result?.exceptionOrNull()?.message ?: getString(R.string.node_starting))
            }
        }
    }

    private fun showMessage(text: String) {
        binding.message.isVisible = true
        binding.message.text = text
    }

    private companion object {
        const val START_WAIT_MS = 8_000L

        /**
         * Messengers wrap shared text in their own words ("Here's my code: …"),
         * so find the card inside whatever arrived rather than demand it alone.
         */
        fun extractCard(text: String): String? =
            Regex("""TORCHAT1\.[A-Za-z0-9_\-=\s]+""").find(text)?.value?.replace(Regex("\\s+"), "")
    }
}
