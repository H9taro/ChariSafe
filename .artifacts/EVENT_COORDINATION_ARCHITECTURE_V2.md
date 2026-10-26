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
- `RecordingService` でのカメラ録画は**一切停止させず継続**する。
- イベント発生時刻をカバーするリングバッファセグメント（現在録画中のセグメントおよび直前のセグメント）のファイルパスを保護対象（削除除外）としてマーク／保護ディレクトリ（`recordings/`）にコピー移動する。
- 動画取り逃し（ギャップ時間）は**完全に 0ms** となる。

### 2.2 UI 設計における重要方針：フラットな時系列リスト表示 (階層構造の廃止)
- **従来案（ネスト・階層構造）の廃止**: 動画記録とイベント検知を親子関係（ネスト）で表示するのではなく、**時系列でフラットな1つのリスト**として一元的に表示する。
- **降順ソート（最新が一番上）**: 常に最新のイベントや記録セグメントがリストの最上部に表示される（`sortedByDescending { it.timestampMs }`）。
- **「最後のイベント検知」セクションの廃止**: 最新のイベントがリストの一番上に自然に表示されるため、常時表示エリアにあった「最後のイベント検知」専用セクションは廃止する。

### 2.3 状態管理 (`IncidentStatus`)
- `enum class IncidentStatus { PENDING, SUCCESS, FAILED }`
- セグメント保護開始時に `PENDING` で `IncidentLog` を作成。ファイル保護およびメディアスキャン完了時に `SUCCESS`、ファイルアクセス失敗等で `FAILED` へ更新する。

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
    val triggerEventType: EventType,                     
    val triggerDetectionLogId: String,
    val relatedDetectionIds: List<String> = emptyList(), 
    val highestSeverityType: EventType = triggerEventType, 
    val latitude: Double,
    val longitude: Double,
    var coveredSegmentFiles: List<String> = emptyList(), // カバーする保護動画セグメントファイルパス一覧
    var status: IncidentStatus = IncidentStatus.PENDING,
    var failureReason: String? = null
)
```

---

## 4. フラット時系列タイムライン UI 仕様 (`MainScreen.kt`)

ユーザーは「走行ログ タイムライン」画面において、保護された録画セグメント（ファイル記録）と各センサー検知イベントが**フラットな時系列（最新が最上部）**で並んだリストを確認できる。

### フラット時系列イメージ（降順）
- **2026-10-26 08:16:00** 🎥 記録セグメント保護完了 [再生] [共有] [🔒保護済]
- **2026-10-26 08:15:14** 🔊 音量 (振幅: 2,400)
- **2026-10-26 08:15:10** ⚠ 減速 (1.5秒 / 22km/h)
- **2026-10-26 08:13:00** 🎥 記録セグメント保護完了 [再生] [共有] [🔒保護済]
- **2026-10-26 08:10:11** 🔊 音量 (振幅: 2,400)
- **2026-10-26 08:10:10** ⚠ 減速 (1.5秒 / 22km/h)
- **2026-10-26 08:10:00** 🎥 記録セグメント保護完了 [再生] [共有] [🔒保護済]
