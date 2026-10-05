# Handsfree for Mistral

Talk to Mistral's models without touching the phone. This is the hands-free voice
mode you know from the big chat apps, as an Android app for Mistral: start it once,
then **speak, pause, listen, speak** — a pause sends your message by itself, the
answer is read out as it is written, and the microphone opens again on its own.
Saying "stop" ends the conversation.

```
listen  ─►  understand  ─►  think  ─►  read out  ─┐
  ▲                                               │
  └───────────────────────────────────────────────┘
```

> **Unofficial.** This app is not affiliated with, endorsed by or sponsored by
> Mistral AI. It uses Mistral's public API with your own API key.

It grew out of [the local live chat][basis] — the voice loop, the silence handling
and the speech output come from there. Instead of a model on the phone, this one
talks to Mistral's API. Open source, so you can see exactly what it does with your
voice and your key.

## Download

**[Download the latest APK][release-apk]**

[![Android](https://github.com/s-vlaude-netizen/mistral-handsfree/actions/workflows/android.yml/badge.svg)](https://github.com/s-vlaude-netizen/mistral-handsfree/actions/workflows/android.yml)

The [`dev-latest`][release] release is replaced by every green build and always
holds the current state. You need to allow "install from unknown sources" once.
Updates install over the previous version and keep your settings and your key.

> **Status: early version.** Over 200 automated tests cover the API client
> (against a real local HTTP server), streaming, error handling, silence detection
> (against synthetic audio), text cleanup and the whole listen → think → speak loop
> (with scripted speech engines). The screens are driven end to end on a simulated
> phone — sign-in, a wrong or revoked key, chatting, the settings, signing out; in
> English, German and dark mode — against a stub Mistral server.
>
> What no test here could touch is the physical hardware: the real microphone and
> loudspeaker, the Keystore, and the browser tab for the console. That code was
> written and compiled without a device in the loop. If something does not work on
> your phone, that is the place to look first — please open an issue with your
> Android version and device.

## Signing in

Mistral's API is opened with an **API key**. It has no "Sign in with Google" for
other apps (its API description knows exactly one authentication method: a bearer
key), so the app cannot offer one honestly. What it does instead:

1. **Open Mistral console** — a browser tab opens `console.mistral.ai`. You log in
   there the way you always do: **Google, Microsoft, Apple or e-mail**. The app
   never sees your password.
2. Create an API key in the console and copy it.
3. **Paste** — the app checks the key against Mistral right away and only keeps it
   if it works.

The key is stored **encrypted with a key that never leaves the Android Keystore**,
is excluded from backups, and is only ever sent to `api.mistral.ai`. *Replace key*
and *Sign out* are in the settings.

No Mistral account yet? Mistral has a free API plan for experimenting (it asks for a
phone number and has rate limits). Read their current terms before you rely on it —
including what they say about how your data may be used on each plan. Note that the
API is billed separately from a Le Chat subscription.

## How "no button" works — silence detection

The point of the app is that nothing has to be pressed to send. Two engines can
decide that you have finished talking; pick one under *Settings → Listening*.

| | Phone's speech recognition (default) | Mistral Voxtral |
|---|---|---|
| Who decides you are done | The Android recognizer | This app ([`Endpointer`](app/src/main/java/de/localvoice/mistralhandsfree/speech/Endpointer.kt)) |
| The pause is | A *hint* — Android lets the app suggest it, the recognizer may ignore it | Exactly the length you set (0.6–3 s) |
| Speech → text | The phone's recognizer (usually Google's) | Mistral's `voxtral-mini-latest` |
| Cost | Free | Billed by Mistral per minute of audio |
| Start-up beep between turns | Some devices | No |
| Language | Fixed in the settings | Detected automatically |
| Live partial text | Yes | No |

The **Voxtral** engine reads the microphone itself and watches the level. Speech
must stand out from the background by a margin, and the background level is
learned continuously, so a humming fridge raises the bar and a quiet room lowers
it. A turn ends only after the configured pause, so taking a breath or thinking in
the middle of a sentence does not cut you off; a click or a cough that never adds
up to a word is dropped; and a TV that is switched on mid-listen is recognised as
"not speech" instead of keeping the microphone open forever. It is an energy
detector, not a neural VAD — a deliberate choice, so there is no model file to
ship and its behaviour can be tested. The recording is trimmed to the speech
(plus a short lead-in) before upload.

## Voices

*Settings → Voice*:

- **The phone's voice** (default) — free, instant, works offline.
- **Mistral Voxtral voice** — natural and expressive, billed per character. Voices
  are fetched from your account; *Automatic* picks one that speaks your language.
  Sentences are synthesised ahead of the playback and streamed into one continuous
  audio track, so there is no gap between them. If Mistral cannot speak a sentence
  (rate limit, moderation block, no connection) that sentence is read by the
  phone's voice instead of leaving silence.

## Models

*Settings → Model* is one field: type any model id (a fine-tuned one, say), or tap
the arrow to pick from the chat models your account can use. That list is fetched
live from `GET /v1/models`, so new models appear without an app update. The default
is `mistral-small-latest` — a fast model suits a conversation best. Reasoning models
are supported: their "thinking" is neither shown as the reply nor read aloud.

Mistral's API keeps no conversation state, so every request carries the history.
To keep a long hands-free session fast and affordable, the oldest turns are dropped
once a budget is used up ([`ConversationWindow`](app/src/main/java/de/localvoice/mistralhandsfree/domain/ConversationWindow.kt));
the screen keeps the full transcript.

## Using it

- **Start live mode** — the big button. After that the screen can be off: a
  notification shows the state and keeps the session alive, and a wake lock stops
  the phone from dozing off mid-conversation.
- **Say "stop"** (or "goodbye", "Tschüss", "au revoir", …) to end it hands-free.
- **Stop the answer** — the stop button on the right interrupts the current reply;
  the app listens again right away.
- **Type instead** — the keyboard icon works with live mode on or off.
- After about a minute and a half of silence live mode pauses itself.

## What goes where

| What | Where it goes |
|---|---|
| Your messages and the conversation | `api.mistral.ai` (chat completions) |
| Your voice, *phone's recognition* | The phone's recognizer — usually Google. Tick *Prefer on-device recognition* to keep it on the phone (needs the offline language pack). |
| Your voice, *Mistral Voxtral* | `api.mistral.ai` (transcriptions), trimmed to the speech |
| Replies read by *Mistral voice* | `api.mistral.ai` (speech) |
| Your API key | Encrypted on the phone; sent only to `api.mistral.ai` |
| Conversation history | Memory only; gone when the app is closed |

There is no analytics, no crash reporting, and no other server involved.

## Build it yourself

```bash
./gradlew assembleRelease      # APK in app/build/outputs/apk/release/
./gradlew testDebugUnitTest    # all tests: logic, the voice loop, the screens
```

Requirements: JDK 17 and an Android SDK with API 36.

The screen tests run on the JVM with Robolectric, so no emulator is needed; the first
run downloads Robolectric's Android framework jar. They save a screenshot of every
step to `app/build/outputs/ui-screenshots/`, and CI attaches those to each run (the
`test-reports` artifact).

## How it is put together

```
app/src/main/java/de/localvoice/mistralhandsfree/
├─ mistral/    API client: streaming chat, models, voices, speech, transcription.
│              No Android classes, so it is tested against a local HTTP server.
├─ domain/     Pure logic: sentence splitting, speech cleanup, voice commands,
│              conversation window
├─ speech/     Recognition (system + Voxtral), speech output (system + Voxtral),
│              the silence detector
├─ session/    LiveSessionController: the state machine of the loop
├─ service/    Foreground service: keeps it running with the screen off
├─ auth/       API key handling and Keystore-backed storage
├─ data/       Settings
├─ llm/        The chat model behind a small interface
└─ ui/         Compose: live screen, sign-in, settings
```

How it is tested: `mistral/` runs against `MockWebServer`, so real HTTP and real
server-sent events; the loop in `session/` runs against scripted speech engines on
virtual time; and `ui/AppFlowTest` starts the real `MainActivity` on Robolectric,
pointed at a stub Mistral server.

The conversation state hangs off the `Application`, not the activity: rotating the
screen or switching apps does not tear the conversation down. To keep the voice
from waiting for the whole answer, the `SentenceChunker` cuts the token stream at
sentence boundaries and hands each finished sentence to the speaker immediately;
ordinals ("May 3.") and abbreviations are not treated as sentence ends.

## Signing

`keystore/` contains the signing key and its password in plain text. That is
deliberate, and a trade-off you should know about.

**Why:** Android only installs an update if it is signed with the same key as the
installed version. A CI without a fixed key signs every run with a fresh one, so no
update would ever install — you would have to uninstall every time and lose your
settings.

**What it costs:** the key proves nothing about where an APK came from. Anyone can
build an APK that devices accept as an update of this app and that then runs with
the data of the installed one. **For this app that includes your stored Mistral API
key**, which makes the trade-off more serious than for an app without credentials.

**What to do about it, in order of effort:**

1. Install APKs only from [this repo's release][release], and look at the source first.
2. Better: give the CI a **private key** that exists nowhere in the repo. Create one,
   and store it as repository secrets (*Settings → Secrets and variables → Actions*):

   ```bash
   keytool -genkeypair -alias handsfree -keyalg RSA -keysize 4096 -validity 36500 \
           -keystore private.jks
   base64 -w0 private.jks            # paste as SIGNING_KEYSTORE_BASE64
   ```

   Also set `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`.
   The workflow then signs with that key and the committed one is never used. (Uninstall
   once first: a different key cannot install over an app signed with another.)
3. Or build it yourself and replace the key in `keystore/` with your own.

The fingerprint of the committed key, which CI checks on every build:

```
SHA-256  5C:3F:25:0B:96:A7:06:6D:7F:11:32:B1:0F:0D:6A:32:
         7B:DD:23:F8:EE:2F:3A:99:44:72:DB:BD:10:4A:55:F4
```

## What it cannot do (yet)

- **Interrupt by talking.** While the app reads out, it does not listen. Real
  barge-in needs recording and playing at the same time plus echo cancellation; the
  stop button is the stand-in.
- **Wake word.** Live mode is started with the button.
- **Work offline.** The local chat this grew out of ran its model on the phone; this
  one needs a connection for every answer. The phone's recognition and voice can stay
  on the device, but the text of each turn always goes to Mistral.
- **Follow the language of the conversation with the phone's voice.** It reads in the
  one language chosen in the settings.
- **Bluetooth headset routing** is left to Android's defaults.
- **Anything with images, files or tools.** It is a voice chat.

[basis]: https://github.com/s-vlaude-netizen/-full-local-live-audio-chatbot-offline-
[release]: https://github.com/s-vlaude-netizen/mistral-handsfree/releases/tag/dev-latest
[release-apk]: https://github.com/s-vlaude-netizen/mistral-handsfree/releases/latest/download/handsfree-for-mistral.apk
