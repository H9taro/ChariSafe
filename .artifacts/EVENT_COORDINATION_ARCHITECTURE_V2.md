# EVENT_COORDINATION_ARCHITECTURE_V2.md
# Phase 3 イベント管理構造見直し 改訂版統合設計仕様書 (V2)

本書は、追加レビュー検討事項（ロック異常系対応、関連イベント自動紐付け、保存失敗状態管理、感度スキーマ改善）を反映した Phase 3 イベント管理構造の改訂版統合設計仕様書である。

---

## 1. 全体アーキテクチャ概要 (Event Coordinator 方式)

```
[ RearApproachDetector  ] ──┐
[ SpeedDecelDetector   ] ──┼─(1. 全検知を即座に通知)
[ CollisionDropDetector ] ──┼─────────────────────────┐
[ AudioImpactDetector   ] ──┘                         │
                                                      ▼
                                       [ EventDetectionLogStore ]
                                       (全検知ログを100%無条件保存)
                                                      │
                                                      ▼
                                         [ EventCoordinator ]
                                  (15秒タイムアウトウォッチドッグ付き)
                                                      │
                                         [ EventSavingLock ]
                                 ┌────────────────────┴────────────────────┐
                           (Lock = False)                             (Lock = True)
                                 │                                         │
                   [ 1. IncidentLog (PENDING) 生成 ]              [ 動画保存スキップ ]
                   [ 2. 関連イベント自動紐付けバッファ開始 ]          (検知ログのみ保存し)
                   [ 3. 警告音鳴動＆非同期結合 ]                     (現行Incidentの関連ID)
                   [ 4. Lock中に起きた全EventIDを自動追記 ]           (へ自動追加)
                   [ 5. IncidentLog (SUCCESS / FAILED) 更新 ]              │
                   [ 6. try-finally で Lock 強制解除 ] ──────────────────────┘
```

---

## 2. 追加検討事項の確定設計仕様

### 2.1 EventSavingLock 異常時解除保証 (専用 `EventCoordinator` 導入)
- **課題**: コルーチン例外や MP4 結合中のハングアップによるロック凍結を100%防止する。
- **仕様**: 専用クラス `EventCoordinator` 内で `executeWithLock` を管理し、`try-finally` による確実なロック解除に加えて、**15秒のタイムアウトウォッチドッグ (`withTimeoutOrNull`)** を併設する。

### 2.2 IncidentLog への関連イベント自動紐付け (Lockウィンドウ方式)
- **課題**: 「接近 ➔ 衝突 ➔ 大音量」等の連鎖イベント発生時、1本の保存動画に対して動画保存開始から Lock 解除までの全イベントを紐付ける。
- **自動紐付け仕様**:
  - `EventCoordinator` の Lock 保持期間中（動画保存開始〜完了）に発生した全 `EventDetectionLog` の ID を `relatedDetectionIds` に自動的に収集・蓄積する。
  - 同時に、発生イベントの危険度（衝突 > 急減速 > 大音量 > 接近）に応じて `highestSeverityType` を自動的に最高ランクへ更新する。
- **1本インシデント時系列 UI 仕様**:
  - UIの「保存動画一覧」カード展開時、1本の動画（`IncidentLog`）に紐づくすべての `relatedDetectionIds` を時系列ソートしてネスト表示する（例: `15:01:10 🚗 接近` ➔ `15:01:12 💥 衝突` ➔ `15:01:13 🔊 大音量`）。

### 2.3 動画保存失敗の記録 (`IncidentStatus` 状態管理)
- **課題**: 動画保存処理中や失敗時の追跡性を確保する。
- **仕様**:
  - `enum class IncidentStatus { PENDING, SUCCESS, FAILED }`
  - 動画保存開始時に即座に `PENDING` で `IncidentLog` を作成。成功時に `SUCCESS` + 動画パス、失敗時に `FAILED` + エラー理由に更新。
  - **ライドサマリーへの影響**: 危険度指標は `EventDetectionLog` から集計するため、動画保存失敗に影響されず100%正確な集計を維持。

