# ChariSafe アーキテクチャ仕様書 (Phase 3 統合版)

本書は、自転車用セーフティレコーダー「ChariSafe」のシステム全体アーキテクチャ、コンポーネント設計、データフロー、およびストレージ構造を定義するものである。

---

## 1. システム全体設計 (MVVM + Clean Architecture)

ChariSafe は、長期のバックグラウンド稼働における安定性と省電力性を最優先に設計されており、明確な関心の分離（Separation of Concerns）に基づいたレイヤー構造を採用している。

```
[ UI Layer ] (Jetpack Compose / MainScreen & MainViewModel)
       ↕
[ Domain Layer ] (UseCases: SaveManualEventUseCase, Models: AppConfig, GpxPoint, EventLog, etc.)
       ↕
[ Data Layer ] (Repositories, Foreground Service, Sensors, CameraX Pipeline, RingBufferManager)
```

---

## 2. コアモジュール構成

### 2.1 Domain 層
- **Models**:
  - `AppConfig`: 撮影設定（FPS、GPS間隔、リングバッファ時間、解像度、音声有効、アクティブカメラ選択）。
  - `GpxPoint`: 位置情報・速度・タイムスタンプモデル。
  - `SystemStatus` / `RecordingState`: サービスの状態（IDLE, RECORDING, EXPORTING, ERROR）。
  - `EventDetectionLog`: 全センサー検知イベントの100%保存ログ（タイムスタンプ、速度、位置、感度値/ラベル）。イベント種別は「減速」「接近」「衝撃」「音量」の4種類。
  - `IncidentLog`: 保護された動画セグメント群および関連イベント群を紐付けるインシデントデータ。
- **Repositories (インターフェース)**:
  - `ConfigRepository`: 設定データの永続化・購読。
  - `GpxRepository`: 位置情報・速度トラッキングおよびGPXファイル書き込み。
  - `EventLogRepository`: 全検知ログ (`EventDetectionLog`) および保護動画インシデント (`IncidentLog`) の永続化・購読。
  - `RecordingRepository`: 録画サービスのバインド・制御および手動/自動イベント保存。
- **Use Cases**:
  - `SaveManualEventUseCase`: 手動または自動イベント発生時のリングバッファセグメント保護の調整。

### 2.2 Data 層 (インフラ・サービス・検知)
- **Foreground Service (`RecordingService`)**:
  - `FOREGROUND_SERVICE_TYPE_CAMERA` 権限を利用した堅牢なバックグラウンド常時録画。
  - `PARTIAL_WAKE_LOCK` によるスリープ時の中断防止。
  - CameraX `VideoCapture` (H.265 / 720p / 10fps / マイク音声同時記録) と `ImageAnalysis` (接近検知) のライフサイクル統合管理。
- **リングバッファマネージャー (`RingBufferManager`)**:
  - 固定長（例: 1分〜3分）のセグメントファイル（`seg_*.mp4`）をノンストップで連続生成。
  - イベント発生時に、対象時間帯をカバーするセグメント（当セグメント＋直前セグメント）を削除保護フラグ設定／保護ディレクトリへ移動。
  - ユーザー設定の `ringBufferMinutes` を超える古い未保護セグメントを自動パージ（削除）。
- **イベントコーディネーター (`EventCoordinator`)**:
  - 各検知器（カメラ・加速度・音声・GPS）からのイベント通知をアトミックに調停。
  - 連続多発イベント発生時も同一セグメントの重複保護を抑止しつつ、関連イベントID群（`relatedDetectionIds`）をインシデントへ自動紐付け。
- **Phase2/Phase3 インテリジェント検知・センサー群**:
  - `SpeedDecelerationDetector`: GPS速度を利用した「減速」検知。
  - `RearApproachDetector`: CameraX `ImageAnalysis` による夜間・暗所ヘッドライト急拡大の「接近」検知。
  - `CollisionDropDetector`: 加速度センサーによる「衝撃」検知（$\ge 3.0G$）。
  - `AudioImpactDetector`: リアルタイム音声解析による大音量の「音量」検知。
  - `PocketStorageDetector`: 近接センサーによるポケット・バッグ収納検知（省電力FPSスロットリング）。

---

## 3. イベント保存の高速ノンストップパイプライン (リングバッファセグメント保護方式)

イベント発生（手動ボタン、または各自動検知トリガー）時に、カメラ録画を**一切停止させず（動画取り逃しギャップ 0ms）**、かつCPU負荷を最小限に抑えるプロ仕様のパイプライン：

1. **全検知ログ無条件保存**: 発生したすべてのイベントを `EventDetectionLogStore` に即座に永続化。
2. **警告音鳴動**: `ToneGenerator` によるアラームビープ音の即時再生。
3. **ノンストップ継続録画**: カメラの録画停止・再開処理を行わず、バックグラウンドでのセグメント録画を連続実行。
4. **セグメント保護 (コピー/マーク)**: イベント発生時刻をカバーするリングバッファセグメント（当セグメントおよび直前セグメント）をローテーション削除対象から除外（保護領域へ移動/コピー）。
5. **インシデント紐付け ＆ メディアスキャン**: 保護されたセグメント一覧と関連イベントID群を `IncidentLog` として保存し、`MediaScannerConnection` により OS メディアライブラリに登録。

---

## 4. ストレージ構造 (パブリック共有ストレージ)

Android 11+ スコープストレージに対応し、PC接続やファイルマネージャーから一目でアクセスできる公開ディレクトリを採用：

- **動画保存先 (保護されたイベント動画 & 一時リングバッファ)**:
  - 保護された動画: `/storage/emulated/0/Movies/ChariSafe/recordings/`
  - 一時リングバッファ: `/storage/emulated/0/Movies/ChariSafe/ring_buffer/`
- **GPXルートログ保存先**:
  - `/storage/emulated/0/Documents/ChariSafe/gpx/`
- **ログ・デバッグログ保存先**:
  - `/storage/emulated/0/Documents/ChariSafe/logs/`
  - アプリ専用デバッグCSV: `Android/data/jp.hisanet.charisafe/files/debug/`

---

## 5. デバッグログシステム (Debug Logging System)

Phase 3 チューニングおよび実走検証のための高精度デバッグ機能。センサー/検知器ごとの内部メトリクスを CSV 形式でアプリ専用ストレージに永続化し、開発者向け設定から ZIP 共有や自動削除が可能。

### 5.1 保存パスとファイル命名規則
- **保存ディレクトリ**: `Android/data/jp.hisanet.charisafe/files/debug/`
- **CSVファイル群 (日付別ローテーション / UTF-8)**:
  - 接近検知: `approach_debug_YYYYMMDD.csv`
  - 音量検知: `audio_debug_YYYYMMDD.csv`
  - 減速検知: `deceleration_debug_YYYYMMDD.csv`
  - 衝撃検知: `collision_debug_YYYYMMDD.csv`

### 5.2 自動クリーンアップ & ストレージ対策
- **保持期間**: 最新 30 日分のみ保持。
- **自動削除**: アプリ起動時に `DebugLogCleanupManager` が 31日以上前の CSV ファイルを自動削除し、ストレージ肥大化を防止。
