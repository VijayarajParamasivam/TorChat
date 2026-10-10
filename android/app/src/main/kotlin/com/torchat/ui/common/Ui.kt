package com.torchat.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.torchat.R
import com.torchat.databinding.DialogEditTextBinding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Copy to the clipboard. Android 13+ shows its own confirmation, so only older versions get a toast. */
fun Context.copyText(label: String, text: String, sensitive: Boolean = false) {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    clipboard.setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(R.string.copied)
}

fun Context.pasteText(): String? =
    getSystemService(ClipboardManager::class.java)?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(this)?.toString()

fun Context.toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

fun Context.toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

/** Collect while the fragment's view is at least started — the standard safe pattern. */
fun <T> Fragment.collectWhileStarted(flow: Flow<T>, block: suspend (T) -> Unit) {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            flow.collect { block(it) }
        }
    }
}

val Fragment.actionBar get() = (activity as? AppCompatActivity)?.supportActionBar

/** A one-field dialog, for renaming things. */
fun Fragment.promptText(
    title: Int,
    initial: String,
    maxLength: Int,
    onDone: (String) -> Unit,
) {
    val binding = DialogEditTextBinding.inflate(LayoutInflater.from(requireContext()))
    binding.input.setText(initial)
    binding.input.filters = arrayOf(android.text.InputFilter.LengthFilter(maxLength))
    binding.input.setSelection(binding.input.text?.length ?: 0)

    MaterialAlertDialogBuilder(requireContext())
        .setTitle(title)
        .setView(binding.root)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(android.R.string.ok) { _, _ ->
            onDone(binding.input.text?.toString().orEmpty().trim())
        }
        .show()
    binding.input.requestFocus()
}
