package com.torchat.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * One-time move from the JSON files earlier builds kept to the database.
 *
 * Friends and conversations exist nowhere but on this phone, so a storage
 * change that left them behind would be data loss, not a migration. The old
 * directory is renamed rather than deleted once its contents are in: if
 * anything here is ever wrong, the originals are still on disk.
 */
object LegacyImport {

    private const val TAG = "TorChat"

    fun runOnce(context: Context, store: SqlStore) {
        val legacy = File(context.filesDir, "torchat")
        if (!legacy.isDirectory) return

        runCatching {
            val friendsFile = File(legacy, "friends.json")
            if (friendsFile.exists()) {
                val friends = Records.friendsFromJson(friendsFile.readText())
                // Merge rather than replace, in case the database already has
                // someone added since — never drop a friend on the floor.
                val present = store.readFriends().associateBy { it.id }
                store.writeFriends((present + friends.associateBy { it.id }).values.toList())
            }

            legacy.listFiles { file -> file.name.startsWith("conv-") && file.name.endsWith(".json") }
                ?.forEach { file ->
                    // IDs are TOR- plus Crockford base32 and hyphens, which the
                    // old file names kept verbatim.
                    val peerId = file.name.removePrefix("conv-").removeSuffix(".json")
                    for (message in Records.messagesFromJson(file.readText())) {
                        if (!store.hasMessage(peerId, message.id, message.mine)) {
                            store.appendMessage(peerId, message)
                        }
                    }
                    // Anything imported was already seen in the old app.
                    store.markRead(peerId)
                }

            val moved = File(context.filesDir, "torchat.migrated")
            if (!legacy.renameTo(moved)) Log.w(TAG, "imported legacy data but could not rename it")
        }.onFailure { failure ->
            // Left in place so the next start tries again.
            Log.w(TAG, "legacy import failed", failure)
        }
    }
}
