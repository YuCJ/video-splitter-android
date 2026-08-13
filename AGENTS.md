# Video Splitter (Android)

把一支影片切成多段 mp4（供 Instagram 上傳），輸出到相簿 `Movies/VideoSplitter/`。

- 轉檔一律用 Jetpack Media3 Transformer，不要改用 FFmpeg 或 MediaMuxer/MediaExtractor——已定案的技術決策。
- 輸出一律 H.264 + AAC mp4（IG 相容性）；盡量保留來源品質（解析度、幀率不動，視訊 bitrate 沿用來源）。
- 一次只跑一個 Transformer instance（循序處理），避免 MediaCodec 資源衝突。
- IG 選檔清單照 `DATE_ADDED`（秒精度）排序，且 MediaProvider 會在 insert 時蓋掉 app 給的日期欄位、發佈時 scan 又重算 `DATE_TAKEN`/`DATE_MODIFIED`——所以不要寫這些欄位，改靠「每段 insert 之間跨秒」讓時間戳嚴格遞增（part_001 最早）。
- Release 簽章：CI 從 repo secrets（`RELEASE_KEYSTORE_BASE64` / `RELEASE_KEYSTORE_PASSWORD`）還原固定 keystore，經 `RELEASE_KEYSTORE_PATH` / `RELEASE_KEYSTORE_PASSWORD` 環境變數餵給 Gradle——簽章不一致的 APK 無法覆蓋更新。本機建置沒設環境變數時退回 debug key。
- 版本號由 CI 注入（`-PappVersionCode`/`-PappVersionName`，tag 為 `v<versionName>`），app 內的更新檢查（`UpdateManager`）靠這個 tag 格式與 GitHub Releases API 比對——改版本方案時 workflow、build script、`UpdateManager` 要一起改。
- 更新檢查只由使用者按按鈕觸發，不要加自動檢查。
- repo 內不准放任何 keystore、API key 或個人資訊；`local.properties`、`*.keystore` 已在 `.gitignore`。
- Commit 訊息用 conventional commits（`feat:`、`fix:`、`chore:`…）。
- `README.md` 與 `CLAUDE.md` 都是指向本檔的 symlink（GitHub 預覽用、Claude Code 只讀 `CLAUDE.md`）。
