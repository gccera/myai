# MyAI — offline AI chat & image generation for Android

> Rename the app by changing `app_name` in `app/src/main/res/values/strings.xml` (it is currently "MyAI"
> as a stand-in for "[APP NAME]") and `applicationId` in `app/build.gradle.kts`.

A private assistant that runs **entirely on the phone**: chat with small LLMs and generate images with
Stable Diffusion. No backend, no paid APIs, no analytics, no ads. The network is used only to download
model files the user picks. Chats, prompts and images never leave the device.

**Status:** Phases 1–3 are implemented, build, and pass 46 unit tests. Phase 1 has been run on a real
12 GB phone; Phases 2–3 have not yet been run on a phone (see §3).

| Phase | Features |
|---|---|
| 1 | Onboarding device check, model manager (resumable, SHA-256), chat, image generation, gallery, safety, settings |
| 2 | Voice input (Whisper), read aloud (TTS), chat with documents (RAG), image understanding (Gemma 4 E2B), upscaling ×2/×4, background removal, OCR + translation (ML Kit) |
| 3 | **Code** tab: Sora editor, projects (app storage or phone folders), file tree, search, AI assistant with diff Accept/Reject, AI autocomplete, run Python (Chaquopy) and HTML/CSS/JS (WebView), Git (JGit), optional cloud model, battery saver |

## 1. Engine choices (checked November 2026)

| Task | Chosen | Why |
|---|---|---|
| **Chat** | **Google AI Edge LiteRT-LM** `com.google.ai.edge.litertlm:litertlm-android:0.20.0` | The MediaPipe **LLM Inference API is deprecated / maintenance-only on Android**. Google points Android developers to LiteRT-LM, which is actively released (0.20.0 shipped Nov 2026). It runs `.litertlm` models on CPU (XNNPACK) or GPU, streams tokens, supports cancellation, and has ready-converted models in the `litert-community` Hugging Face org. llama.cpp/JNI was not needed. |
| **Images** | **ONNX Runtime Mobile** `com.microsoft.onnxruntime:onnxruntime-android:1.32.0` | The MediaPipe **Image Generator** is still documented but experimental, has had no release since July 2025, and needs a desktop conversion script; there are no hosted, ready-to-download weights. ONNX Runtime runs standard diffusers ONNX exports that can be downloaded directly from Hugging Face. |

| **Voice / docs / image tools** | ONNX Runtime (same runtime as images) | Whisper Base (MIT), MiniLM-L6 embeddings, Swin2SR ×2/×4 and MODNet (Apache-2.0), all as ONNX. Each was run on desktop against a reference implementation (§3). |
| **Vision** | LiteRT-LM `Content.ImageBytes` + `visionBackend` | Gemma 4 E2B is multimodal; checked on desktop (it correctly described a generated photo). |
| **OCR / translation** | Google ML Kit (as requested) | **Caveat:** ML Kit sends Google usage/performance metrics. Its auto-start is removed; it starts only after the user consents in the Tools tab (§6). |
| **Code editor** | Sora Editor 0.24.7 + TextMate grammars | **LGPL-2.1**: allowed in a commercial app; the licence text and notice are shown in-app, and you must offer the library source/relinking on request. |
| **Python** | Chaquopy 17.2 (MIT), CPython 3.13 | Standard library only; adds ~15–20 MB per ABI. |
| **Git** | JGit **5.13.5** | JGit 6/7 need Java 11/17 APIs missing on Android 8. 5.13.5 is the latest Java-8 line (with security fixes). JMX stats, auto-gc and global gitconfig are disabled for Android. |

**Why SD 1.5 and not SD-Turbo:** the only fp16 SD-Turbo ONNX export on Hugging Face (`tlwu/sd-turbo-onnxruntime`)
uses CUDA-only operators (`NhwcConv`, `GroupNorm`, `SkipGroupNorm`, `BiasSplitGelu`) and **would crash on a phone**.
The fp32 export is 5 GB. The bundled SD 1.5 export uses only standard ONNX opset-14 operators; I checked every node
in its graph. The pipeline also supports SD 2.x/Turbo (1024-d text encoder, `"!"` pad token, trailing timestep
spacing, v-prediction), so you can add a portable Turbo export later through the configurable catalog.

