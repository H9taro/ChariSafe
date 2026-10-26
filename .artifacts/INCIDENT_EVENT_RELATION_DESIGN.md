# INCIDENT_EVENT_RELATION_DESIGN.md
# 追加検討: IncidentLog と保護動画セグメント・関連イベントの保持設計

## 1. 課題と目的
一連の事故シーケンス（例：`接近` ➔ `減速` ➔ `衝撃` ➔ `音量`）において、従来は単一イベントのトリガー情報のみを保存していたため、同一インシデント内で「衝撃」や「音量」が発生していた事実が隠蔽されてしまう問題があった。

新方式（リングバッファセグメント保護方式）の導入に伴い、1つのインシデントに対して**「カバーする保護動画セグメント群（`coveredSegmentFiles`）」**と**「期間内に発生したすべての関連イベント（`relatedDetectionIds`）」**を自動紐付けし、タイムライン上で1つの統合ログとして表示できるようにする。

---

## 2. スキーマ定義 (`IncidentLog`)

```kotlin
data class IncidentLog(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    
    // トリガーとなった最初のイベント
    val triggerEventType: EventType,
    val triggerDetectionLogId: String,

    // 関連イベント情報 (ハイブリッド)
    val relatedDetectionIds: List<String> = emptyList(),  // カバー期間内に起きた全 EventDetectionLog ID
    val highestSeverityType: EventType = triggerEventType, // 最重要（最高危険度）イベントタイプ

    val latitude: Double,
    val longitude: Double,
    
    // 保護された動画セグメント一覧
    var coveredSegmentFiles: List<String> = emptyList(),
    var status: IncidentStatus = IncidentStatus.PENDING
)
```

### 危険度（Severity）の優先順位定義
`highestSeverityType` の自動判定に使用する優先度ランク：

1. **`COLLISION` (衝撃)** [最優先]
2. **`DECELERATION` (減速)**
3. **`SOUND` (音量)**
4. **`APPROACH` (接近)**

---

## 3. 自動紐付けメカニズム (セグメント保護ウィンドウ方式)

1. **イベント発生時**:
   - `EventCoordinator` が通知を受け取り、該当時刻を含むセグメント（当セグメント＋直前セグメント）を `RingBufferManager` 経由で保護ディレクトリに退避／マークする。
2. **関連イベント収集**:
   - 同一セグメント期間中に発生したすべての `EventDetectionLog` ID を `relatedDetectionIds` に自動追加する。
   - 危険度ランクの高いイベントが発生した場合、`highestSeverityType` を自動的に最高ランクへ更新する。
3. **完成版 IncidentLog 永続化**:
   - 保護された動画セグメントパス一覧（`coveredSegmentFiles`）をセットし、`IncidentLogStore` に永続化する。

---

## 4. UI 表示設計 (`MainScreen.kt`)

ユーザーが「走行ログ タイムライン」を開くと、保護セグメントカード内にネストされた時系列イベントが表示される。

```text
┌────────────────────────────────────────────────────────────────────────┐
│ 🎥 08:10:00 録画セグメント #1 [▶ 再生] [📤 共有] [🔒 保護済]          │
│ トリガー: 🚗 接近 (💥 衝撃・🔊 音量 含む)  [最高危険度: 💥 衝撃]     │
├────────────────────────────────────────────────────────────────────────┤
│ ▼ 一連の発生イベントタイムライン (3件):                                │
│   ├─ 08:10:10  🚗 接近 (速度: 22.4km/h | 感度: 70%) [トリガー]      │
│   ├─ 08:10:12  💥 衝撃 (速度: 15.1km/h | 3.4G)     [最重症]        │
│   └─ 08:10:13  🔊 音量 (速度:  0.0km/h | 振幅: 28,500)             │
└────────────────────────────────────────────────────────────────────────┘
```