### 2.4 EventDetectionLog の感度記録スキーマ改善 (補完型構造)
- **課題**: イベントごとに物理単位（%, 秒, G, Amp）が異なる感度設定の可読性と分析性の両立。
- **仕様**:
  - `sensitivityValue: Float` (生数値)
  - `sensitivityLabel: String` (フォーマット済み表示用文字列: `"感度: 70%"`, `"1.5秒"`, `"3.0G"`, `"振幅: 5000"`)
  - `sensitivitySnapshot: EventSensitivity` (検知時点の全4種設定の完全スナップショット)

---

## 3. データモデル最終定義

```kotlin
// 全検知履歴データモデル
data class EventDetectionLog(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val eventType: EventType,
    val speedKmh: Float,
    val latitude: Double,
    val longitude: Double,
    val sensitivityValue: Float,
    val sensitivityLabel: String,
    val sensitivitySnapshot: EventSensitivity = EventSensitivity(),
    var incidentId: String? = null // 紐付けられた IncidentLog ID
)

// 保存動画・インシデントデータモデル
enum class IncidentStatus { PENDING, SUCCESS, FAILED }

data class IncidentLog(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val triggerEventType: EventType,                     // トリガーとなった最初のイベント
    val triggerDetectionLogId: String,
    val relatedDetectionIds: List<String> = emptyList(), // Lock保持期間中に起きた全 EventDetectionLog ID
    val highestSeverityType: EventType = triggerEventType, // 最重要（最高危険度）イベントタイプ
    val latitude: Double,
    val longitude: Double,
    var videoFilePath: String = "",
    var status: IncidentStatus = IncidentStatus.PENDING,
    var failureReason: String? = null
)
```

---

## 4. 修正対象クラス一覧

1. **`domain/model/EventLog.kt`**: `EventDetectionLog`, `IncidentLog`, `IncidentStatus` の定義
2. **`data/service/EventCoordinator.kt`** [新規]: タイムアウト付きロック管理・自動紐付けバッファ管理
3. **`data/local/EventDetectionLogStore.kt`** [新規]: 全検知ログの永続化
4. **`data/local/IncidentLogStore.kt`** [新規]: 保存動画インシデントの永続化
5. **`data/repository/EventLogRepositoryImpl.kt`**: 新2系統データストアへのリポジトリ層統合
6. **`data/service/RecordingService.kt`**: `EventCoordinator` によるイベント調停ハブ化
7. **`data/camera/RearApproachDetector.kt`**: クールダウン撤廃、即時通知化
8. **`data/sensor/CollisionDropDetector.kt`**: クールダウン撤廃、即時通知化
9. **`data/sensor/AudioImpactDetector.kt`**: クールダウン撤廃、即時通知化
10. **`data/location/SpeedDecelerationDetector.kt`**: 即時通知化
11. **`ui/main/MainViewModel.kt`**: 2系統ログの保持、サマリー集計ロジック更新
12. **`ui/main/MainScreen.kt`**: イベント履歴（全検知）表示と、保存動画一覧における1本インシデントネスト時系列UI表示

---

## 5. 実装優先順位 (Phase 計画)

- **Phase 1: データモデル ＆ ストア層基盤構築**
  - `EventDetectionLog`, `IncidentLog`, `EventCoordinator` の作成とテスト
- **Phase 2: RecordingService 調停ハブ化 ＆ 自動紐付けバッファ実装 ＆ クールダウン撤廃**
  - `EventCoordinator` 組み込み、ロック期間中イベントの自動収集、各検出器からのクールダウン削除と即時通知化
- **Phase 3: UI ＆ リポジトリ層統合**
  - `MainViewModel` と `MainScreen` の履歴・動画表示分離、保存動画カード内ネスト時系列タイムライン描画、ライドサマリー更新