## 2. Models (all allow commercial use; the licence is shown in-app)

Every file is pinned to a Hugging Face **commit hash** and verified with **SHA-256** after download.
The list lives in `app/src/main/assets/model_catalog.json`; users can also add custom models in the app.

| Model | Type | Download | RAM class (min / recommended) | Licence | Gated? |
|---|---|---|---|---|---|
| Qwen3 0.6B (int4) | chat | 343 MB | 3 / 4 GB | Apache 2.0 | no |
| Gemma 3 1B IT (int4) | chat | 584 MB | 4 / 6 GB | Gemma Terms of Use | **yes**¹ |
| Qwen2.5 1.5B Instruct (int8) | chat | 1.6 GB | 6 / 8 GB | Apache 2.0 | no |
| Llama 3.2 3B Instruct (int4) | chat | 2.2 GB | 8 / 12 GB | Llama 3.2 Community License² | **yes**¹ |
| Gemma 4 E2B IT | chat | 2.6 GB | 8 / 12 GB | Apache 2.0 | no |
| Stable Diffusion 1.5 (ONNX fp16) | image | 2.1 GB | 6 / 8 GB | CreativeML Open RAIL-M³ | no |
| Qwen2.5-Coder 1.5B Instruct (int4) | code | 1.1 GB | 4 / 6 GB | Apache 2.0 | no |
| Qwen2.5-Coder 7B Instruct (int4)⁴ | code | 3.9 GB | 12 / 12 GB | Apache 2.0 | no |
| Whisper Base (int8) | voice | 112 MB | 3 GB | MIT | no |
| MiniLM-L6-v2 (int8) | doc search | 23 MB | 2 GB | Apache 2.0 | no |
| Swin2SR ×2 / ×4 | upscale | 54 + 55 MB | 3 GB | Apache 2.0 | no |
| MODNet | background removal | 26 MB | 3 GB | Apache 2.0 | no |

1. On Hugging Face, the user must accept the licence on the model page and paste a free **read token** in
   Settings. The token is stored on the device and sent only to huggingface.co.
2. Free commercial use under 700M monthly active users. "Built with Llama" attribution is shown in the app.
3. The licence's use restrictions must be passed on to users. The full text is bundled and shown in the app.
4. Community LiteRT-LM conversion. Its built-in chat template crashes in LiteRT-LM, so the app overrides
   it with a ChatML template (`chatTemplate: "chatml"`); with that, it was checked on desktop (~2.8 tokens/s
   on 4 cores). **Qwen2.5-Coder 3B is deliberately excluded:** it uses the non-commercial Qwen Research
   Licence (the copy on Hugging Face is wrongly tagged Apache-2.0).

**Recommendations by RAM:** `DeviceInspector` rounds the RAM Android reports up to the advertised size
(for example, 7.4 GiB counts as an 8 GB phone). `ModelRecommender` then picks the largest model rated
"recommended" for that size. This is unit-tested for 4, 8 and 12 GB phones.

## 3. What has been verified, and what has not

