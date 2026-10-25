# DEBUG_LOGGER_ARCHITECTURE.md
# デバッグログ永続化機能 アーキテクチャ設計書

## 1. 概要
ChariSafeの Phase3 開発期間における接近検知および音量検知のチューニング効率化のため、Logcatだけでなく CSVファイルとしてデバッグデータをアプリ専用ストレージに永続化する機能。

## 2. アーキテクチャ構成

```
[RearApproachDetector] ──(Metrics)──> [ApproachDebugLogger] ──┐
                                                            │
[AudioImpactDetector]  ──(Metrics)──> [AudioDebugLogger]   ├─> [CsvDebugLogger] ──> Android/data/.../files/debug/*.csv
                                                            │
[RecordingService]     ──(EventId)─┘                        │
                                                            │
[MainViewModel/UI]     ──(Share/Delete)─────────────────────┴─> [DebugLogShareManager / CleanupManager]
```

## 3. 保存仕様
- **ストレージパス**: `Android/data/jp.hisanet.charisafe/files/debug/`
- **ファイル命名規則**:
  - 接近検知: `approach_debug_YYYYMMDD.csv`
  - 音量検知: `audio_debug_YYYYMMDD.csv`
- **エンコーディング**: UTF-8 (ヘッダー付き、追記モード)
- **自動削除**: 起動時に `DebugLogCleanupManager` が 31日以上前のファイルを自動クリーンアップ。

## 4. データサイズ試算
1. **1時間走行時のログサイズ**:
   - 接近検知: 約2.0 MB / 時間
   - 音量検知: 約2.3 MB / 時間
   - 合計: 約4.3 MB / 時間
2. **1日走行時のログサイズ** (推定平均 3時間稼働):
   - 合計: 約13 MB / 日
3. **30日保存時の最大容量**:
   - 合計: 約390 MB (最大でも500MB以内に収まり、ストレージを圧迫しない設計)
