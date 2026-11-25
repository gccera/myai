# MyAI — offline AI chat & image generation for Android

> Rename the app by changing `app_name` in `app/src/main/res/values/strings.xml` (it is currently "MyAI"
> as a stand-in for "[APP NAME]") and `applicationId` in `app/build.gradle.kts`.

A private assistant that runs **entirely on the phone**: chat with small LLMs and generate images with
Stable Diffusion. No backend, no paid APIs, no analytics, no ads. The network is used only to download
model files the user picks. Chats, prompts and images never leave the device.

**Status:** Phase 1 is complete and builds. Phase 2 has not started (see the end of this file).

---

## 1. Engine choices (checked November 2026)

| Task | Chosen | Why |
|---|---|---|
| **Chat** | **Google AI Edge LiteRT-LM** `com.google.ai.edge.litertlm:litertlm-android:0.20.0` | The MediaPipe **LLM Inference API is deprecated / maintenance-only on Android**. Google points Android developers to LiteRT-LM, which is actively released (0.20.0 shipped Nov 2026). It runs `.litertlm` models on CPU (XNNPACK) or GPU, streams tokens, supports cancellation, and has ready-converted models in the `litert-community` Hugging Face org. llama.cpp/JNI was not needed. |
| **Images** | **ONNX Runtime Mobile** `com.microsoft.onnxruntime:onnxruntime-android:1.32.0` | The MediaPipe **Image Generator** is still documented but experimental, has had no release since July 2025, and needs a desktop conversion script; there are no hosted, ready-to-download weights. ONNX Runtime runs standard diffusers ONNX exports that can be downloaded directly from Hugging Face. |

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

1. On Hugging Face, the user must accept the licence on the model page and paste a free **read token** in
   Settings. The token is stored on the device and sent only to huggingface.co.
2. Free commercial use under 700M monthly active users. "Built with Llama" attribution is shown in the app.
3. The licence's use restrictions must be passed on to users. The full text is bundled and shown in the app.

**Recommendations by RAM:** `DeviceInspector` rounds the RAM Android reports up to the advertised size
(for example, 7.4 GiB counts as an 8 GB phone). `ModelRecommender` then picks the largest model rated
"recommended" for that size. This is unit-tested for 4, 8 and 12 GB phones.

## 3. What has been verified, and what has not

| Check | Result |
|---|---|
| `./gradlew assembleDebug` | ✅ builds |
| `./gradlew bundleRelease` (R8, signed with a test key) | ✅ 57 MB AAB, `jarsigner` verified |
| Unit tests (`./gradlew testDebugUnitTest`) | ✅ 34 / 34 pass |
| CLIP tokenizer vs Hugging Face `CLIPTokenizer` | ✅ identical IDs (includes accents and punctuation) |
| Scheduler vs diffusers timesteps / σ | ✅ |
| Resumable download + SHA-256 (MockWebServer) | ✅ resume via `Range`, restart if `Range` is ignored, corrupt files discarded, 401 message |
| SHA-256 of every SD 1.5 and Qwen3 file in the catalog | ✅ downloaded and verified |
| **Image pipeline, real model:** the app's own `engine/sd/*.kt` run on desktop ONNX Runtime | ✅ correct 512×512 SD 1.5 image, ~3 min on 4 x86 cores |
| **Chat engine, real model:** the app's own `LlmEngine.kt` run on `litertlm-jvm` 0.20.0 with Qwen3-0.6B | ✅ streaming, multi-turn reuse, Stop, rebuild after Stop, prompt improver |
| Running on a **physical phone or emulator** | ❌ **not done.** No device was available and the build machine has no KVM. **This is the next step.** |

Running the real models exposed two bugs that compiling alone would have missed. Both are fixed:
- **Memory:** SD at 512 px with a batch-2 classifier-free-guidance pass reached 6.2 GB and was OOM-killed.
  Phones below 12 GB now run guidance as two batch-1 passes with ONNX Runtime's memory arena turned off.
  Measured peak: **about 4.5 GB at 512 px and about 3.8 GB at 384 px**. Phones below the 8 GB class
  default to 384 px.
- **Empty replies:** on later turns, Qwen3's reply could arrive in LiteRT-LM's `thought` channel, so the
  reply came back empty. Thinking is now turned off, and any thought-channel text is shown as
  "Thinking…" or used as the reply. A regression test covers this.

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
├── safety/                     Blocked-term filter (accents/leetspeak/spacing) + local reports
└── ui/                         Compose screens: onboarding, chat, image, gallery, models, settings
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
   - **Data safety:** no data collected and no data shared. The app sends nothing off the device except
     model downloads from Hugging Face.
   - **Privacy policy URL:** host `PRIVACY_POLICY.md` and set `privacy_policy_url` in `strings.xml`.
   - **AI-generated content:** the app filters prompts and replies and has an in-app **Report** button
     on every reply and image. Set `report_email` in `strings.xml`.
   - **Foreground service (dataSync):** explain that it shows progress for user-started model downloads.

## 7. Customising

- **Models:** edit `assets/model_catalog.json`. For every file, give the URL pinned to a commit, the size
  and the SHA-256. The unit test `ModelCatalogTest` enforces this.
- **Blocked terms:** edit `assets/safety/blocked_terms.txt`.
- **Your own image model:** export with standard ONNX ops only, for example
  `optimum-cli export onnx --model <sd-model> --task text-to-image out/`. Check that the graph has no
  `com.microsoft` ops, host it on Hugging Face, and add it in **Models → Custom**.

## 8. Known limitations

- Not yet run on a phone (see §3). Things to check first on real hardware: the GPU backend, OEMs that
  kill background apps during long image generations, and SD memory use on 6 GB phones.
- Images run on the CPU only. NNAPI is deprecated and ORT's QNN/GPU paths are device-specific.
- The safety filter matches keywords. There is no image NSFW classifier yet (planned for Phase 2).
- The Gemma 3 and Llama downloads need a Hugging Face token (licence-gated upstream).

## 9. Phase 2 (not started: build it once Phase 1 has been checked on a phone)

| Feature | Plan |
|---|---|
| Voice input | Whisper-tiny/base on-device (whisper.cpp JNI or ONNX), push-to-talk in the chat bar |
| Read aloud | Android `TextToSpeech` (built in, no download) |
| Chat with documents | PDF/TXT import → chunking → EmbeddingGemma (LiteRT, in `litert-community`) → cosine search in Room → retrieved chunks added to the prompt |
| Image understanding | Gemma 4 E2B is multimodal in LiteRT-LM (`visionBackend`); attach a photo as `Content.ImageBytes` |
| Background removal / upscaling | ONNX models (for example a MODNet/U²-Net-style matting model, Real-ESRGAN x2/x4) through the same ORT runtime. Check each licence |
| Translation and OCR | ML Kit on-device Translation and Text Recognition v2 (on-device; language packs download once) |
| Image NSFW check | Small on-device classifier run on each generated image before it is shown |
