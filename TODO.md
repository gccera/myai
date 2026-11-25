# TODO / pending work

Status as of 25 Nov 2026 (branch `ccr-d140ec80-iy4ra1`). Everything below "Done" builds, and 49/49 unit
tests pass. Tick items off as you go (`- [x]`).

## 1. Needs you (blocking)

- [ ] **Create the Hugging Face OAuth app** so "Sign in with Hugging Face" works (button is disabled until then):
  huggingface.co/settings/applications/new → public app (no client secret) →
  redirect URI `com.gccera.myai://oauth/callback` → scopes `openid profile gated-repos read-repos` →
  add `hfOauthClientId=<id>` to `gradle.properties` and rebuild (README §6a).
- [ ] **Choose the final app name** (replace "MyAI" in `res/values/strings.xml`) and **applicationId**
  (`app/build.gradle.kts`; also the OAuth redirect scheme in `HfAccount.kt` + `manifestPlaceholders`).
- [ ] Set `report_email` and `privacy_policy_url` in `res/values/strings.xml`; host `PRIVACY_POLICY.md`.
- [ ] Decide: keep **Google ML Kit** (OCR/translation send usage metrics to Google, consent-gated) or replace it.
- [ ] Create the Play upload key + `keystore.properties` (README §6).

## 2. Test on a real phone (not yet verified on device)

Phase 1 ran on a 12 GB phone; everything else has only been verified on desktop/JVM.

- [ ] **Re-test image generation at 512 px** after the "stuck at Step 0" fix (report stage label and s/step).
- [ ] Rust theme looks right in light + dark (existing install should switch from wallpaper colours to rust).
- [ ] Upgrade from the first APK keeps chats (Room migration v1→v2) and models.
- [ ] Hugging Face: paste-token sign-in, browser search/sort, "My models", gated access check,
      "Add as Chat/Code" download + SHA-256 verify; OAuth round trip once the client ID exists.
- [ ] Voice input (mic permission, Whisper download, transcription) and Read aloud.
- [ ] Chat with a document (PDF + TXT import, sources under replies).
- [ ] Photo in chat with Gemma 4 E2B (vision).
- [ ] Tools: upscale ×2/×4, background removal; ML Kit consent → OCR → translation pack download.
- [ ] Code tab: Sora editor with the keyboard (selection, symbol row, undo/redo, find & replace),
      tabs, file tree actions, project search.
- [ ] Code: AI assistant actions + diff Accept/Reject; autocomplete suggestion bar + battery-saver pause.
- [ ] Code: run Python (Chaquopy) incl. stdin + Stop; HTML/CSS/JS preview (internet toggle, JS console).
- [ ] Code: open a phone folder via SAF (persists after restart).
- [ ] Git: init/commit/branch locally; clone + pull + push over HTTPS with a GitHub token.
- [ ] Cloud model (BYOK): enable flow, warning label, streaming reply.
- [ ] GPU backend for chat (Settings) on your phone; falls back to CPU if unsupported.
- [ ] Memory: SD 1.5 on a 6–8 GB phone at 384/512 px; background kill behaviour during long generations.

## 3. Known gaps / improvements

- [ ] No on-device **NSFW image classifier** (safety is keyword-based on prompts/replies only).
- [ ] Autocomplete is a tap-to-insert bar, not inline ghost text.
- [ ] Git only for app-storage projects (not SAF folders); no merge-conflict UI.
- [ ] Python: standard library only (no pip packages).
- [ ] Image browser on Hugging Face: only LiteRT-LM chat/code models; no ONNX image-model browsing.
- [ ] Image generation is CPU-only (consider ORT QNN/NNAPI-successor paths per chipset).
- [ ] Background removal (MODNet) is portrait-oriented; consider a general-purpose matting model.
- [ ] Release APK/AAB size (~125 MB installed on arm64): consider making Python (Chaquopy) a
      Play Feature Delivery module.
- [ ] Instrumented UI tests (Compose) and a Room `MigrationTestHelper` test on a device/emulator.
- [ ] Store listing: screenshots, descriptions, Data safety form (ML Kit, Git, BYOK — README §6),
      content rating questionnaire, AI-generated content declaration.

## Done (for reference)

- Phase 1: onboarding device check, model manager (resumable, SHA-256), chat (LiteRT-LM), image
  generation (SD 1.5 / ONNX Runtime), gallery, safety + report, settings, privacy policy.
- Phase 2: Whisper voice input, TTS, RAG documents, Gemma 4 vision, Swin2SR upscaling, MODNet
  background removal, ML Kit OCR + translation (consent-gated), Tools tab.
- Phase 3: Code tab (Sora editor, projects, file tree, search, AI assistant + diff, autocomplete,
  Python run, web preview, JGit, BYOK cloud model, battery saver).
- Hugging Face sign-in (OAuth/PKCE or token) + in-app model browser; rust brand theme.
- Fixed from real-model runs: SD memory spike on 12 GB phones, Qwen3 empty replies, code repetition
  penalty, 7B coder chat template, JGit global-config/JMX issues.
