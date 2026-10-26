# EVENT_COORDINATION_MIGRATION_PLAN.md
# Phase 3 イベント管理構造見直し 移行計画書 (リングバッファセグメント保護方式)

## 1. 修正対象クラス一覧 ＆ 変更概要

| レイヤー | クラス名 | 役割 / 変更内容 | 影響度 |
| :--- | :--- | :--- | :--- |
| **Domain** | `EventLog.kt` | `EventDetectionLog`, `IncidentLog` (`coveredSegmentFiles`) の更新 | 高 |
| **Domain** | `RideSummary.kt` | `eventCounts` を `EventDetectionLog` ベースの集計に変更 | 中 |
| **Data** | `RingBufferManager.kt` | MP4結合抽出の代わりにセグメント保護（コピー/退避/削除除外）機能追加 | 高 |
| **Data** | `Mp4Concatenator.kt` | **削除** (動画結合処理不要化のため) | 高 |
| **Data** | `EventLogStore.kt` | `EventDetectionLogStore` と `IncidentLogStore` に分離 | 高 |
| **Data** | `EventLogRepositoryImpl.kt` | 新データモデルに対応する StateFlow とリロード処理に修正 | 高 |
| **Service** | `RecordingService.kt` | 録画停止/再開/結合パイプラインを廃止し、セグメント保護の即時呼び出し化 | 最高 |
| **Service** | `EventCoordinator.kt` | 重厚な排他ロックを撤廃し、重複保護抑止・関連ID紐付けの調停クラスへスリム化 | 中 |
| **Sensors** | `RearApproachDetector.kt` | 個別 `COOLDOWN_MS` 廃止、調停ハブへの即時通知に変更 | 中 |
| **Sensors** | `CollisionDropDetector.kt` | 個別 `COOLDOWN_MS` 廃止、調停ハブへの即時通知に変更 | 中 |
| **Sensors** | `AudioImpactDetector.kt` | 個別 `COOLDOWN_MS` 廃止、調停ハブへの即時通知に変更 | 中 |
| **Location**| `SpeedDecelerationDetector.kt`| 個別 `lastTriggerTimeMs` 制限撤廃、即時通知に変更 | 中 |
| **UI** | `MainViewModel.kt` | `eventDetectionLogs` と `incidentLogs` の保持・統合タイムライン処理 | 高 |
| **UI** | `MainScreen.kt` | 録画セグメントとイベントが統合されたタイムライン UI の描画 | 高 |

---

## 2. 段階的移行手順 (Migration Steps)

### Step 1: ドメインモデル & データストアの整備
1. `domain/model/EventLog.kt` の `IncidentLog` に `coveredSegmentFiles` を反映。
2. `EventDetectionLogStore` および `IncidentLogStore` のスキーマ更新。

### Step 2: Mp4Concatenator 削除 & RingBufferManager セグメント保護拡張
1. `Mp4Concatenator.kt` を廃止・削除。
2. `RingBufferManager.kt` に、イベント発生時刻に応じたセグメント保護（`protectSegmentsForEvent(timestampMs)`）を実装。

### Step 3: RecordingService のノンストップ化
1. `RecordingService` の `handleDetectedEvent()` から `activeRecording.stop()` および `Mp4Concatenator.concatenate()` 呼び出しを完全除去。
2. イベント通知時に録画を継続したまま、即座にセグメント保護を呼び出すノンストップ録画処理に変更。

### Step 4: 各検出器のクールダウン廃止 ＆ 即時通知化
1. 各検出器（`RearApproachDetector` 等）から個別クールダウンコードを除去し、即時通知化。

### Step 5: UI & ViewModel のタイムライン統合
1. `MainViewModel` で保護セグメントとイベントログの統合タイムラインを生成。
2. `MainScreen` で統合タイムライン UI（セグメントカード内にイベントが並ぶ直感表示）を描画。

---

## 3. 評価と期待される成果
- **動画取り逃しギャップ 0ms 化**: イベント発生時のカメラバインド再初期化に伴う画面停止・空白時間がゼロになる。
- **CPU負荷・発熱の激減**: MP4解体・結合処理が完全になくなるため、イベント発生時のバッテリー消費と発熱が大幅低減。
- **画面の直感性改善**: 「録画セグメント」と「イベント」が1つのタイムラインに整列し、ユーザー体験が飛躍的に向上。
