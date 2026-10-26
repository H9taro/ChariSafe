# ChariSafe アーキテクチャ仕様書 (Phase 3 統合・ナビゲーション・統一順序版)

本書は、自転車用セーフティレコーダー「ChariSafe」のシステム全体アーキテクチャ、コンポーネント設計、データフロー、およびストレージ構造を定義するものである。

---

## 1. システム全体設計 (MVVM + Clean Architecture + Navigation)

ChariSafe は、長期のバックグラウンド稼働における安定性と省電力性を最優先に設計されており、視認性・操作性を極限まで高めた**マルチ画面ナビゲーション構造**を採用している。

```
[ UI Layer ]
   ├── ModalNavigationDrawer (ハンバーガーメニュー ☰)
   ├── MainScreen (🏠 常時表示: 速度, スライダー4種+件数, 大アイコン録画ボタン)
   ├── EventHistoryScreen (📁 イベント履歴: 日付別グルーピング時系列リスト, ディープリンク自動スクロール)
   ├── RideResultScreen (📈 ライド結果: 過去サマリー選択参照, 危険度指標, 速度グラフ)
   └── SettingsScreen (⚙️ 設定: カメラ選択, リング時間, CSVデバッグ等)
       ↕
[ Domain Layer ] (UseCases, Models: AppConfig, GpxPoint, EventLog, RideSummary, etc.)
       ↕
[ Data Layer ] (Repositories, Foreground Service, Sensors, CameraX Pipeline, RingBufferManager, Stores)
```

---

## 2. コアモジュール構成とイベント種類の順序統一

### 2.1 イベント種類の表示順序の完全統一
アプリ内のすべての画面（常時表示画面のスライダー、構造化件数サマリー、ライド結果サマリー、設定画面のCSVチェックボックス等）において、イベント種類の表示順序は必ず以下の規約（**接近 ➔ 減速 ➔ 衝撃 ➔ 音量**）で**完全統一**する。

1. **`APPROACH`** (接近)
2. **`DECELERATION`** (減速)
3. **`COLLISION`** (衝撃)
4. **`SOUND`** (音量)

### 2.2 Domain 層
- **Models**:
  - `AppConfig`: 撮影設定（FPS、GPS間隔、リングバッファ時間、解像度、音声有効、アクティブカメラ選択）。
  - `GpxPoint`: 位置情報・速度・タイムスタンプモデル。
  - `SystemStatus` / `RecordingState`: サービスの状態（IDLE, RECORDING, EXPORTING, ERROR）。
  - `EventDetectionLog`: 全センサー検知イベント（接近、減速、衝撃、音量）。
  - `IncidentLog`: 保護された動画セグメント群および関連イベント群を紐付けるインシデントデータ。
  - `RideSummary`: 走行時間、停止時間、距離、平均/最高速度、危険度指標（件/km, 件/h）。
- **Repositories & Stores**:
  - `ConfigRepository`, `GpxRepository`, `EventLogRepository`, `RecordingRepository`, `RideSummaryStore` (過去走行サマリーJSON永続化)。

### 2.3 Data 層 (インフラ・サービス・検知)
- **Foreground Service (`RecordingService`)**:
  - `FOREGROUND_SERVICE_TYPE_CAMERA` 権限を利用した堅牢なバックグラウンド常時録画。
  - **OS標準通知連携**: セグメント記録完了時およびストレージ容量低下時（< 500MB）に通知を発行。通知タップで「イベント履歴画面」の日付グループ内該当ログへダイレクトジャンプ。
  - **接近限定警告音 & ミュート**: 警告音（ビープ音）の発報は「接近」イベントのみ。鳴動中（約800ms）は `AudioImpactDetector`（音量検知）の判定を一時ミュート。
- **リングバッファマネージャー (`RingBufferManager`)**:
  - 固定長セグメント（`seg_*.mp4`）のノンストップ循環生成と、イベント発生時の保護ディレクトリへの複製・退避。
- **インテリジェント検知・センサー群**:
  - `RearApproachDetector` (接近), `SpeedDecelerationDetector` (減速), `CollisionDropDetector` (衝撃), `AudioImpactDetector` (音量), `PocketStorageDetector` (ポケット収納)。

---

## 3. UI 画面構成および表示ルール

### 3.1 📁 イベント履歴画面（日付別グルーピング表示）
- 大量ログの視認性を最大化するため、**「日付（例: `2026-10-26`）」ごとのセクションヘッダーによるグルーピング表示**を採用。
- 各日付グループの配下に、記録セグメント動画（`🎥 記録`）と検知イベント（`🚗 接近`, `⚠ 減速`, `💥 衝撃`, `🔊 音量`）を時刻降順でフラットに配置。
- ディープリンクによる自動スクロール ＆ 2秒間カードハイライト表示。

### 3.2 🏠 常時表示画面（走行中リアルタイム監視特化）
- 超大文字速度メーター (64sp ボールド)
- スライダー4種（接近 ➔ 減速 ➔ 衝撃 ➔ 音量の順で固定配置、セッション累計件数付き）
- 形状グラフィック大録画ボタン（🔴 / ⏹️、テキストなし）。
