# EVENT_COORDINATION_ARCHITECTURE_V2.md
# Phase 3 イベント管理構造見直し 改訂版統合設計仕様書 (マルチ画面・接近警告音ミュート・OS通知連動対応版)

本書は、イベント発生前後の動画確実保持・CPU負荷削減・直感的ナビゲーションUI・OS通知ディープリンク連携を実現する**「リングバッファセグメント保護方式」**を適用した、Phase 3 イベント管理構造の統合設計仕様書である。

---

## 1. 全体アーキテクチャ概要 (セグメント保護 & 通知・調停ハブ方式)

```
[ RearApproachDetector  ] ──┐
[ SpeedDecelDetector   ] ──┼─(1. 全検知を即座に通知)
[ CollisionDropDetector ] ──┼─────────────────────────┐
[ AudioImpactDetector   ] ──┘ (接近警告音鳴動中はミュート)│
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
                                                      │
                                                      ▼
                                         [ NotificationManager ]
                            (記録完了・容量不足通知 ➔ タップで履歴画面へ即ジャンプ)
```

---

## 2. 新方式のコア仕様および改訂ポイント

### 2.1 録画ノンストップ＆動画取り逃しゼロ保証
- `RecordingService` でのカメラ録画は**一切停止させず継続**する。
- イベント発生時刻をカバーするリングバッファセグメント（現在録画中のセグメントおよび直前のセグメント）を保護領域 (`recordings/`) へコピー移動する（取り逃し 0ms）。

### 2.2 接近限定警告音 ＆ 音量検知一時ミュート連動
- **接近限定の警告音**: 警告音（ビープ音）を発報するのは **「接近」イベントのみ**（減速・衝撃・音量は無音で記録のみ）。
- **自作自演防止ミュート**: 接近警告音鳴動中（約800ms）は `AudioImpactDetector`（音量検知）の判定を一時的にスキップし、アプリ自身のビープ音による誤検知を100%防止。

### 2.3 OS標準通知 ＆ ディープリンク・ハイライト表示
- **通知発行**: `RecordingService` がセグメント保護完了時およびストレージ容量低下時（< 500MB）に通知を発行。
- **ディープリンク**: 通知タップでアプリ起動 ➔ 「イベント履歴画面」へ直接遷移 ➔ 対象ログカードへ自動スクロール (`animateScrollToItem`) ＆ 2秒間ハイライト表示。

### 2.4 UI 設計における重要方針：マルチ画面ナビゲーション ＆ 軽量化
- **ハンバーガーメニュー (☰)**: 常時表示、イベント履歴、ライド結果、設定の4画面に分離。
- **常時表示画面の徹底軽量化**: 録画時間タイマー・ボタンテキストを廃止。大文字速度、スライダー4種+セッション累計件数（`接近 70% 28件` 等）、大アイコン録画ボタン（🔴 / ⏹️）のみに特化。

### 2.5 過去ライドサマリーの永続化 (`RideSummaryStore`)
- `RideSummaryStore` (`ride_summaries.json`) を新設。過去の走行結果を一覧選択して当時の危険度指標や速度グラフを復元参照。

---

## 3. データモデル最終定義

```kotlin
// 全検知履歴データモデル
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
    var incidentId: String? = null
)

// 保護動画・インシデントデータモデル
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
    var coveredSegmentFiles: List<String> = emptyList(),
    var status: IncidentStatus = IncidentStatus.PENDING,
    var failureReason: String? = null
)
```

---

## 4. 修正対象クラス一覧

1. **`domain/model/EventLog.kt`**: `EventType`, `EventDetectionLog`, `IncidentLog`, `IncidentStatus` の定義
2. **`data/local/RideSummaryStore.kt` [新規]**: 過去走行サマリーの JSON 永続化
3. **`data/local/ringbuffer/RingBufferManager.kt`**: セグメント保護（コピー/退避/パージ除外）
4. **`data/service/EventCoordinator.kt`**: 重複保護抑止および接近警告音鳴動中の音量検知ミュート連動
5. **`data/service/RecordingService.kt`**: ノンストップ録画、接近限定警告音、OS通知（記録完了/容量不足）発行
6. **`data/sensor/AudioImpactDetector.kt`**: 接近警告音鳴動中の一時ミュートフラグ対応
7. **`ui/main/MainViewModel.kt`**: ドロワーナビゲーション状態、通知ディープリンクからの `targetEventId` 自動スクロール制御、セッション累計件数算出、過去サマリー読み込み
8. **`ui/main/MainScreen.kt`**: ドロワーナビゲーション、常時表示画面の超シンプル表示、イベント履歴画面（フォント拡大・ディープリンク自動スクロール＆ハイライト）、ライド結果画面（過去選択）、設定画面への分割
