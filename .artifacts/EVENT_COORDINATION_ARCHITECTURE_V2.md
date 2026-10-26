# EVENT_COORDINATION_ARCHITECTURE_V2.md
# Phase 3 イベント管理構造見直し 改訂版統合設計仕様書 (リングバッファセグメント保護方式)

本書は、イベント発生前後の動画確実保持・CPU負荷削減・コード単純化・直感的UI統合を実現する**「リングバッファセグメント保護方式」**を適用した、Phase 3 イベント管理構造の統合設計仕様書である。

---

## 1. 全体アーキテクチャ概要 (セグメント保護 & イベント調停ハブ方式)

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
                              (重複保護抑止 ＆ 関連イベント紐付け調停)
                                                      │
                                                      ▼
                                         [ RingBufferManager ]
                          (カメラ録画は停止せず、対象セグメントを即座に保護)
                                                      │
                                                      ▼
                                           [ IncidentLogStore ]
                           (保護セグメント群 ＆ 関連イベントID群を永続化)
```

---

## 2. 新方式（リングバッファセグメント保護）のコア仕様

### 2.1 録画ノンストップ＆動画取り逃しゼロ保証
- **従来（MP4結合方式）の課題**: イベント発生時に進行中の録画を `.stop()` して再開するため、約500ms〜2秒間の録画空白（フレーム欠落）が発生していた。
- **新方式の仕様**:
  - `RecordingService` でのカメラ録画は**一切停止させず継続**する。
  - イベント発生時刻をカバーするリングバッファセグメント（現在録画中のセグメントおよび直前のセグメント）のファイルパスを保護対象（削除除外）としてマーク／保護ディレクトリ（`recordings/`）にコピー移動する。
  - 動画取り逃し（ギャップ時間）は**完全に 0ms** となる。

### 2.2 EventCoordinator による自動紐付け ＆ 重複保護抑止
- **連続多発イベント対応**:
  - 連鎖イベント（例: 「接近 ➔ 減速 ➔ 衝撃 ➔ 音量」）が発生した際、`EventCoordinator` が同一セグメントに対する重複保護処理を抑止しつつ、関連するすべての `EventDetectionLog` の ID を `relatedDetectionIds` に記録する。
  - 最高危険度（`highestSeverityType`: 衝撃 > 減速 > 音量 > 接近）を自動判定・保持する。

### 2.3 状態管理 (`IncidentStatus`)
- `enum class IncidentStatus { PENDING, SUCCESS, FAILED }`
- セグメント保護開始時に `PENDING` で `IncidentLog` を作成。ファイル保護およびメディアスキャン完了時に `SUCCESS`、ファイルアクセス失敗等で `FAILED` へ更新する。

### 2.4 EventDetectionLog の感度記録スキーマ (補完型構造)
- `sensitivityValue: Float` (生数値)
- `sensitivityLabel: String` (表示用フォーマット文字列: `"感度: 70%"`, `"1.5秒"`, `"3.0G"`, `"振幅: 5000"`)
- `sensitivitySnapshot: EventSensitivity` (検知時点の全感度設定スナップショット)

---

## 3. データモデル最終定義

```kotlin
// 全検知履歴データモデル (イベント種別: 減速, 接近, 衝撃, 音量)
data class EventDetectionLog(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val eventType: EventType, // DECELERATION("減速"), APPROACH("接近"), COLLISION("衝撃"), SOUND("音量")
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
    val relatedDetectionIds: List<String> = emptyList(), // 同一セグメント期間中に起きた全 EventDetectionLog ID
    val highestSeverityType: EventType = triggerEventType, // 最重要（最高危険度）イベントタイプ
    val latitude: Double,
    val longitude: Double,
    var coveredSegmentFiles: List<String> = emptyList(), // カバーする保護動画セグメントファイルパス一覧
    var status: IncidentStatus = IncidentStatus.PENDING,
    var failureReason: String? = null
)
```

---

## 4. 統合タイムライン UI 仕様 (`MainScreen.kt`)

ユーザーは「走行ログ タイムライン」画面において、保護された録画セグメントと発生イベントが一体化されたネスト時系列で直感的に確認・再生できる。

```text
┌────────────────────────────────────────────────────────────────────────┐
│ 🎥 08:10:00 録画セグメント #1 [▶ 再生] [📤 共有] [🔒 保護済]          │
│ │   ├─ 08:10:10 ⚠ 減速 (減速: 1.5秒 / 22km/h)                        │
│ │   └─ 08:10:11 🔊 音量 (振幅: 2,400)                                │
│ │                                                                      │
│ 🎥 08:13:00 録画セグメント #2 [▶ 再生] [📤 共有] [🔒 保護済]          │
│ │   ├─ 08:15:10 🚗 接近 (感度: 70% / 18km/h)                        │
│ │   └─ 08:15:14 💥 衝撃 (衝撃: 3.8G) [最重要]                         │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 5. 修正対象クラス一覧

1. **`domain/model/EventLog.kt`**: `EventType` (`減速`, `接近`, `衝撃`, `音量`), `EventDetectionLog`, `IncidentLog` (`coveredSegmentFiles`), `IncidentStatus` の定義
2. **`data/local/ringbuffer/RingBufferManager.kt`**: セグメント保護（コピー／保護マーク／パージ除外）機能の追加
3. **`data/service/EventCoordinator.kt`**: 重複保護抑止および関連イベント紐付けの調停
4. **`data/local/EventDetectionLogStore.kt`**: 全検知ログの永続化
5. **`data/local/IncidentLogStore.kt`**: 保護インシデントの永続化
6. **`data/service/RecordingService.kt`**: ノンストップ録画 ＆ セグメント保護呼び出し
7. **`data/camera/RearApproachDetector.kt`**: クールダウン撤廃、即時通知化
8. **`data/sensor/CollisionDropDetector.kt`**: クールダウン撤廃、即時通知化
9. **`data/sensor/AudioImpactDetector.kt`**: クールダウン撤廃、即時通知化
10. **`data/location/SpeedDecelerationDetector.kt`**: 即時通知化
11. **`ui/main/MainViewModel.kt`**: 2系統ログの保持、統合タイムラインデータ生成
12. **`ui/main/MainScreen.kt`**: セグメントとイベントが統合されたタイムライン UI 描画
