# EVENT_COORDINATION_ARCHITECTURE.md
# Phase 3 イベント管理構造見直し アーキテクチャ設計レビュー仕様書

## 1. 現状分析
現在の ChariSafe は、`接近`・`急減速`・`衝撃`・`大音量` の4種類の検知イベントを備えている。
現在の実装では、各センサー/検出器が個別に `triggerAutoEventWithAlert()` を呼び出し、単一の `EventLog`（動画ファイルパスと検知イベントが1対1で合体したモデル）を生成・保存している。

---

## 2. 現行設計の問題点
1. **インシデント連続発生時の動画多重保存・CPU過負荷**:
   実際のヒヤリ・ハット/事故では「接近 ➔ 急減速 ➔ 衝突 ➔ 大音量」が数秒間に連続発生する。この時、各検知器が独立して動画保存・MP4結合を実行するため、同一インシデントに対して動画が重包して生成され、結合処理のCPU負荷が急増する。
2. **クールダウンによる後続車両の逃し**:
   従来は過剰保存を防ぐため各検知器に15秒〜60秒のクールダウン（`COOLDOWN_MS`）を設けていた。しかし、実際の道路では5秒おきに連続して後続車が追い越すため、クールダウン期間中に接近した2台目以降の車両が検知・記録されない。
3. **データモデルの混同**:
   `EventLog` が「イベント検知履歴」と「保存動画（ファイルパス）」の役割を兼ねているため、動画結合に失敗した場合に検知事実そのものが消滅していた。

---

## 3. EventSavingLock ＆ Event Coordinator 設計 (ハブ＆ロック方式)

### 3.1 評価: RecordingService をイベント調停ハブとする妥当性
`RecordingService` を**イベント調停ハブ (Event Coordinator Hub)** として利用する構成は**極めて妥当**である。理由：
- `RecordingService` は既に全センサー/検出器のライフサイクルおよびリングバッファ動画生成を統合管理している。
- 単一のサービスプロセス内で排他ロック（`EventSavingLock`）を管理することで、マルチスレッド（カメラ解析、音声解析、センサー解析）からのイベント要求を安全かつアトミックに調停可能。

### 3.2 構成図

```
[ RearApproachDetector  ] ──┐
[ SpeedDecelDetector   ] ──┼─(1. 検知イベント発生)
[ CollisionDropDetector ] ──┼───────────────────────┐
[ AudioImpactDetector   ] ──┘                       │
                                                    ▼
                                      [ EventDetectionLogStore ]
                                      (全検知ログを100%無条件保存)
                                                    │
                                                    ▼
                                      [ RecordingService ] (Event Coordinator Hub)
                                                    │
                                      [ EventSavingLock ] (AtomicBoolean)
                                      ┌─────────────┴─────────────┐
                                 (Lock = False)              (Lock = True)
                                      │                           │
                            [ 動画保存処理開始 ]           [ 動画保存スキップ ]
                            (1. Lock = True に変更)      (検知ログのみ保持)
                            (2. 警告音鳴動)
                            (3. MP4結合・ファイル生成)
                            (4. IncidentLog 保存)
                            (5. Lock = False に解除)
```

---

## 4. データモデル設計の見直し

データモデルを「全検知履歴」と「動画保存インシデント」に明確に分離する。

### 4.1 全検知履歴データモデル (`EventDetectionLog`)
すべての検知イベント（動画保存の有無に関わらず）を100%記録する。

```kotlin
data class EventDetectionLog(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val eventType: EventType,               // DECELERATION, APPROACH, COLLISION, SOUND
    val speedKmh: Float,
    val latitude: Double,
    val longitude: Double,
    val sensitivityValue: Float,            // 検知時の設定値 (秒数, %, G, 振幅)
    val incidentId: String? = null          // 動画保存が行われた場合、対応する IncidentLog の ID
)
```

### 4.2 保存動画データモデル (`IncidentLog`)
動画保存（MP4結合）が行われた単位のモデル。

```kotlin
data class IncidentLog(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val triggerEventType: EventType,        // 動画保存のトリガーとなった最初のイベント
    val triggerDetectionLogId: String,     // トリガーとなった EventDetectionLog の ID
    val latitude: Double,
    val longitude: Double,
    val videoFilePath: String,             // 生成された MP4 パス
    val durationSec: Int                   // 保存動画の長さ
)
```

---

## 5. クールダウン時間廃止の妥当性評価
- 各検出器（`RearApproachDetector`, `AudioImpactDetector` 等）の個別クールダウン時間は**完全廃止**する。
- **理由**: `EventSavingLock` が有効な間（動画結合中）は他イベントの動画保存が安全に抑止される。ロック解除直後（5秒後等）に別の車両が接近した場合、新しい `IncidentLog` として速やかに動画保存が実行されるため、連続追い越し環境でも証拠動画を撮り逃さない。

---

## 6. RecordingService 修正案 (ワークフロー)

```kotlin
class RecordingService : Service() {
    // 動画保存中フラグ（アトミックロック）
    private val isEventSavingLocked = AtomicBoolean(false)

    fun handleDetectedEvent(eventType: EventType, speedKmh: Float, lat: Double, lon: Double, sensitivityValue: Float) {
        // Step 1: イベント検知ログは無条件で即座に保存 (100% 保持)
        val detectionLog = EventDetectionLog(
            eventType = eventType,
            speedKmh = speedKmh,
            latitude = lat,
            longitude = lon,
            sensitivityValue = sensitivityValue
        )
        eventDetectionLogStore.save(detectionLog)

        // Step 2: 動画保存の排他ロックチェック
        if (isEventSavingLocked.compareAndSet(false, true)) {
            // ロック獲得成功 ➔ 最初のイベントとして警告音再生＆動画保存パイプライン実行
            triggerIncidentVideoSave(detectionLog)
        } else {
            // ロック中 ➔ 動画保存は抑止（スキップ）。検知ログは Step 1 で保存済み。
            Log.i(TAG, "🔒 [EventSavingLock] 動画保存中のため、動画生成をスキップします: ${eventType.displayName}")
        }
    }

    private fun triggerIncidentVideoSave(triggerLog: EventDetectionLog) {
        // 1. 警告音鳴動
        toneGenerator.startTone(ToneGenerator.TONE_DTMF_S, 800)

        // 2. 非同期でMP4結合＆IncidentLog生成
        exportEventAsync(triggerLog) { incidentLog ->
            if (incidentLog != null) {
                incidentLogStore.save(incidentLog)
                // detectionLog 側に incidentId を更新紐付け
                eventDetectionLogStore.updateIncidentId(triggerLog.id, incidentLog.id)
            }
            // 3. 動画保存完了後、ロック解除
            isEventSavingLocked.set(false)
        }
    }
}
```

---

## 7. UI / ViewModel 修正案

### 7.1 ライドサマリー (集計)
- 集計ロジック（件数、件/km、件/h）は `EventDetectionLog` を使用する。
- 例: 接近 20件、急減速 3件、衝突 1件、大音量 5件（総検知 29件）。保存動画 4件。

### 7.2 📁 イベント履歴 (Accordion 1)
- `EventDetectionLog` を全件リスト表示（タイムライン形式）。
- 動画が保存されているログ（`incidentId != null`）には「🎥 動画あり」バッジを表示。

### 7.3 🎥 保存動画一覧 (Accordion 2)
- `IncidentLog` を表示。MP4再生・動画共有・Map共有ボタンを提供。
