# Video Splitter (Android)

把一支影片切成多個片段（例如供 Instagram 上傳），輸出 H.264 + AAC 的 mp4 到相簿 `Movies/VideoSplitter/`。

`README.md` 是指向本檔的 symlink，僅為了 GitHub 預覽。

## 架構

- Kotlin + Coroutines + Jetpack Compose (Material 3)。
- 轉檔用 **Jetpack Media3 Transformer**（裁切 + re-encode）。不要改用 FFmpeg 或 MediaMuxer/MediaExtractor——這是已定案的技術決策。
- 輸出一律 H.264 + AAC mp4（IG 相容性）；解析度與幀率保留來源，視訊 bitrate 沿用來源影片的 bitrate。
- 切割在 Foreground Service（`SplitService`）執行，循序處理、一次只跑一個 Transformer instance，避免 MediaCodec 資源衝突。

## 檔案職責

| 檔案 | 職責 |
| --- | --- |
| `app/src/main/java/dev/yucj/videosplitter/MainActivity.kt` | Compose UI：選影片、每段秒數 slider、切段模式 toggle、段數預覽、進度、結果清單 |
| `.../MainViewModel.kt` | UI 狀態、讀影片長度（MediaMetadataRetriever）、啟動/取消 service |
| `.../split/SplitPlanner.kt` | 純邏輯：把總長度依模式（平均分配 / 固定長度）算成各段起訖時間 |
| `.../split/SplitService.kt` | Foreground Service：循序 export 各段、進度通知、取消、寫入 MediaStore、清暫存 |
| `.../split/SegmentExporter.kt` | 包一段 Transformer export 成 suspend function（`suspendCancellableCoroutine`），含 HDR tone-map fallback 與進度輪詢 |
| `.../split/MediaStoreSaver.kt` | 把私有目錄的 mp4 複製進 MediaStore `Movies/VideoSplitter/` |
| `.../split/SplitState.kt` | Service ↔ UI 的狀態流（process 內 singleton `StateFlow`） |

## 行為細節

- 切段模式：
  - 平均分配：段數 = `ceil(duration / 最大秒數)`，每段等長。
  - 固定長度：每段 = 設定秒數，最後一段為剩餘長度。
- 單段 export 失敗不中斷整批；HDR 來源失敗會強制 tone map 成 SDR 重試一次，最後回報成功/失敗清單。
- 取消時停止當前 export 並刪除 `filesDir/splits/` 暫存檔。
- 全部段落完成後才寫入 MediaStore。
- MediaStore 的 `DATE_TAKEN`/`DATE_ADDED` 以原始影片拍攝時間為基準、每段 +index 秒（part_001 最早），讓 IG 等照時間排序的選檔清單維持段落順序；原檔讀不到拍攝時間時以完成當下為基準。

## 建置

```bash
./gradlew :app:assembleRelease
```

- Release build 用 debug keystore 簽章（repo 內沒有、也不准放任何私有簽章檔或 API key）。
- CI/CD：push 到 `main` 會觸發 `.github/workflows/release.yml`，打包 release APK 並建立 GitHub Release 供下載。

## 慣例

- Commit 訊息用 conventional commits（`feat:`、`fix:`、`chore:`…）。
- 依賴版本集中在 `gradle/libs.versions.toml`。
