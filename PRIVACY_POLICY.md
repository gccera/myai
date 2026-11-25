# Privacy Policy

**Effective date:** 25 November 2026

This app ("the App") is an offline AI assistant for chat, images, voice, documents and code. All
artificial-intelligence processing happens **on your device** unless you explicitly turn on the optional
cloud model described in section 4.

## 1. Summary

- **Your content stays on your device.** Chats, prompts, voice recordings, photos you attach, imported
  documents, generated images, code projects and settings are stored only in the App's private storage.
- **No account, no servers of ours, no ads, no analytics in the App.** The App contains no advertising
  or analytics SDK. (Google ML Kit, used only if you enable text recognition/translation, reports its own
  usage metrics to Google — see section 3.)
- **The internet is used only for things you start:** model downloads, and the optional features in
  sections 3–4 (ML Kit language packs, Git clone/pull/push, the optional cloud model, and internet access
  in the web preview if you switch it on).

## 2. Model downloads

The App downloads AI model files you choose from Hugging Face (huggingface.co and its CDN). Hugging Face
receives standard technical information (IP address, requested file). An optional Hugging Face token,
needed only for licence-gated models, is stored on your device and sent only to Hugging Face.

## 3. Google ML Kit (text from photos, translation) — off until you enable it

Text recognition and translation use Google ML Kit. The photos and text you process are handled **on your
device** and are not sent to Google. However, Google's ML Kit SDK sends Google **usage and performance
metrics** (for example device model, OS version and app information) for diagnostics, and translation
language packs are downloaded from Google. ML Kit is not started until you tap "Enable ML Kit features" in
the Tools tab, and you can turn it off there. See Google's ML Kit terms and data disclosure.

## 4. Optional features that send data only when you use them

- **Git (Code tab):** cloning, pulling and pushing contact the Git server you specify (e.g. GitHub) and
  transfer your repository. Your access token is encrypted with the Android Keystore and sent only to
  that server.
- **Cloud model ("bring your own key", Code tab):** OFF by default. If you enable it in Settings and
  choose "Use cloud model" for a request, the code and question in that request are sent to the
  provider you configured, under that provider's terms. Your API key is encrypted with the Android Keystore.
- **Web preview (Code tab):** pages you preview cannot load anything from the internet unless you switch
  "Internet" on for that preview.

## 5. Microphone

Voice input records audio only while you hold the conversation's microphone on. The audio is transcribed
on the device by the Whisper model and is never saved or sent anywhere.

## 6. Data stored on your device

| Data | How to delete |
|---|---|
| Conversations, messages, attached photos | Delete a chat, or Settings → Clear all data |
| Imported documents and their search index | Tools → Documents, or Clear all data |
| Generated and edited images | Gallery, or Clear all data |
| Code projects in app storage | Code → remove project, or Clear all data |
| Downloaded models | Settings → Models |
| Settings, Git/cloud credentials (encrypted), content reports | Settings → Clear all data |

Uninstalling the App deletes all of the above. Android cloud backup and device-to-device transfer are
disabled for the App. Folders you open from your phone's storage in the Code tab stay where they are.

## 7. Sharing that you start

Data leaves the App when **you** choose to share a message, image or text, save an image to your gallery,
send a content report by e-mail, or use the features in sections 3–4.

## 8. Permissions

Internet (downloads and the features above) · Notifications/foreground service (download progress) ·
Microphone (voice input, only when you tap it) · Storage on Android 8–9 (saving images to your gallery).

## 9. Children

The App is not directed at children under 13 and does not knowingly collect personal data from anyone.

## 10. AI-generated content

AI output can be wrong or inappropriate. The App filters prompts and replies against blocked terms and
lets you report any reply or image in the App. Reports are stored on your device; you can optionally
e-mail one to the developer.

## 11. Changes and contact

We will update this policy in the App and on our store page. Questions: see the contact e-mail on the
App's Google Play listing.
