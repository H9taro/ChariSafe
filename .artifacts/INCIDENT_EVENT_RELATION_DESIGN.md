# INCIDENT_EVENT_RELATION_DESIGN.md
# 追加検討②: IncidentLog への関連イベント保持 設計検討書

## 1. 課題と目的
一連の事故シーケンス（例：`接近` ➔ `急減速` ➔ `衝突` ➔ `大音量`）において、動画保存のトリガーが最初の「接近」だった場合、従来の設計では `IncidentLog`（保存動画）の記録が `triggerEventType = APPROACH` のみとなり、同一インシデント内で「衝突」や「大音量」が発生していた事実が隠蔽されてしまう。

目的：1本の保存動画（`IncidentLog`）に対して、動画保存開始からLock解除（動画結合完了）までに発生した**すべての関連イベント（`EventDetectionLog`）を自動紐付け**し、後から1本のインシデントとして時系列表示できるようにする。

---

## 2. 候補案の比較 (A案, B案, C案)

| 検討案 | 構造 | 長所 | 短所 | 評価 |
| :--- | :--- | :--- | :--- | :--- |
| **A案** | `relatedDetectionIds: List<String>` | 全検知ログ（`EventDetectionLog`）と完全な1対多リンクが可能。後から詳細データを辿れる。 | IDの配列のみのため、単体で種別がわからない。 | ○ |
| **B案** | `relatedEventTypes: List<EventType>` | `[APPROACH, COLLISION, SOUND]` のように一目で発生種別がわかる。 | 同一種別の複数回発生や、元の `EventDetectionLog` との紐付けができない。 | △ |
| **C案** | `highestSeverityEvent: EventType` | 一番重要なイベント（例：衝突）を1つだけバッジ表示できる。 | 他のイベント情報が削ぎ落とされる。 | △ |

---

## 3. 採用設計: A案 + C案 のハイブリッド方式

1対多の完全追跡（A案）と、UIでの直感的な表示・ソート（C案）の両立を図るため、ハイブリッド構成を採用する。

### 3.1 `IncidentLog` スキーマ定義

```kotlin
data class IncidentLog(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    
    // トリガーとなった最初のイベント
    val triggerEventType: EventType,
    val triggerDetectionLogId: String,

    // 関連イベント情報 (ハイブリッド)
    val relatedDetectionIds: List<String> = emptyList(),  // 動画保存開始〜Lock解除までの全 EventDetectionLog ID
    val highestSeverityType: EventType = triggerEventType, // 最重要（最高危険度）イベントタイプ

    val latitude: Double,
    val longitude: Double,
    var videoFilePath: String = "",
    var status: IncidentStatus = IncidentStatus.PENDING
)
```

### 3.2 危険度（Severity）の優先順位定義
`highestSeverityType` の自動判定に使用する優先度ランク：

1. **`COLLISION` (衝突・落下)** [最優先]
2. **`DECELERATION` (急減速)**
3. **`SOUND` (大音量)**
4. **`APPROACH` (後方接近)**

---

## 4. 自動紐付けメカニズム (Lockウィンドウ方式)

1. **Lock獲得時 (動画保存開始)**:
   - `EventCoordinator` がロックを獲得すると、アクティブなインシデントセッションが開始され、トリガーとなった `EventDetectionLog` の ID が `relatedDetectionIds` の最初の要素となる。
2. **Lock保持期間 (動画処理中)**:
   - この間に他の検知イベント（例: `急減速` ➔ `衝突` ➔ `大音量`）が発生すると、通常通り `EventDetectionLog` が個別に保存されるとともに、その ID がアクティブインシデントの `relatedDetectionIds` に即座に自動追加される。
   - 同時に、発生したイベントの危険度が現在の `highestSeverityType` より高い場合、`highestSeverityType` が自動更新される。
3. **Lock解除時 (動画保存完了)**:
   - 収集された `relatedDetectionIds` と最終的な `highestSeverityType` を含む完成版 `IncidentLog` が `IncidentLogStore` に永続化され、セッションが終了する。

---

## 5. UI での 1本インシデント時系列表示設計 (`MainScreen.kt`)

ユーザーが「保存動画一覧」画面で各 `IncidentLog` カードを展開すると、1本のインシデントとして**ネストされた時系列タイムライン (Nested Incident Timeline)** が表示される。

### 5.1 UI 表示イメージ

```text
┌────────────────────────────────────────────────────────────────────────┐
│ 🎥 2026-10-25 15:01:10 インシデント #12                          │
│ トリガー: 🚗 後方接近 (💥 衝突・🔊 大音量 含む)  [最高危険度: 💥 衝突]│
│ [ ▶ 動画再生 (event_20261025_150110_APPROACH.mp4) ] [ 📤 共有 ]        │
├────────────────────────────────────────────────────────────────────────┤
│ ▼ 一連の発生イベントタイムライン (3件):                                │
│   ├─ 15:01:10  🚗 後方接近 (速度: 22.4km/h | 感度: 70%) [トリガー]  │
│   ├─ 15:01:12  💥 衝撃検知 (速度: 15.1km/h | 3.4G)     [最重症]     │
│   └─ 15:01:13  🔊 大音量検知 (速度:  0.0km/h | 振幅: 28,500)       │
└────────────────────────────────────────────────────────────────────────┘
```

ユーザーは1本の保存動画を再生しながら、その動画内でどのタイミングでどの不測事態（接近➔衝突➔大音量）が起きたかを正確に時系列で把握できる。
