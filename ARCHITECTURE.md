# ChariSafe システムアーキテクチャ設計書 (MVP & 将来拡張設計)

## 1. 概要と基本コンセプト

ChariSafe は、自転車ライドにおける長時間の安全を保障する「自転車向けセーフティレコーダー」アプリである。
一般的な車載ドライブレコーダーのような常時録画ファイル出力ではなく、**「リングバッファ（インメモリ/セグメント循環）」**および**「イベント発生時の前後抽出保存（指定時間）」**を採用し、バッテリー消費とストレージ消耗を最小限に抑えながら10〜12時間以上の連続運用を実現する。

---

## 2. 変更可能な動作パラメータ設定 (`AppConfig`)

以下のパラメータはアプリ設定および `AppConfig` 設定用クラスによって動的に変更可能とする。

| パラメータ名 | プロパティ名 | デフォルト値 | 選択肢・推奨値 | 説明 |
|---|---|---|---|---|
| **撮影 FPS** | `fps` | **10 fps** | `5`, `10`, `15` fps | 録画フレームレート。PoC結果により 10fps（9.6%/h）を推奨。 |
| **GPS取得間隔** | `gpsIntervalSeconds` | **30 秒** | `5`, `10`, `30`, `60` 秒 | GPS位置・速度情報の取得・GPX書き出し間隔。 |
| **リングバッファ時間** | `ringBufferMinutes` | **5 分** | `3`, `5`, `10` 分 | インメモリ/セグメント循環で保持する直前録画時間。 |
| **録画解像度** | `resolution` | **720p (HD)** | `480p`, `720p`, `1080p` | 映像解像度 (`VideoResolution.HD_720P`) |

### 2.1 パラメータ設定クラス設計 (`AppConfig.kt`)
```kotlin
package jp.hisanet.charisafe.domain.model

enum class VideoResolution(val width: Int, val height: Int, val label: String) {
    SD_480P(640, 480, "480p (SD)"),
    HD_720P(1280, 720, "720p (HD)"),
    FHD_1080P(1920, 1080, "1080p (FHD)")
}

data class AppConfig(
    val fps: Int = 10,
    val gpsIntervalSeconds: Long = 30L,
    val ringBufferMinutes: Int = 5,
    val resolution: VideoResolution = VideoResolution.HD_720P
)
```

---

## 3. レイヤー・アーキテクチャ設計 (MVVM + Clean / Repository Pattern)

長期保守性と将来機能追加 (Phase 2 〜 Phase 5) を容易にするため、単一責任原則に沿った 3 レイヤー構造を採用する。

```
 ┌────────────────────────────────────────────────────────┐
 │                      UI Layer                          │
 │  [MainActivity] ───> [MainScreen (Compose)]             │
 │                          │                             │
 │                          ▼                             │
 │                  [MainViewModel]                       │
 └──────────────────────────┬─────────────────────────────┘
                            │ (StateFlow / Event)
                            ▼
 ┌────────────────────────────────────────────────────────┐
 │                    Domain Layer                        │
 │  [RecordingUseCase] [EventExportUseCase] [GpxUseCase] │
 │  [GetAppConfigUseCase] [UpdateAppConfigUseCase]       │
 └──────────────────────────┬─────────────────────────────┘
                            │ (Repository Interface)
                            ▼
 ┌────────────────────────────────────────────────────────┐
 │                     Data Layer                         │
 │ ┌──────────────────┐ ┌────────────────┐ ┌────────────┐ │
 │ │ConfigRepository  │ │RecordingRepo   │ │GpxRepo     │ │
 │ └────────┬─────────┘ └───────┬────────┘ └─────┬──────┘ │
 │          │                   │                │        │
 │          ▼                   ▼                ▼        │
 │  [SharedPreferences]  [CameraX Pipeline] [Location]    │
 └────────────────────────────────────────────────────────┘
```

---

## 4. リングバッファ & イベント保存機構

- **リングバッファ構造**: `AppConfig.ringBufferMinutes` (例: 5分) に基づき、1分単位セグメント動画を動的保持・循環破棄。
- **イベント保存時**: イベント発生時に「指定された直近 N 分間 (例: 5分)」＋「発生後 1 分間」の動画を1本の MP4 ファイルとして切り出し出力。

---

## 5. ディレクトリ構成・保存先仕様

- **動画保存先**: `Android/data/jp.hisanet.charisafe/files/recordings/`
- **GPSログ保存先**: `Android/data/jp.hisanet.charisafe/files/gpx/`
- **ログ保存先**: `Android/data/jp.hisanet.charisafe/files/logs/`
