# BLACK MASK AI — Personal AI Assistant (Android, Java)

A Jarvis-style AI assistant app: speak or type, and it does things —
opens apps, messages people on WhatsApp by name, calls, sets alarms,
and chats with Google Gemini AI. It keeps running in the background
with a notification, so you can talk to it any time.
Built with plain Java + Android SDK (no libraries),
so GitHub Actions can build the APK for free.

## How to build the APK (no Android Studio needed)

1. Create a **new public repo** on GitHub (e.g. `blackmask-ai`).
2. Upload **all files from this folder** to the repo (keep the same
   folder structure, including `.github/workflows/build.yml`).
3. Go to the repo's **Actions** tab → the **Build APK** run starts
   automatically → wait for the green checkmark.
4. Open the finished run → **Artifacts** → download **blackmask-ai-apk**
   → unzip it → install `app-debug.apk` on your phone
   (allow "Install unknown apps" if asked).

## First run

1. Open the **Black Mask AI** app. Allow microphone, contacts and
   notifications when asked — they are needed for voice, WhatsApp
   messages by name, and background running.
2. Get a free Gemini API key: https://aistudio.google.com/apikey
3. Paste the key in the **Gemini API key** box and tap **Save key**.
4. Tap the 🎤 mic and speak, or type a message and tap **Send**.
5. The app keeps running in the background — pull down the
   notification shade any time and tap **Listen** to talk to it
   without opening the app.

## What it can do

- **Runs in the background** — starts on phone boot, stays alive with a
  notification. Tap **Listen** in the notification any time to talk to
  it without opening the app. If the app is swiped away, the service
  restarts itself. Tap **⚡ Keep alive in background** once
  to stop the phone's battery saver from killing it.
  **Only when the internet is off does the AI chat stop** — local
  commands (time, alarms, open apps, notes, calculator) still work.
- **Wake word** — tick "Wake word (any language)" and just call it in
  any language — "Hey Black Mask", "ব্ল্যাক মাস্ক", "ब्लैक मास्क",
  "Jarvis", "জার্ভিস" — to talk (uses more battery).
- **Floating mic** — tick "Floating mic" and a small logo bubble stays
  on top of other apps; drag it anywhere, tap it to talk.
- **Home screen widget** — long-press the home screen → Widgets →
  Black Mask AI, tap the logo to talk.
- **Easy for everyone** — suggestion buttons under the chat: if you say
  something it does not understand, it shows options to tap.
  Chat history is saved, so old chats are still there when you reopen.
- **HUD status** — battery %, time and SYSTEM ONLINE readout at the top.
- **Red Black Mask theme** — black + red laser design with your logo
  and a glowing pulsing orb.
- **Chat** — any question, answered by Gemini (Bengali / Hinglish / English)
- **Voice** — mic button listens, replies are spoken aloud (can be switched off)
- **Open any app** — "open youtube", "open chrome", "open camera"
- **WhatsApp by name** — "whatsapp Rahul I am coming" (opens the chat
  with your message ready — WhatsApp does not let any app press Send
  by itself, so the final tap is yours)
- **Call** — "call Maa" (calls directly, no tap needed)
- **SMS** — "sms Rahul I am coming"
- **Alarm** — "set alarm 7 am", "set alarm 7:30 pm"
- **Timer** — "set timer 5 minutes"
- **Flashlight** — "flashlight on", "flashlight off"
- **Search / play** — "search cricket score", "play faded on youtube"
- **Time / date / weather / news** — "what is the time", "weather", "news"
- **Calculator** — "calculate 25 * 4", "what is 25 plus 30"
- **Translate** — "translate hello in Bengali"
- **Cricket** — "cricket score" (opens live scores)
- **Reminders** — "remind me in 10 minutes to drink water", "remind me at 7 pm to call Maa"
- **Notes** — "note buy milk tomorrow", "show my note"
- **Jokes / help** — "tell me a joke", "help"

## Your logo

Your `logo.png` is already in the app (`app/src/main/res/drawable/`) —
it shows at the top and is the app icon too. To change it later, just
replace that file with a new `logo.png` and rebuild.

## Changing the weather city

In `CommandEngine.java`, find `fetchWeather()` and change the
`latitude` / `longitude` in the Open-Meteo URL to your city.

## Notes / limits

- Background listening works from the notification's **Listen** button.
  Some phones (Xiaomi, Oppo, Vivo) aggressively kill background apps —
  if the service stops, open the app once or lock it in Recent apps.
- WhatsApp messages: the chat opens with the text typed, you tap Send.
  No app can send WhatsApp messages fully automatically.
- The Gemini key is stored only on your phone, never uploaded anywhere.

## If the build fails

Open the failed Actions run, copy the red error lines and send them to
Muse — the fix is usually a one-line change.
