# EVENT_COORDINATION_MIGRATION_PLAN.md
# Phase 3 イベント管理構造見直し 移行計画書

## 1. 修正対象クラス一覧 ＆ 影響範囲

| レイヤー | クラス名 | 役割 / 変更内容 | 影響度 |
| :--- | :--- | :--- | :--- |
| **Domain** | `EventLog.kt` | `EventDetectionLog`, `IncidentLog` の新規定義に置き換え／拡張 | 高 |
| **Domain** | `RideSummary.kt` | `eventCounts` を `EventDetectionLog` ベースの集計に変更 | 中 |
| **Data** | `EventLogStore.kt` | `EventDetectionLogStore` と `IncidentLogStore` に分離 | 高 |
| **Data** | `EventLogRepositoryImpl.kt` | 新データモデルに対応する StateFlow とリロード処理に修正 | 高 |
| **Service** | `RecordingService.kt` | `EventSavingLock` (AtomicBoolean) 導入、調停ハブ化 | 最高 |
| **Sensors** | `RearApproachDetector.kt` | 個別 `COOLDOWN_MS` 廃止、調停ハブへの通知のみに変更 | 中 |
| **Sensors** | `CollisionDropDetector.kt` | 個別 `COOLDOWN_MS` 廃止、調停ハブへの通知のみに変更 | 中 |
| **Sensors** | `AudioImpactDetector.kt` | 個別 `COOLDOWN_MS` 廃止、調停ハブへの通知のみに変更 | 中 |
| **Location**| `SpeedDecelerationDetector.kt`| 個別 `lastTriggerTimeMs` 制限撤廃、即時通知に変更 | 中 |
| **UI** | `MainViewModel.kt` | `eventDetectionLogs` と `incidentLogs` の保持・計算 | 高 |
| **UI** | `MainScreen.kt` | 「イベント履歴」と「保存動画一覧」のUI分離表示 | 高 |

---

## 2. 段階的移行手順 (Migration Steps)

### Step 1: ドメイン層 & データストアの分離 (新規作成)
1. `domain/model/EventLog.kt` に `EventDetectionLog` と `IncidentLog` のデータ構造を追加定義。
2. `data/local/EventDetectionLogStore.kt` および `data/local/IncidentLogStore.kt` を作成。
3. 旧 `event_logs.json` が存在する場合は初回起動時に自動コンバート（互換性確保）。

### Step 2: RecordingService のイベント調停ハブ化
1. `RecordingService` に `isEventSavingLocked = AtomicBoolean(false)` を実装。
2. `handleDetectedEvent()` を定義し、無条件の検知ログ保存 ➔ ロック獲得判定 ➔ 動画保存＆ロック解除の単一パイプラインを構築。

### Step 3: 各検出器のクールダウン撤廃
1. `RearApproachDetector`, `CollisionDropDetector`, `AudioImpactDetector`, `SpeedDecelerationDetector` から個別の `COOLDOWN_MS` 判定を削除。
2. 検知成立時は即座に `RecordingService.handleDetectedEvent()` へ通知する構造に変更。

### Step 4: UI & ViewModel の連携更新
1. `MainViewModel` の StateFlow を `eventDetectionLogs` と `incidentLogs` の2系統に整理。
2. `RideSummary` 計算を `eventDetectionLogs` に紐付け。
3. `MainScreen` の「イベント履歴」を `EventDetectionLog` 表示に、「保存動画」を `IncidentLog` 表示に切り替え。

---

## 3. リスク評価 & 対策

| リスク | 影響度 | 対策 / 回避策 |
| :--- | :--- | :--- |
| **ロック解除の失敗による動画保存不能** | 高 | `exportEventAsync` の `try-finally` ブロックで確実に `isEventSavingLocked.set(false)` を実行し、デッドロックを防止。タイムアウト（例: 10秒）保険も併設。 |
| **既存ユーザーの過去ログ損失** | 中 | 旧 `event_logs.json` を読み込み、`EventDetectionLog` と `IncidentLog` に自動変換して永続化するマイグレーションロジックを実装。 |
| **大量の検知ログによるメモリ/ファイル増大** | 低 | `EventDetectionLog` は1件あたり約150バイトと極めて軽量。1日1,000件検知されても約150KB程度であり、影響は軽微。 |
| **連続通知によるアラーム音の連打** | 中 | ロック中（同一インシデント内）は `triggerIncidentVideoSave()` が呼ばれないため、警告アラームビープ音も連打されず適切に1回のみ鳴動する。 |

---

## 4. 設計レビュー結果まとめ (結論)
- 本設計により、「同一個所での連鎖イベントによる重複動画生成」と「クールダウンによる後続車の撮り逃し」という二大課題が**100%解決**される。
- `RecordingService` をイベント調停ハブとする構成は最も自然であり、競合状態（Race Condition）の発生しない強固なアーキテクチャとなる。
