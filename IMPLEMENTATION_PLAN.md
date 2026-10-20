# ChariSafe 本実装計画書 (IMPLEMENTATION_PLAN)

本ドキュメントは、ChariSafe の MVP（Minimum Viable Product）実装および今後の拡張フェーズにおけるロードマップ、実装手順、テスト戦略を定義するものである。

## 1. 全体フェーズ計画

```
[Phase 1: MVP (本フェーズ)] ───> 毎日の通勤・サイクリングで実際に使えるセーフティレコーダー
  ├── パラメータ設定クラス AppConfig (FPS, GPS間隔, リング時間, 解像度)
  ├── 設定保持 (ConfigRepository / SharedPreferences)
  ├── 設定に応じた CameraX (解像度/FPS) & リングバッファ動的制御
  ├── 5分リングバッファ (1分セグメント循環)
  ├── 手動イベント保存 (前5分 + 後1分 MP4出力)
  ├── 30秒間隔 (設定可変) GPS GPXファイル保存 & 速度表示
  └── 画面OFF / バックグラウンド安定動作

[Phase 2: 事故・衝撃検知] ─────> 自動イベント保存の強化
[Phase 3: 夜間セーフティ機能] ─> 事故予防・アクティブセーフティ
[Phase 4: 超省電力化] ─────────> 長時間・日またぎライド対応
[Phase 5: 2カメラ連携] ────────> 完全後方＋前方同時点検
```

---

## 2. Phase 1 (MVP) 実装ステップ

### Step 1: プロジェクト基盤・設定クラス (`AppConfig`) & ドメインモデルの作成
- パッケージ構造 (`data`, `domain`, `ui`, `util`) の構築。
- 設定用モデル定義 (`AppConfig`, `VideoResolution`)。
  - FPS: `5`, `10`, `15`
  - GPS間隔: `5`, `10`, `30`, `60` 秒
  - リングバッファ時間: `3`, `5`, `10` 分
  - 解像度: `480p`, `720p`, `1080p`
- `ConfigRepository` (SharedPreferences / DataStore) による設定永続化。

### Step 2: GPS & GPX トラッカー実装 (設定連携)
- `AppConfig.gpsIntervalSeconds` の変更に対応した `LocationTracker` の作成。
- `GpxFileWriter` の作成 (GPX 1.1 形式 XML 追記生成)。

### Step 3: CameraX リングバッファ & イベント保存ロジック実装 (設定連携)
- `AppConfig.resolution` および `AppConfig.fps` に応じた CameraX パイプライン制御。
- `AppConfig.ringBufferMinutes` に応じた `RingBufferManager` の循環制御。
- `Mp4Concatenator` による動画結合処理。
- `SaveManualEventUseCase` の構築。

### Step 4: UI & ViewModel 統合・実機検証
- `MainViewModel` での `AppConfig` 読み込みおよび Service 状態提供。
- `MainScreen` (Compose) での各種ステータス表示および操作。
