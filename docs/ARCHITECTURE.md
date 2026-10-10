# TorChat — Core Architecture

This document specifies the TorChat core: identity, contact cards, the wire
protocol, the delivery contract, and the connection lifecycle. It is written as a
**protocol specification, not a description of the JavaScript implementation** —
everything here is reproducible in any language on any platform, and two
independent implementations that follow it will interoperate byte for byte.

The current Node/Electron client is one conformant implementation. An Android
client is another. Nothing below assumes either.

UI, packaging, and the CLI command surface are deliberately out of scope.

---

## Contents

1. [The one idea](#1-the-one-idea)
2. [Layer map](#2-layer-map)
3. [Cryptographic primitives](#3-cryptographic-primitives)
4. [Identity](#4-identity)
5. [Contact cards](#5-contact-cards)
6. [The transport abstraction](#6-the-transport-abstraction)
7. [Wire protocol](#7-wire-protocol)
8. [The delivery contract](#8-the-delivery-contract)
9. [The friend book](#9-the-friend-book)
10. [Connection management](#10-connection-management)
11. [Persistence](#11-persistence)
12. [Constants](#12-constants)
13. [Platform interface](#13-platform-interface)
14. [Invariants](#14-invariants)
15. [Build order](#15-build-order)

---

## 1. The one idea

Every peer runs a Tor onion service. That is the entire networking design, and
everything else follows from it.

The problem being solved is inbound reachability. Your device has an address, but
something upstream — a home router, an office firewall, a mobile carrier's CGNAT
— refuses to deliver unsolicited packets to it. On a mobile carrier that refusal
is absolute and cannot be negotiated with. The traversal toolkit that normally
answers this (UPnP, port forwarding, hole punching, STUN/TURN) exists to *win*
that fight, and loses it often.

An onion service does not fight it. Tor makes only **outbound** connections at
both ends — each side dials out to a rendezvous point and the two circuits are
joined there — and outbound is the one thing every network on earth permits.
Nothing ever has to arrive at your address unsolicited, so nothing can be
dropped.

The privacy property is the second half, and it is free. Your peer connects to an
onion address, never to an IP. An onion address *is a public key*, not a
location. The rendezvous point sees two circuits and cannot associate them with
either end. Neither friend ever learns where the other physically is.

The honest trade: this is not host-free. Tor is thousands of volunteer relays and
a handful of hardcoded directory authorities — a real dependency on
infrastructure nobody here owns. What survives is the property that matters: none
of that infrastructure can read a message or work out who is talking to whom,
because frames are already sealed before they enter a circuit and identities are
keys rather than addresses.

**Consequence for implementers:** there is no fallback path. No IP endpoint is
ever published, stored, announced, or dialled. If Tor is unavailable, the client
is offline. A "direct connection for speed" option would undo the whole design,
and the code must contain no branch that could reach one.

---

## 2. Layer map

```
┌─────────────────────────────────────────────────────────┐
│  Application / UI                                       │
│  observes events, calls sendText / addFriend / history  │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│  Engine (roster)                                        │
│  owns friends, links, dial policy, message routing      │
│  ├─ FriendBook   who you know, where they said they are │
│  ├─ Messenger    delivery contract, outbox, acks        │
│  └─ DialPolicy   ordering, racing, backoff              │
└──────┬──────────────────────────────────┬───────────────┘
       │                                  │
┌──────▼────────────────┐   ┌─────────────▼───────────────┐
│  Card                 │   │  Transport (Link)           │
│  build / parse /      │   │  framing, handshake,        │
│  verify contact codes │   │  sealed channel, keepalive  │
└──────┬────────────────┘   └─────────────┬───────────────┘
       │                                  │
       │              ┌───────────────────▼───────────────┐
       │              │  TorProvider  (platform-specific) │
       │              │  dial(onion, port) -> Stream      │
       │              │  publish(localPort) -> address    │
       │              └───────────────────┬───────────────┘
       │                                  │
┌──────▼──────────────────────────────────▼───────────────┐
│  Crypto        Ed25519 · X25519 · HKDF-SHA256 · AES-GCM │
└─────────────────────────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│  Store         identity · friends · conversations       │
└─────────────────────────────────────────────────────────┘
```

Two rules govern this map:

- **Transport does not know about Tor.** It is handed an open bidirectional byte
  stream and speaks the same protocol it would over a LAN cable. The encryption
  below it does not depend on the transport keeping any promises, and does not
  trust it to. A relay carrying these bytes sees exactly what a LAN eavesdropper
  sees.
- **Only `TorProvider` is platform-specific.** Everything above it is portable
  logic. Porting to a new platform means implementing that one interface (§13)
  and re-expressing the rest.

---

## 3. Cryptographic primitives

Four primitives, no more.

| Purpose | Algorithm | Notes |
|---|---|---|
| Identity / signing | **Ed25519** | The signing key *is* the identity |
| Key agreement | **X25519** | Ed25519 cannot do ECDH, hence a second pair |
| Key derivation | **HKDF-SHA256** | 32-byte output |
| Channel encryption | **AES-256-GCM** | 12-byte IV, 16-byte tag |

### 3.1 Key encoding — get this exactly right

Every public and private key that crosses a boundary (a card, a backup file, an
on-disk record) is encoded as:

- **Public keys:** DER, **SubjectPublicKeyInfo (SPKI)** structure → **base64url**, no padding.
- **Private keys:** DER, **PKCS#8** structure → **base64url**, no padding.

This is not the raw 32-byte key. An Ed25519 SPKI DER blob is 44 bytes; an X25519
SPKI DER blob is 44 bytes; the PKCS#8 private forms are 48 bytes. Libraries that
default to raw 32-byte encodings (including BouncyCastle's low-level API) must be
wrapped in the SPKI/PKCS#8 structures explicitly.

Getting this wrong produces a client that works perfectly against itself and
fails every signature check against any other client. **This is the single most
likely interop bug in a port.**

`base64url` means the RFC 4648 §5 alphabet (`-` and `_` instead of `+` and `/`)
with padding `=` stripped.

### 3.2 Sealing

`seal(key, value)` produces:

```
┌────────────┬─────────────┬──────────────────────┐
│  IV        │  GCM tag    │  ciphertext          │
│  12 bytes  │  16 bytes   │  variable            │
└────────────┴─────────────┴──────────────────────┘
```

- IV is 12 cryptographically random bytes, fresh for every frame.
- Plaintext is `JSON.stringify(value)` encoded as UTF-8.
- No additional authenticated data.
- **The tag precedes the ciphertext.** Most AEAD APIs (including Java's JCE)
  append the tag to the ciphertext instead. You must reorder on both seal and
  open.

`open(key, buf)` reverses it and throws on tag mismatch. A frame that fails to
open is a protocol failure and closes the link — it is never skipped.

### 3.3 Random values

| Value | Derivation |
|---|---|
| Handshake nonce | base64url of 24 random bytes |
| Message ID | lowercase hex of 8 random bytes (16 chars) |
| GCM IV | 12 random bytes |

All from a CSPRNG.

---

## 4. Identity

An identity is two keypairs generated locally, and nothing else. There is no
signup, no account server, and no directory to be looked up in.

```
identity {
  id         string   derived, see below
  name       string   display label, ≤ 24 chars
  sigil      string   1–2 char glyph
  sign       { public, private }   Ed25519, encoded per §3.1
  box        { public, private }   X25519,  encoded per §3.1
  createdAt  number   epoch ms
}
```

### 4.1 Deriving the ID

The TorChat ID is a hash of the Ed25519 public key, which is what replaces a
server-side account registry: nobody can claim your ID without your private key.

```
1. bytes  = base64url_decode(sign.public)      // the SPKI DER blob, not raw key
2. digest = SHA-256(bytes)
3. take the first 8 bytes of digest as a 64-bit big-endian bit string
4. take the first 60 bits, split into 12 groups of 5 bits
5. map each group through Crockford base32:  "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
6. format as  TOR-XXXX-XXXX-XXXX
```

Crockford's alphabet omits `I`, `L`, `O`, and `U`, so an ID survives being read
aloud or copied by hand. Example shape: `TOR-4K7P-9XQ2-M3TV`.

**Verification rule:** anywhere a peer presents an ID alongside a signing key,
re-derive the ID from the key and reject a mismatch. This is `idMatchesKey`, and
it is what makes ID spoofing impossible rather than merely difficult.

### 4.2 Profile clamping

`name` and `sigil` arrive from other people's machines and are labels, not
proofs. Clamp them at every entry point — identity creation, profile edits, card
parsing, and a peer renaming itself mid-session:

- `name`: trim, truncate to 24 chars, fall back to `"anon"` if empty.
- `sigil`: truncate to 2 chars, fall back to `"*"` if empty.

Put this in one function. The original implementation had it inlined in four
places with slightly divergent rules, and one of them forgot to trim.

### 4.3 Backup

A backup is the only way an identity moves between devices:

```json
{
  "torchat": 1,
  "identity": { ...the full identity record, private keys included... },
  "onionKey": "ED25519-V3:..."
}
```

The onion key belongs in here as much as the signing key does. Every friend's
saved card points at the onion address, and that address is derived from the
onion key alone — a backup that restored your name and lost your address would be
the worse half, leaving you unreachable by everyone who already knows you.

On import, before adopting the record:

1. Check `idMatchesKey(record.id, record.sign.public)`.
2. Sign the probe string `torchat-backup-check` with the private key and verify
   it against the public half. This proves the pair actually matches, rather than
   discovering it at the first handshake.
3. Confirm the X25519 private key parses.

A backup without `onionKey` is valid — it predates the field — and yields a fresh
address.

**Anyone holding this file can be you.** It never leaves the device unless the
user explicitly moves it.

---

## 5. Contact cards

A card is the text you send a friend out of band — over any messenger, email, a
QR code, a sticky note. It is the **only** discovery mechanism in the system.
There is no directory, no lookup service, nothing that can be taken offline.

### 5.1 Format

```
TORCHAT1.<base64url(JSON)>
```

Where the JSON is:

```json
{
  "v": 1,
  "id": "TOR-4K7P-9XQ2-M3TV",
  "sign": "<base64url SPKI DER Ed25519 public>",
  "box":  "<base64url SPKI DER X25519 public>",
  "name": "alice",
  "sigil": "*",
  "endpoints": [{ "type": "onion", "host": "….onion", "port": 47777 }],
  "ts": 1753900000000,
  "sig": "<base64url Ed25519 signature>"
}
```

Handing a card to the wrong person costs you an unwanted connection attempt, not
your location. It reveals no IP, no ISP, no country.

### 5.2 Canonical JSON

Signing requires both ends to produce byte-identical input, so cards are
serialised with a deterministic encoder rather than the language's default:

```
canonical(value):
  if value is an array:
      "[" + join(map(canonical, value), ",") + "]"
  if value is an object:
      "{" + join([ json_string(k) + ":" + canonical(value[k])
                   for k in sorted(keys(value)) ], ",") + "}"
  otherwise:
      json_encode(value), with undefined/absent encoded as null
```

Details that matter:

- Keys are sorted by **code-unit order** (plain byte comparison for the ASCII
  keys used here).
- No whitespace anywhere.
- String escaping follows standard JSON rules.
- **Canonicalise whatever fields actually arrived**, not a fixed schema. On
  parse, the payload is "the received object minus `sig`". A card carrying an
  unknown extra field must still verify, because the sender signed it too.

### 5.3 Creating

```
payload = { v, id, sign, box, name, sigil, endpoints, ts }
sig     = Ed25519_sign(signPrivate, UTF8(canonical(payload)))
card    = "TORCHAT1." + base64url(UTF8(json(payload + { sig })))
```

`ts` is `now` in epoch milliseconds. `endpoints` entries are normalised to
`{ type, host, port }` with `port` coerced to a number — the declared type is
preserved rather than forced, because the payload is signed and must serialise
identically on both sides. Filtering happens when a card is *read*, not written.

### 5.4 Parsing and verification

This runs on text a user pasted from who-knows-where, so every step is checked
and every failure produces a message a human can act on.

1. Strip all whitespace. Require the `TORCHAT1.` prefix.
2. base64url-decode, parse JSON. Failure ⇒ *"code is damaged — it was probably
   truncated when copied"*.
3. Require `v == 1`.
4. Require `id`, `sign`, `box` present.
5. **`idMatchesKey(id, sign)`** ⇒ else *"code is forged — the ID does not match
   its key"*.
6. Split off `sig`; verify `Ed25519_verify(sign, UTF8(canonical(rest)), sig)`.
7. Reject `ts > now + 86_400_000`. A card dated in the future would outrank every
   real card that person ever makes again (§9.2). One day of slack covers an
   unset clock; beyond that is wrong.
8. Clamp `name` and `sigil` (§4.2).

Signature verification returns `false` on malformed input rather than throwing —
it runs on untrusted bytes.

---

## 6. The transport abstraction

The core needs exactly two things from Tor. Define them as an interface and the
rest of the system is platform-free.

```
interface TorProvider {
    // Bring Tor up. Resolves when bootstrapping reaches 100%.
    start(): Future<Unit>

    // Publish an onion service forwarding virtualPort -> 127.0.0.1:localPort.
    // Returns the address, the private key (first creation only), and whether
    // the descriptor is confirmed uploaded.
    publish(localPort: Int, virtualPort: Int, existingKey: String?)
        : Future<{ address: String, key: String, published: Boolean }>

    // Open a stream to a peer's onion address, through Tor.
    dial(host: String, port: Int, timeoutMs: Long): Future<Stream>

    stop()

    val isReady: Boolean
    val events: Flow<TorEvent>      // log lines, bootstrap %, "tor went down"
}
```

### 6.1 Publishing

Publishing uses Tor's control port:

```
ADD_ONION NEW:ED25519-V3 Port=47777,127.0.0.1:<localPort>
```

or, on subsequent runs, `ADD_ONION <savedKey> Port=…` to keep the same address.
The reply carries `ServiceID=<56 chars>` and, on first creation only,
`PrivateKey=<spec>`. The address is `ServiceID + ".onion"`.

**Persist the private key immediately.** The address is derived from it; lose it
and every friend's saved card points at a service that no longer exists.

Two behaviours are not optional:

- **Do not use the `Detach` flag.** A service created without it dies with the
  control connection that made it. Quitting the app should take the onion down,
  not leave it advertised and unanswered — which looks to a friend exactly like
  being ignored.
- **Wait for the descriptor.** `ADD_ONION` returns as soon as the service exists
  *locally*, but nobody can reach it until the descriptor is uploaded to the
  hidden-service directories, which takes tens of seconds. Subscribe to
  `HS_DESC`, wait for an `UPLOADED` event naming your service ID, then report
  reachable. Announcing before then hands friends an address that fails with
  "TTL expired", indistinguishable from being offline.

  Timing out the descriptor wait resolves *false*, not an error — the service
  usually becomes reachable shortly after anyway, and refusing to start over a
  slow upload is worse than starting with an honest caveat.

### 6.2 Dialling

Dial through Tor's SOCKS5 port with **the onion address sent as a domain name**,
never as a resolved IP. This is not convenience: it means Tor resolves the
`.onion` inside the network, and the local device never performs a lookup that
could leak who it is trying to reach. Resolving locally first defeats the entire
point.

Map SOCKS5 reply codes to human causes; code `4` (host unreachable) is the
ordinary case of a friend having the app closed.

**One subtle requirement.** Tor routinely delivers the peer's first protocol
frame in the same TCP segment as the SOCKS reply. The SOCKS negotiator must read
*exactly* the bytes of the reply and leave the remainder in the stream buffer for
the protocol layer. An implementation that switches the socket to a
read-everything mode during negotiation will swallow the peer's `hello`, and the
connection then waits forever for a frame that already arrived — presenting
exactly as an unreachable peer.

### 6.3 The local listener

Bind a TCP listener on **`127.0.0.1` with an ephemeral port**, and point the
onion service at it.

Loopback is deliberate. The only thing that should ever connect there is your own
Tor, handing over a rendezvous stream. Binding a real interface would accept
direct connections and give away the address the onion exists to keep private.

Nothing is recorded about where an inbound peer came from. Over Tor the remote
address is always `127.0.0.1` — there is nothing true to learn, and asking would
be pointless as well as unwanted.

---

## 7. Wire protocol

Spoken over any bidirectional byte stream, once one is open.

### 7.1 Framing

```
┌──────────────────────┬─────────┬────────────────────┐
│  length  (uint32 BE) │  tag    │  body              │
│  4 bytes             │ 1 byte  │  length - 1 bytes  │
└──────────────────────┴─────────┴────────────────────┘
```

`length` counts the tag plus the body. Reject `length < 1` or `length > 1048576`
as a malformed frame and close the link.

| Tag | Meaning |
|---|---|
| `0` | Plaintext handshake JSON |
| `1` | AES-GCM sealed payload (§3.2) |

Any other tag closes the link.

Tagging every frame, rather than tracking a plaintext/encrypted mode, avoids a
race where one side starts encrypting before the other has finished its
handshake.

### 7.2 Handshake

Both sides send the same two frames. The handshake is **symmetric in content**;
only the ordering is asymmetric, and only because Tor forces it.

```
        dialler                                responder
           │                                       │
           │ ──── hello { card, nonce_A } ───────► │   (responder stays silent
           │                                       │    until this arrives)
           │ ◄─── hello { card, nonce_B } ──────── │
           │ ◄─── proof { sig over nonce_A } ───── │
           │                                       │
           │ ──── proof { sig over nonce_B } ────► │
           │                                       │
        ┌──▼───────────────────────────────────────▼──┐
        │   both proved → channel ready, tag 1 only   │
        └─────────────────────────────────────────────┘
```

**Frames:**

```json
{ "t": "hello", "card": "TORCHAT1.…", "nonce": "<base64url, 24 bytes>" }
{ "t": "proof", "sig": "<base64url Ed25519 signature>" }
```

**The responder must not speak first.** This is a hard property of the transport,
not a style choice: Tor discards anything an onion service writes into a
rendezvous stream before the `CONNECTED` cell reaches the other end — silently,
with no error on the writing side. A measured 120-byte marker written on accept
never arrived, while the same marker written four seconds later arrived in 380 ms.

So: the side that *accepted* the connection waits. On receiving `hello` it sends
its own `hello` first, then its `proof` — the peer cannot check a proof from
someone who has not yet identified themselves.

### 7.3 What a proof signs

```
torchat-proof-v1|<nonce>|<signerId>|<recipientId>
```

UTF-8, signed with Ed25519, verified against the `sign` key from the peer's card.

Signing the bare nonce would make the signature a free-floating assertion that
said nothing about who produced it or who it was for — valid in any context that
happened to present the same bytes. Naming the protocol, the signer and the
intended recipient makes it answer exactly one question: *did this identity prove
itself to that identity, in this handshake?*

Each side verifies the proof against **the nonce it sent**, the peer's ID as the
signer, and its own ID as the recipient.

This proves *possession*, not knowledge. Copying somebody's public contact card
gets you nowhere — you would need their private key.

### 7.4 Handshake validation

On receiving `hello`:

1. Reject a second `hello` on the same link.
2. Require `nonce` to be a string of **length ≥ 16**. A nonce that was never
   validated got signed as the literal string `"undefined"` — the same challenge
   for every peer that omits it, which is the one input to the proof that must
   not be attacker-chosen or constant.
3. Parse and fully verify the card (§5.4).
4. If this link was opened expecting a specific peer, reject a different ID:
   *"expected X but reached Y"*.
5. Reject your own ID: *"that is your own address"*.
6. Derive the channel key (§7.5).

On receiving `proof`:

1. Reject if no `hello` has been seen.
2. Verify against the expected payload. Failure ⇒ *"peer could not prove it owns
   that ID"*.

The link becomes ready only when **both** `theyProved` and `weProved` are true.

A sealed frame arriving before the channel key exists closes the link.

### 7.5 Channel key derivation

```
shared = X25519(myBoxPrivate, theirBoxPublic)
mine   = base64url(SPKI_DER(public_of(myBoxPrivate)))
salt   = UTF8( sorted([mine, theirBoxPublic]).join("|") )
key    = HKDF-SHA256(ikm = shared, salt = salt, info = UTF8("torchat-channel-v1"), L = 32)
```

Both sides compute an identical secret without it ever crossing the wire. The
salt is the two public keys sorted, so both ends agree regardless of who dialled
whom.

`sorted` is plain lexicographic byte comparison over the base64url strings.
`theirBoxPublic` is used **exactly as it appeared in their card** — same
encoding, no re-normalisation — or the two sides derive different salts.

### 7.6 Application frames

Once ready, all traffic is tag `1`. Payloads are JSON objects discriminated by
`t`:

| `t` | Direction | Payload | Meaning |
|---|---|---|---|
| `msg` | either | `{ id, ts, body }` | A chat message |
| `ack` | either | `{ id }` | Confirmed receipt of `id` |
| `ping` | either | `{}` | Keepalive probe |
| `pong` | either | `{}` | Keepalive reply |
| `profile` | either | `{ name, sigil }` | Sender renamed itself |
| `endpoint` | either | `{ endpoints: [...] }` | Sender's current addresses |

Unknown `t` values are ignored, not errors — that is the forward-compatibility
hinge.

`ping` and `pong` are handled inside the transport layer and never surface to the
application.

### 7.7 Keepalive

- Set TCP keepalive on the socket (15 s).
- Every **20 s** once ready, send `ping`.
- If nothing at all has been received for **60 s**, fail the link as *"peer went
  silent"*.
- Answer every `ping` with `pong`. Ignore received `pong` beyond refreshing the
  last-seen timestamp.

Any received byte refreshes last-seen, not only pongs.

### 7.8 Timeouts

The handshake budget must be **passed down from the caller**, not fixed inside
the link. A hardcoded 45 s always fired before a 90 s dial budget, so a handshake
that was merely slow across six relays got reported as failed.

Every round trip here crosses six relays. Exchanges that cost microseconds on a
LAN cost seconds on a circuit; a timeout tight enough for TCP abandons
connections that were about to succeed.

---

## 8. The delivery contract

The most important behaviour in the system, and the one that costs a bug to get
right.

### 8.1 States

```
  queued  ──► sent ──────► delivered
              (bytes         (peer
             accepted)      said so)

  received                     ← anything that came from them
```

| State | Means |
|---|---|
| `queued` | Composed locally, nothing on the wire yet |
| `sent` | The socket accepted the bytes — **which is not delivery** |
| `delivered` | The peer sent an `ack`. Only then. |
| `received` | It came from them |

**Nothing but an `ack` may set `delivered`.** A message written into a circuit
that dies a moment later is still `sent`, and `sent` is still owed a retry. That
distinction is the whole reason messages do not vanish silently.

### 8.2 Message record

```
message {
  id     string   16 hex chars, chosen by the composer
  ts     number   epoch ms
  body   string   ≤ 4000 chars
  mine   bool     did we compose it
  state  string   see above
}
```

The wire form carries only `{ t: "msg", id, ts, body }` — never local
bookkeeping.

Clamp `body` to 4000 chars and incoming `id` to 64 chars on receipt.

### 8.3 Sending

1. Compose, append to the conversation with state `queued`.
2. If a live link exists, seal and write the `msg` frame; on success set `sent`.
3. Leave it in the outbox regardless. Only an `ack` removes it.

### 8.4 Receiving

1. Derive the message ID from the frame, clamped.
2. **If we already hold a message with that ID from this peer**, re-send the
   `ack` and stop. Do not file it, do not display it, do not emit an event.
3. Otherwise append with state `received`, `ack` it, and emit.

The sender retries anything it has no ack for, so the same message legitimately
arrives twice whenever an *ack* was the thing that got lost. Re-acknowledging is
what they are waiting for.

### 8.5 Flushing the outbox

On **every** newly established link, re-send everything undelivered — that is,
every message where `mine == true` and `state != delivered`. This deliberately
includes `sent`.

Retrying is safe precisely because of §8.4: the receiver recognises a duplicate
and re-acks it. The only cost of a needless retry is one extra ack.

### 8.6 The `mine` scoping rule

Message IDs are chosen by whoever composed the message, so the two directions
share an ID space that neither side controls both halves of.

**Every lookup must be scoped by `mine`.** Without it, a peer picking an ID that
collides with one of ours could mark our message delivered merely by sending a
message, or have their message mistaken for a duplicate of ours.

- Applying an `ack` searches only `mine == true`.
- The duplicate check on receipt searches only `mine == false`.

Nothing may ever match across that boundary.

---

## 9. The friend book

The record of who exists and where they said they were. Nothing here dials,
listens, or touches the network.

```
friend {
  id         string   hash of their signing key — the only field that is proof
  sign, box  string   their public keys, from a signed card
  name       string   label they chose, clamped
  sigil      string   label they chose, clamped
  endpoints  [...]    onion addresses only, newest first, max 4
  cardTs     number   when the card we believe was made
  addedAt    number   when we first met them
}
```

### 9.1 The endpoint choke point

```
endpoint { type: "onion", host: "<56 chars>.onion", port: 47777 }
```

An onion address is validated by `/^[a-z2-7]{56}\.onion$/i`.

**Every path that could introduce an address goes through one merge function** —
parsing a card, a live peer announcing itself, loading the friend file from disk.
That function:

- drops anything that is not a valid onion address (it **drops**, it does not
  deprioritise — there is no ordering in which a rejected address gets used),
- puts incoming addresses ahead of existing ones,
- deduplicates on `host:port`,
- caps the list at **4**.

A separate check at dial time re-applies the same rule and additionally refuses
your own onion. An address that somehow got stored still cannot be dialled.

This single function is the privacy guarantee. A peer that sent an IP — whether
an old build or a hostile one — cannot get you to dial it.

### 9.2 Card staleness

Cards are signed but not sequenced, so pasting an old card after a newer one
would otherwise reinstate a dead onion ahead of the live one.

```
isStale(friend, peer) = not_new AND friend.cardTs AND peer.ts AND peer.ts < friend.cardTs
```

- **Fresh card** → apply everything: name, sigil, keys, `cardTs`, merged endpoints.
- **Stale card** → keep its endpoints as *fallbacks only*, merged behind what we
  already hold. Do not touch the profile, do not reorder the live address behind
  itself.

Identity stays trusted — it is proven by the key. What a card *claims about now*
is not, if we already hold something more recent.

### 9.3 Adding and accepting

Adding a friend from a pasted code: parse and verify, reject your own ID, reject
a card with no onion endpoint (*"they need to send a new one"*), upsert, and
immediately attempt a connection.

**A peer that dials in and proves its identity is accepted automatically.** The
only way it could know your onion address is that you handed it your contact
card. One person pasting a code forms the friendship — there is no accept/reject
step, because the card handover already was one.

### 9.4 Resolution

Look someone up by exact ID, by a unique ID fragment (hyphens ignored, case
insensitive), or by exact display name. Return a match only when **exactly one**
candidate matches; ambiguity resolves to nothing.

---

## 10. Connection management

### 10.1 Symmetry

Either side may dial the other, and whoever gets there first wins. When both
dial at once and two links reach `ready`, **keep the one already adopted and
close the newcomer.**

### 10.2 Adoption

When a link reports ready:

1. **Check it has not already closed.** A link can die between "ready" and
   adoption. Storing a dead one is permanent damage: its close event has already
   fired and its listeners are gone, so the friend stays in the live-links map
   forever, the redial sweep skips them for having a "live" link, and only a
   restart brings them back.
2. If a link for that peer already exists, close this one.
3. Upsert the peer into the friend book from their card.
4. Register it, reset the retry policy, emit `online`.
5. Send `{ t: "endpoint", endpoints: [ours] }`.
6. Flush the outbox (§8.5).

On close, only remove the map entry if the closing link is *still the registered
one* — otherwise a superseded duplicate's close event evicts the live link.

### 10.3 Dialling

- Filter the friend's endpoints to dialable onions (§9.1).
- Start each endpoint's dial staggered by **2 s**, race them, take the first link
  that comes up, and close any straggler that wins a race already decided.
- Friends almost always have exactly one address; this matters only just after
  someone's has changed and both old and new are on record.
- Guard against dialling a peer already connected or already being dialled.

### 10.4 Backoff

```
delay = min(300_000, 15_000 × 2^(attempts - 1))
```

15 s, 30 s, 60 s, 120 s, 240 s, then capped at 5 minutes. Reset on any successful
connection.

A background sweep every **30 s** dials every friend that has no link, is not
already being dialled, is past its next-try time, and has at least one endpoint.

### 10.5 Failure reporting

Translate dial failures into causes a person can act on:

| Condition | Message |
|---|---|
| Tor not running | *tor is not running here, so nothing can be dialled* |
| Timeout | *no answer. they are probably offline, or their tor has not published yet* |
| Handshake failed | *reached them, but the handshake failed: …* |
| SOCKS "host unreachable" | *their torchat is not running* |

Suppress repeats: report only when the failure reason **changes**, unless the
user explicitly asked (a manual "try now" always answers). Otherwise a
permanently offline friend spams the log forever.

### 10.6 Tor going down

Tor dying takes every link with it. On a `down` event, tear all links down
explicitly so each friend is reported offline **once** — rather than looking
online while every send silently fails.

Treat this as fatal to connectivity, not something to route around. There is
nothing to fall back to, and falling back would mean connecting over a plain IP.

---

## 11. Persistence

Everything the client knows lives on the device and nowhere else. There is no
remote copy — that is the point.

| Store | Contents | Notes |
|---|---|---|
| identity | The record from §4 | **Treat as a password.** Private keys in the clear. |
| settings | `onionKey` | Lose it and your address changes forever |
| friends | The list from §9 | |
| conversations | Per-peer message logs | Capped at 2000 messages each |

Rules:

- **Write atomically.** Temp file plus rename, or an equivalent transaction. A
  crash mid-write must not corrupt the identity.
- **Debounce conversation writes** (~300 ms). Delivery receipts mutate messages
  that were already saved, so an append-only log would make this awkward; hold
  the log in memory and write back on a short debounce.
- **Flush on shutdown.**
- **Clear the in-memory cache when the identity changes.** Restoring a backup
  over a running client otherwise leaves the previous identity's history in
  memory, to be written back to disk under the new one.

On Android, put the identity and settings behind the Keystore — an
`EncryptedSharedPreferences`-equivalent, or a Keystore-held AES key wrapping the
record. Conversations can live in a normal database; the identity cannot.

---

## 12. Constants

| Name | Value | Purpose |
|---|---|---|
| `CARD_PREFIX` | `TORCHAT1.` | Contact code marker |
| `ONION_PORT` | `47777` | Virtual port on the onion; same for everyone |
| `PROOF_CONTEXT` | `torchat-proof-v1` | Handshake proof domain separator |
| `HKDF_INFO` | `torchat-channel-v1` | Channel key domain separator |
| `MAX_FRAME` | `1048576` | 1 MiB frame ceiling |
| `MAX_BODY` | `4000` | Message body chars |
| `MAX_ID` | `64` | Message ID chars |
| `MAX_NAME` | `24` | Display name chars |
| `MAX_SIGIL` | `2` | Sigil chars |
| `MAX_ENDPOINTS` | `4` | Addresses remembered per friend |
| `HISTORY_CAP` | `2000` | Messages kept per conversation |
| `BOOTSTRAP_TIMEOUT_MS` | `300000` | Tor first bootstrap (slow on mobile) |
| `DESCRIPTOR_TIMEOUT_MS` | `120000` | Onion descriptor upload wait |
| `DIAL_TIMEOUT_MS` | `90000` | One dial, circuit build included |
| `HANDSHAKE_TIMEOUT_MS` | `45000` | Default, overridden by caller |
| `PING_INTERVAL_MS` | `20000` | Keepalive send |
| `PING_TIMEOUT_MS` | `60000` | Silence before failing a link |
| `REDIAL_INTERVAL_MS` | `30000` | Background sweep |
| `FIRST_BACKOFF_MS` | `15000` | First retry delay |
| `MAX_BACKOFF_MS` | `300000` | Retry ceiling |
| `DIAL_STAGGER_MS` | `2000` | Between racing endpoints |
| `SAVE_DEBOUNCE_MS` | `300` | Conversation write debounce |

The Tor-related timeouts are generous on purpose. A first bootstrap downloads the
full network consensus — megabytes — and was measured at over 120 s on a phone
hotspot, which is exactly the network this design exists for.

---

## 13. Platform interface

Everything in §3–§10 is portable. These are the only pieces that are not.

| Concern | Contract |
|---|---|
| **Tor runtime** | `TorProvider` (§6). Must expose SOCKS dialling and control-port onion publishing. |
| **Byte stream** | Bidirectional, backpressure-aware, with connect/data/error/close signals. |
| **TCP listener** | Bind loopback, ephemeral port, one callback per accepted stream. |
| **Crypto** | The four primitives of §3, with SPKI/PKCS#8 DER encoding. |
| **Storage** | Atomic key-value + per-peer append/patch log. Identity encrypted at rest. |
| **Timers** | Schedule/cancel; must survive the app being backgrounded. |
| **CSPRNG** | For nonces, IVs, message IDs, key generation. |

Notes for an Android implementation specifically:

- **Ed25519 and X25519 only reached the platform JCA at API 33; HKDF is not there
  at any level.** Use BouncyCastle (or Tink) for all three so behaviour is
  identical across API levels, and wrap keys in SPKI/PKCS#8 explicitly.
- **AES-GCM tag placement differs.** Java appends the tag to the ciphertext; §3.2
  requires it before. Reorder in both directions.
- **The Tor binary must ship in the APK's JNI lib directory.** Android 10+ blocks
  executing anything from the app data directory, so the desktop client's
  "download tor at install time" approach does not port. A library such as
  kmp-tor packages tor as `libtor.so` and handles this.
- **Everything must run in a foreground service.** On desktop the process simply
  stays alive; on Android, the moment the UI leaves the foreground the system
  will kill Tor and the onion service disappears, silently ending message
  delivery. Expect OEM battery managers to fight this regardless — the honest
  guarantee is "delivery while the service is alive", and there is no push
  fallback available to a design with no server.

---

## 14. Invariants

Statements that must hold in any conformant implementation. Most are worth a
test.

**Privacy**

1. No IP address is ever published, stored, announced, or dialled.
2. The only endpoint type that survives storage is `onion`.
3. The local listener binds loopback only.
4. Onion addresses are resolved by Tor, never locally.
5. Nothing is recorded about where an inbound peer came from.

**Identity**

6. An ID is always re-derived from the presented signing key and rejected on
   mismatch.
7. A card whose signature does not verify is rejected entirely — no partial
   trust.
8. A card dated more than 24 h in the future is rejected.
9. A peer's name and sigil are clamped at every entry point and never trusted.

**Protocol**

10. A link is ready only when both sides have proved possession.
11. A proof binds the protocol, the nonce, the signer, and the recipient.
12. A nonce shorter than 16 characters is rejected.
13. The side that accepted the connection never writes before it has read.
14. A sealed frame before the channel key exists closes the link.
15. Both ends derive the same channel key regardless of who dialled.

**Delivery**

16. Only an `ack` may set `delivered`.
17. `sent` messages are retried on every new link.
18. A duplicate inbound message is re-acked but never filed or displayed twice.
19. Message lookups never match across the `mine` boundary.

**Lifecycle**

20. A closed link is never adopted.
21. A link's close only evicts it if it is still the registered link.
22. Tor going down closes every link exactly once.

---

## 15. Build order

Each stage is independently testable, and every stage before the last needs no
network at all.

1. **Crypto** — keygen, SPKI/PKCS#8 encoding, sign/verify, ECDH, HKDF, seal/open,
   ID derivation. *Test against fixtures exported from the reference client. Do
   not proceed until a card generated by the desktop app verifies here, and vice
   versa.* Every later interop bug traces back to this stage.
2. **Models** — profile clamping, endpoint merge, friend staleness, message
   states. Pure functions, exhaustively testable.
3. **Card** — canonical JSON, create, parse, and every rejection path.
4. **Transport** — framing, handshake, sealed channel, keepalive. *Test over a
   plain loopback socket pair.* This layer must never know Tor exists.
5. **Store** — atomic writes, debounce, cache reset, the `mine`-scoped lookups.
6. **Engine** — friend book, messenger, dial policy, backoff. *Inject a fake
   dialler; test the whole engine with no network.*
7. **TorProvider** — the real thing. Last, because by this point everything above
   it is already proven.

Stage 1 deserves a fixture file: a handful of known-good cards, a known keypair,
a known channel key from a known pair of keys, and a sealed frame with its
expected plaintext. Generate them from the reference implementation once, commit
them, and assert against them forever.