| Check | Result |
|---|---|
| Debug build, signed release AAB (R8) | ✅ AAB 113 MB with both ABIs; an arm64 phone gets ≈125 MB installed |
| Unit tests (`./gradlew testDebugUnitTest`) | ✅ 46 / 46 |
| **Phase 1 on a real phone (12 GB)** | ✅ ran. ⚠ **Found:** image generation stalled at "Step 0" because 12 GB phones took a batch-2 path that needs >6 GB. **Fixed:** guidance always runs as two batch-1 passes (measured peak ≈4.5–4.9 GB at 512 px) and only fast cores are used. Not yet re-tested on the phone. |
| SD 1.5 pipeline (desktop ORT, the app's own code) | ✅ correct 512×512 image |
| Chat (LiteRT-LM JVM, Qwen3-0.6B) | ✅ streaming, multi-turn, Stop |
| **Whisper** log-mel vs HF `WhisperFeatureExtractor` | ✅ max diff 1.2e-7; real LibriSpeech clips transcribed correctly |
| **WordPiece** vs HF `BertTokenizer`; **embeddings** vs ONNX reference | ✅ identical IDs; cosine 1.000000 (fp32) / 0.99 (int8 shipped) |
| **Swin2SR** ×2/×4 tiled; **MODNet** matting | ✅ visual check on a generated image |
| **Gemma 4 E2B vision** (LiteRT-LM JVM) | ✅ "The photo shows a single red apple resting on a wooden surface." |
| **Qwen2.5-Coder 1.5B / 7B** chat + code completion | ✅ (7B only with the ChatML override, see §2) |
| **Git** (JGit): init, commit, branch, checkout, push, clone, pull | ✅ unit test against a local bare remote |
| **Python runner** (`runner.py`) under CPython 3.13 | ✅ stdout/stdin, tracebacks, `sys.exit`, project imports, Stop |
| Room migration v1→v2 | ✅ SQL matches Room's exported schema exactly |
| **Phases 2–3 on a phone** (mic, TTS, ML Kit, Sora editor UI, WebView preview, Chaquopy on device, SAF folders) | ❌ **not run yet** — no device here. These use well-trodden Android APIs, but please test them. |

Running real models found these problems, all now fixed:
- The image-generation memory spike described above.
- Qwen3 replies landing in LiteRT-LM's "thought" channel, which produced empty replies.
- The repetition penalty discouraged reproducing code from the prompt, so it is now off for code.
- Raw fill-in-the-middle tokens don't work with the LiteRT-LM Qwen Coder builds, so autocomplete uses an
  instruction prompt instead.
- The 7B coder's built-in chat template crashed (see §2).
- JGit failed on unknown `~/.gitconfig` options, so it now ignores global config.

## 4. Architecture

```
app/src/main/java/com/gccera/myai/
├── MyAiApp.kt                  Hilt app; WorkManager factory; frees the model on memory pressure
├── MainActivity.kt             Compose host, theme, onboarding gate
├── di/AppModule.kt             Room, OkHttp (downloads only), WorkManager, app scope
├── data/
│   ├── catalog/                ModelSpec + configurable catalog (assets JSON + user-added models)
│   ├── models/                 FileDownloader (Range resume + SHA-256), ModelDownloadWorker (foreground),
│   │                           ModelStorage (no-backup dir), ModelRepository (state from disk + WorkManager)
│   ├── db/                     Room: conversations, messages, images, reports
│   ├── repo/                   ChatRepository, ImageRepository (MediaStore export)
│   └── settings/               DataStore settings
├── device/                     DeviceInspector (RAM, SoC, storage, ABI) + ModelRecommender
├── engine/
│   ├── ModelSessionManager.kt  ONE model in memory at a time (mutex; unloads chat <-> image)
│   ├── llm/LlmEngine.kt        LiteRT-LM wrapper: streaming, KV-cache reuse, Stop, history trimming
│   ├── sd/                     Pure-Kotlin CLIP BPE tokenizer, Euler-Ancestral scheduler, fp16, ORT pipeline
│   ├── ImageGenerationController.kt  app-scoped generation (survives leaving the screen), cancel
│   └── PromptImprover.kt       "Improve my prompt" using the chat model
│   ├── stt/ rag/ tools/ tts/   Whisper, MiniLM + chunker, Swin2SR/MODNet, TextToSpeech (Phase 2)
│   └── code/                   SecretStore (Keystore AES-GCM), GitService (JGit), CloudClient (BYOK),
│                               CodeAssistant, PythonRunner (Chaquopy), BatteryMonitor (Phase 3)
├── data/code/                  ProjectFs (app storage via File, phone folders via SAF), ProjectRepository
├── safety/                     Blocked-term filter (accents/leetspeak/spacing) + local reports
└── ui/                         chat, image, code, tools, gallery, models, settings, onboarding
app/src/main/python/runner.py   Runs user Python with streamed output, stdin and Stop
```

**One model at a time:** every use goes through `ModelSessionManager.withChat` or `withImage`. These calls
take an exclusive lock, unload the other model type, and load the requested one. A second request at the
same time fails quickly with a friendly "please wait" message instead of loading two multi-GB models.

## 5. Build and run on a real phone

Requirements: Android Studio (2026.x or newer), JDK 17+, Android SDK Platform **37.2** (Compose 1.13
requires API 37.1 or newer), and an **arm64** phone running Android 8.0 or newer. 32-bit-only phones are
not supported by LiteRT-LM.

```bash
git clone <this repo> && cd myai
./gradlew assembleDebug                 # or open the folder in Android Studio
```
1. On the phone, enable **Developer options → USB debugging** (tap *Build number* 7 times).
2. Connect it over USB and accept the prompt, then run `adb devices` to confirm.
3. `./gradlew installDebug`, or press **Run ▶** in Android Studio.
4. In the app, onboarding shows the device check and the recommended models. Use Wi-Fi to download.
5. Try a chat, then **Image → Generate**. The first image takes longest because the model has to load.

Run the tests with `./gradlew testDebugUnitTest`.

**Expected speed (rough guide; not yet measured on a phone):** small chat models stream at roughly
10–30 tokens/s on recent arm64 CPUs, and faster with the GPU option. SD 1.5 at 512 px takes 8.5 s
per step on 4 desktop cores, so expect **1–4 minutes per image** on a recent flagship.

## 6. Create a signed release AAB for Google Play

1. Create an upload key once. Keep it safe and **never commit it**:
   ```bash
   keytool -genkeypair -v -keystore ~/keys/myai-upload.jks -alias upload \
     -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Create `keystore.properties` in the project root. It is already in `.gitignore`:
   ```properties
   storeFile=/absolute/path/to/myai-upload.jks
   storePassword=...
   keyAlias=upload
   keyPassword=...
   ```
3. Bump `versionCode` and `versionName` in `app/build.gradle.kts`, then run:
   ```bash
   ./gradlew bundleRelease
   # -> app/build/outputs/bundle/release/app-release.aab
   ```
4. In Play Console, enrol in **Play App Signing** and upload the AAB.
5. Fill in the Play Console declarations:
   - **Data safety:** the App itself collects nothing. **Google ML Kit** (only after the user enables OCR/
     translation) collects *App info and performance → Diagnostics* and device info for Google; declare
     it as ML Kit's data disclosure page describes. Git and the optional cloud model send data only when the
     user triggers them, to the server/provider they configure (user-initiated transfer).
   - **Privacy policy URL:** host `PRIVACY_POLICY.md` and set `privacy_policy_url` in `strings.xml`.
   - **AI-generated content:** the app filters prompts and replies and has an in-app **Report** button
     on every reply and image. Set `report_email` in `strings.xml`.
   - **Foreground service (dataSync):** explain that it shows progress for user-started model downloads.
   - **Microphone:** used only while the user records voice input; processed on device.
   - **LGPL (Sora Editor):** keep `licenses/code-editor.txt` in the app and be ready to provide the library
     source on request.

## 7. Customising

- **Models:** edit `assets/model_catalog.json`. For every file, give the URL pinned to a commit, the size
  and the SHA-256. The unit test `ModelCatalogTest` enforces this.
- **Blocked terms:** edit `assets/safety/blocked_terms.txt`.
- **Your own image model:** export with standard ONNX ops only, for example
  `optimum-cli export onnx --model <sd-model> --task text-to-image out/`. Check that the graph has no
  `com.microsoft` ops, host it on Hugging Face, and add it in **Models → Custom**.

## 8. Known limitations

- Phases 2–3 have not been run on a phone yet (§3). Check first: the microphone flow, ML Kit consent and
  translation download, the Sora editor (keyboard, selection, symbol row), the WebView preview, Python on
  the device, opening a phone folder (SAF), and Git over HTTPS with a token.
- Image generation and image tools run on the CPU. Expect minutes per 512 px image.
- The safety filter matches keywords. There is no image NSFW classifier.
- Autocomplete is a suggestion bar above the keyboard (tap to insert), not inline ghost text, and it pauses
  on low battery, Battery Saver or when the phone is hot.
- Git works with projects in app storage (create or clone). Folders opened from phone storage can be
  edited and run, but can't use Git.
- Python: standard library only (no pip packages). `input()` reads from the "stdin" box in the console.
- Background removal is trained on people, so other subjects can have rough edges.
- Gemma 3 and Llama downloads need a Hugging Face token (licence-gated upstream).
