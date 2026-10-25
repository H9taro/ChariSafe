# ChariSafe アーキテクチャ仕様書 (Phase2 最終完了版)

本書は、自転車用セーフティレコーダー「ChariSafe」の MVP および Phase2 で実装されたシステム全体アーキテクチャ、コンポーネント設計、データフロー、およびストレージ構造を定義するものである。

---

## 1. システム全体設計 (MVVM + Clean Architecture)

ChariSafe は、長期のバックグラウンド稼働における安定性と省電力性を最優先に設計されており、明確な関心の分離（Separation of Concerns）に基づいたレイヤー構造を採用している。

```
[ UI Layer ] (Jetpack Compose / MainScreen & MainViewModel)
       ↕
[ Domain Layer ] (UseCases: SaveManualEventUseCase, Models: AppConfig, GpxPoint, etc.)
       ↕
[ Data Layer ] (Repositories, Foreground Service, Sensors, CameraX Pipeline)
```

---

## 2. コアモジュール構成

### 2.1 Domain 層
- **Models**:
  - `AppConfig`: 撮影設定（FPS、GPS間隔、リングバッファ時間、解像度、音声有効（デフォルト有効）、アクティブカメラ選択）。
  - `GpxPoint`: 位置情報・速度・タイムスタンプモデル。
  - `SystemStatus` / `RecordingState`: サービスの状態（IDLE, RECORDING, EXPORTING, ERROR）。
- **Repositories (インターフェース)**:
  - `ConfigRepository`: 設定データの永続化・購読。
  - `GpxRepository`: 位置情報・速度トラッキングおよびGPXファイル書き込み。
  - `RecordingRepository`: 録画サービスのバインド・制御および手動/自動イベント保存。
- **Use Cases**:
  - `SaveManualEventUseCase`: 手動または自動イベント発生時のリングバッファ抽出・保存の調整。

### 2.2 Data 層 (インフラ・サービス・検知)
- **Foreground Service (`RecordingService`)**:
  - `FOREGROUND_SERVICE_TYPE_CAMERA` 権限を利用した堅牢なバックグラウンド常時録画。
  - `PARTIAL_WAKE_LOCK` によるスリープ時の中断防止。
  - CameraX `VideoCapture` (H.265 / 720p / 10fps / マイク音声同時記録) と `ImageAnalysis` (接近検知) のライフサイクル統合管理。
- **リングバッファマネージャー (`RingBufferManager`)**:
  - 1分ごとのセグメントファイル（`seg_*.mp4`）をローテーション生成。
  - ユーザー設定の `ringBufferMinutes`（例: 3分、5分）を超える古いセグメントをディスクから自動パージ（削除）。
- **動画結合コンテナ (`Mp4Concatenator`)**:
  - 複数セグメントをタイムスタンプ（PTS）の破綻なくマルチセグメント連結する高信頼コンテナ。
- **Phase2 インテリジェント検知・センサー群**:
  - `SpeedDecelerationDetector`: GPS速度を利用した急減速（15km/h以上から3秒以内に5km/h未満へ急低下）検知。
  - `RearApproachDetector`: CameraX `ImageAnalysis` を利用した夜間・暗所におけるヘッドライト急拡大（後方接近）検知。
  - `CollisionDropDetector`: 加速度センサーによる衝突・落下（$\ge 3.0G$）検知。
  - `AudioImpactDetector`: マイクロフォン音声のリアルタイム解析による大音量（衝突音・クラクション）検知。
  - `PocketStorageDetector`: 近接センサーによるポケット・バッグ収納検知（省電力FPSスロットリング）。

---

## 3. イベント保存の非同期高速パイプライン (作業ディレクトリ退避方式)

イベント発生（手動ボタン、または各自動検知トリガー）時に、ユーザーを待たせず、かつ動画ファイル破損を 100% 防止するプロ仕様のパイプライン：

1. **警告音鳴動**: `ToneGenerator` によるアラームビープ音の即時再生。
2. **安全停止**: 現在録画中のリングバッファセグメントを停止・クローズ。
3. **作業ディレクトリ退避**: 対象セグメントファイル群をアプリ内キャッシュ（`cacheDir/working_segments`）に高速退避。
4. **即時録画再開**: すかさず新ファイルでリングバッファ常時録画を自動再開（ユーザーの待ち時間・空白をゼロに）。
5. **非同期結合**: バックグラウンドスレッドで作業ディレクトリのファイルを結合。
6. **公開保存 ＆ メディアスキャン**: 完了した動画（`event_*.mp4`）を公開ストレージに保存し、`MediaScannerConnection` により OS に即時登録（Google Files 等での一発再生・共有を保証）。

---

## 4. ストレージ構造 (パブリック共有ストレージ)

Android 11+ スコープストレージに対応し、PC接続やファイルマネージャーから一目でアクセスできる公開ディレクトリを採用：

- **動画保存先 (イベント動画 & セグメント)**:
  - `/storage/emulated/0/Movies/ChariSafe/`
  - `/storage/emulated/0/Movies/ChariSafe/ring_buffer/`
- **GPXルートログ保存先**:
  - `/storage/emulated/0/Documents/ChariSafe/gpx/`
- **バッテリー・温度 CSVログ保存先**:
  - `/storage/emulated/0/Documents/ChariSafe/logs/`

---

## 5. デバッグログシステム (Debug Logging System)

Phase 3 チューニングおよび実走検証のための高精度デバッグ機能。センサー/検知器ごとの内部メトリクスを CSV 形式でアプリ専用ストレージに永続化し、開発者向け設定から ZIP 共有や自動削除が可能。

### 5.1 保存パスとファイル命名規則
- **保存ディレクトリ**:
  - `Android/data/jp.hisanet.charisafe/files/debug/`
- **CSVファイル群 (日付別ローテーション / UTF-8)**:
  - 接近検知: `approach_debug_YYYYMMDD.csv`
  - 音量検知: `audio_debug_YYYYMMDD.csv`
  - 減速検知: `deceleration_debug_YYYYMMDD.csv`
  - 衝撃検知: `collision_debug_YYYYMMDD.csv`

### 5.2 自動クリーンアップ & ストレージ対策
- **保持期間**: 最新 30 日分のみ保持。
- **自動削除**: アプリ起動時に `DebugLogCleanupManager` が 31日以上前の CSV ファイルを自動削除し、ストレージ肥大化を防止。

### 5.3 開発者向け操作手順 (UI)
1. アプリの **⚙️ 録画設定** 内にある **【開発者向けデバッグ】** セクションを開く。
2. 取得したいセンサー/検知器のチェックボックス（接近検知、音量検知、減速検知、衝撃検知）を有効にする。
3. **📤 ログ共有 (ZIP)** ボタンをタップすると、`debug/` フォルダ内のすべての CSV が ZIP 圧縮され、Android 共有シート（メール、Googleドライブ、Slack等）が起動。
4. **🗑️ ログ削除** ボタンをタップして確認ダイアログで承認すると、デバッグログを全削除可能。
