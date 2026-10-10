# TorChat for Android

The Android client. It speaks the same protocol as the desktop app byte for
byte — a phone and a laptop chat with each other over Tor onion services, with
no server in between. The protocol is specified in
[`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md).

---

## Build and run

You need Android Studio (or the Android SDK) and JDK 17 or newer.

```bash
cd android
./gradlew :core:test          # the protocol, ~160 tests, no emulator needed
./gradlew :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
```

Or open the `android/` folder in Android Studio and press Run.

**Release signing.** `keystore.properties` and the `.jks` it names are
deliberately not in git. Copy both into `android/` to build a signed release;
without them the release variant builds unsigned.

**First launch** takes 30 seconds or more while Tor downloads the network
directory. Later launches take a few seconds.

---

## Layout

```
core/   the protocol — pure Kotlin, no android.* imports, testable on any JVM
        crypto · card · wire (handshake, sealed channel) · engine · models

app/    the Android app
  service/    TorChatService (the node), Session, NetworkObserver, KeepAlive, Power
  receivers/  BootReceiver, KeepAliveReceiver, NotificationActionReceiver
  provider/   TorChatProvider (friends and conversations as a content provider)
  data/       SQLite store, contract, legacy JSON import, backup, prefs, Keystore
  notify/     notification channels and message notifications
  widget/     the home-screen widget
  tor/        kmp-tor wrapper and SOCKS5 dialler
  ui/         activities, fragments, view models, adapters, custom views
```

---

## Where each Android topic lives

Every item below is part of how the app actually works, not a separate demo.
**Settings → Diagnostics** exercises each component kind on its own and prints
what happened, which is the quickest way to show them.

### 1. Layouts with a list view and a grid view, and a simple widget

| What | Where |
| --- | --- |
| `ListView` — the chat list, with a view-holder `BaseAdapter`, long-press context menu, pull-to-refresh | `ui/home/ChatsFragment.kt`, `res/layout/fragment_chats.xml`, `item_conversation.xml` |
| `GridView` — every friend as a node tile; switches to one column from the menu | `ui/home/PeersFragment.kt`, `fragment_peers.xml`, `item_peer_grid.xml`, `item_peer_list.xml` |
| Two more `ListView`s with mixed row types | `ui/requests/RequestsFragment.kt`, `ui/settings/ChannelsFragment.kt` |
| `ConstraintLayout`, `LinearLayout`, `FrameLayout`, `CoordinatorLayout` | throughout `res/layout/` |
| Home-screen widget — node status, peers online, unread count | `widget/NodeWidgetProvider.kt`, `res/layout/widget_node.xml`, `res/xml/node_widget_info.xml` |

### 2. Action bar, menus and swipe views

| What | Where |
| --- | --- |
| Action bar — a `MaterialToolbar` set with `setSupportActionBar`, wired to navigation so titles and Up follow the graph; the subtitle is the node's live status | `MainActivity.kt`, `res/layout/activity_main.xml` |
| Options menus, via `MenuProvider` — search (`SearchView`), my code, grid/list toggle, requests, settings | `ui/home/HomeFragment.kt`, `res/menu/menu_home.xml`, `menu_chat.xml`, `menu_log.xml` |
| Context menu (long-press a chat or a peer) | `menu_conversation_context.xml`, `ChatsFragment`, `PeersFragment` |
| Popup menu (the ⋮ on a notification channel) | `menu_channel_popup.xml`, `ChannelsFragment` |
| Swipe view — `ViewPager2` + `TabLayout`: Chats ⇄ Peers ⇄ My node | `HomeFragment.kt`, `fragment_home.xml` |

### 3. Activities, services, broadcast receivers and content providers, each activated its own way

`ui/ComponentLauncher.kt` holds one method per mechanism; the screens call
those same methods.

| Component | Activated by | Where |
| --- | --- | --- |
| **Activity** — `MainActivity` | launcher, deep links | `MainActivity.kt` |
| **Activity** — `ScanActivity` (QR scanner) | explicit intent, for a result (`ActivityResultContract`) | `ui/scan/ScanActivity.kt` |
| **Activity** — `ImportCardActivity` | implicit `ACTION_SEND` from any app ("Share → TorChat") | `ui/importcard/ImportCardActivity.kt` |
| **Service** — `TorChatService` | `startForegroundService` (runs on its own) and `bindService` (screens reach the engine) | `service/TorChatService.kt` |
| **Receiver** — `BootReceiver` | system broadcast `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` | `receivers/BootReceiver.kt` |
| **Receiver** — `KeepAliveReceiver` | `AlarmManager` alarm, or `sendBroadcast` | `receivers/KeepAliveReceiver.kt` |
| **Receiver** — `NotificationActionReceiver` | notification buttons: reply, mark read, accept, reject | `receivers/NotificationActionReceiver.kt` |
| **Receiver** — `NodeWidgetProvider` | explicit `sendBroadcast` from the service | `widget/NodeWidgetProvider.kt` |
| **Receiver** — battery-saver changes | registered at runtime with `registerReceiver` | `ui/home/NodeFragment.kt` |
| **Provider** — `TorChatProvider` | `ContentResolver.query` / `update` / `delete`; observed with `ContentObserver` | `provider/TorChatProvider.kt`, `data/TorChatContract.kt` |
| **Provider** — `FileProvider` | sharing the QR image as a `content://` grant | `ui/common/QrCode.kt`, `res/xml/file_paths.xml` |

### 4. Creating, modifying and deleting notification channels

`notify/Channels.kt`, with a screen at **Settings → Notification channels**.

- **Create** — four fixed channels on start (node status, messages, requests,
  alerts), and one channel per friend the moment you add them.
- **Modify** — a friend renaming themselves renames their channel; you can
  rename one yourself from its ⋮ menu. Muting replaces the channel at a lower
  importance, because Android never lets an app raise a channel's importance
  after creating it.
- **Delete** — removing a friend deletes their channel; any channel can be
  deleted from its ⋮ menu.

### 5. Fragments and a navigation graph

`res/navigation/nav_graph.xml` — twelve fragment destinations under one activity:
onboarding, home (which itself hosts three tab fragments), chat, friend
profile, add friend, my code, requests, settings, channels, log and
diagnostics. It declares typed arguments (`peerId`, `code`), transition
animations, global actions, and deep links (`torchat://chat/{peerId}`,
`torchat://card`, `torchat://requests`, `torchat://add?code=…`) that
notifications, conversation shortcuts and the widget open.

---

## What makes delivery and notifications reliable

Each of these was a cause of "it works sometimes".

| Problem | Fix |
| --- | --- |
| The node was a `dataSync` foreground service, which Android 15 stops after six hours a day, and which may not start at boot | `specialUse` service type; starts at boot and after updates |
| Leaving a chat open and pressing Home suppressed every later notification from that friend | "visible conversation" is tied to the chat screen's resume/pause |
| A burst of engine events (Tor logs) overflowed a 64-slot buffer and dropped `MessageReceived` | 1024-slot buffer that drops oldest; notifications are rebuilt from unread rows in the database, so a missed event costs nothing |
| Tor dying left the app offline until relaunched | the engine restarts Tor with backoff, keeping the same onion address |
| Switching wifi ⇄ mobile left friends "offline" for up to five minutes | network observer → Tor's `DisableNetwork`, links probed, backoff reset, everyone redialled |
| A friend who redialled after a dropped circuit was refused for up to a minute in favour of our dead half-open link | a silent existing link is replaced by the fresh one |
| A message to a friend in backoff waited for the next sweep | sending dials immediately |
| Conversation JSON files were rewritten on a background thread while the engine mutated them; a collision silently lost the write | SQLite with transactions; existing JSON is imported once |
| No wake-ups in Doze | keep-alive alarm with a short wake lock; optional always-on mode; battery-optimisation prompt |

Tested on an Android 16 emulator against the unchanged desktop engine over the
live Tor network: add a friend via share, chat both ways, delivery receipts,
notification with the chat left open behind Home, reconnect after the desktop
restarted.
