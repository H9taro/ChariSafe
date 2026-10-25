# EVENT_DETECTION_LOG_SCHEMA_REVIEW.md
# 追加検討④: EventDetectionLog 感度記録スキーマ検討書

## 1. 課題
検知イベントによって感度設定の物理単位が異なる：
- **接近 (`APPROACH`)**: スコア閾値 / パーセンテージ (`%`)
- **急減速 (`DECELERATION`)**: 目標判定時間 (`sec`)
- **衝撃 (`COLLISION`)**: 加速度 G値 (`G`)
- **音量 (`SOUND`)**: 振幅値 (`Amp`)

単一の `sensitivityValue: Float` のみでは単位情報が欠落し、UI表示やログ分析時にフォーマット処理が複雑化する。

---

## 2. 候補案の比較 (A案, B案, C案, D案)

| 検討案 | 構造 | 長所 | 短所 | 評価 |
| :--- | :--- | :--- | :--- | :--- |
| **A案** | `sensitivityValue: Float` のみ | 最小軽量データ。 | 単位がなく表示時に変換ロジックが必要。 | △ |
| **B案** | `sensitivityLabel: String` 追加 | `"70%"`, `"2.5秒"`, `"3.0G"`, `"5000"` などのフォーマット文字列を直接保持。UI描画が超高速。 | 生数値の数値検索・集計がしにくい。 | ○ |
| **C案** | `sensitivitySnapshot: EventSensitivity` | 検知時点の全4種感度設定スナップショットをそのまま保持。 | 容量がわずかに増える。 | ○ |
| **D案** | イベント別サブモデル (Sealed Class) | 厳密な型安全。 | Room/JSONシリアライズや共通リスト表示が複雑化。 | △ |

---

## 3. 採用設計: A案 + B案 + C案 の補完型ハイブリッド構造

数値比較（A案）、UI表示の即時性（B案）、検知時点の全システム感度分析（C案）を完璧に満たす補完型モデルを採用する。

### 3.1 `EventDetectionLog` スキーマ定義

```kotlin
data class EventDetectionLog(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val eventType: EventType,
    val speedKmh: Float,
    val latitude: Double,
    val longitude: Double,

    // 感度情報 (補完型)
    val sensitivityValue: Float,            // 生数値 (例: 0.7, 1.5, 3.0, 5000.0)
    val sensitivityLabel: String,           // フォーマット済み表示文字列 (例: "感度: 70%", "1.5秒", "3.0G", "振幅: 5000")
    val sensitivitySnapshot: EventSensitivity = EventSensitivity(), // 検知時点の4種設定完全スナップショット

    var incidentId: String? = null           // 関連する IncidentLog ID
)
```

---

## 4. 各利用シーンとの整合性

### 4.1 イベント履歴 UI 表示 (`MainScreen.kt`)
`sensitivityLabel` をそのまま直接描画（文字列フォーマット処理不要）。
例: `🚗 後方接近 (速度: 18.5km/h | 感度: 70%)`

### 4.2 ライド分析 & CSV エクスポート (`ApproachDebugLogger` 等)
`sensitivityValue`（生数値）と `sensitivitySnapshot`（全4種感度）を利用。
「接近感度 70% 設定時に、大音量感度 5000 設定でどれくらい同時発火したか」といったクロス多角分析が可能。

### 4.3 成果物との一貫性
既存の `EventSensitivity` データクラス（デフォルト値保持モデル）をそのまま再利用できるため、コード変更量が最小限に抑えられる。
