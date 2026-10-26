# EVENT_COORDINATION_MIGRATION_PLAN.md
# Phase 3 イベント管理構造見直し 移行計画書 (マルチ画面ナビゲーション・新UI・OS通知連動対応版)

## 1. 修正対象クラス一覧 ＆ 変更概要

| レイヤー | クラス名 | 役割 / 変更内容 | 影響度 |
| :--- | :--- | :--- | :--- |
| **Domain** | `EventLog.kt` | `EventDetectionLog`, `IncidentLog` の更新 | 高 |
| **Data** | `RideSummaryStore.kt` [新規] | 過去の走行結果サマリー (`RideSummary`) の JSON 永続化ストア作成 | 高 |
| **Data** | `RingBufferManager.kt` | セグメント保護（コピー/退避）機能 | 高 |
| **Service** | `RecordingService.kt` | 録画継続セグメント保護化、接近限定警告音、OS通知（記録完了/容量不足）発行 | 最高 |
| **Service** | `EventCoordinator.kt` | 重複保護抑止および接近警告音鳴動中の音量検知一時ミュート連動 | 中 |
| **Sensors** | `AudioImpactDetector.kt` | 接近警告音鳴動時の一時ミュートフラグ処理組み込み | 中 |
| **Sensors** | `RearApproachDetector.kt` | クールダウン廃止、即時通知化 | 中 |
| **Sensors** | `CollisionDropDetector.kt` | 即時通知化 | 中 |
| **Location**| `SpeedDecelerationDetector.kt`| 即時通知化 | 中 |
| **UI** | `MainViewModel.kt` | ナビゲーション管理、通知ディープリンク(`targetEventId`)受領・スクロール指示、セッション累計件数 | 高 |
| **UI** | `MainScreen.kt` | ナビゲーションドロワー構築、常時表示画面徹底軽量化、イベント履歴画面（高コントラスト・ハイライト）、ライド結果画面（過去参照）、設定画面 | 最高 |

---

## 2. 段階的移行手順 (Migration Steps)

### Step 1: ドメインモデル & データストアの拡張
1. `RideSummaryStore` を作成し、録画ストップ時に `RideSummary` を自動永続化する。
2. `EventDetectionLog` / `IncidentLog` のデータストア動作確認。

### Step 2: RecordingService & センサー層の刷新
1. `RecordingService` の `handleDetectedEvent()` を接近限定警告音（`EventType.APPROACH` のみ）に変更。
2. 接近警告音鳴動中（約800ms）は `AudioImpactDetector` をミュートする仕組みを組み込み。
3. `RecordingService` から記録完了通知およびストレージ空き容量低下通知（< 500MB）を発行。

### Step 3: UI Navigation & 常時表示画面の徹底軽量化
1. `MainScreen` に `ModalNavigationDrawer` を導入し、常時表示、イベント履歴、ライド結果、設定の4画面に分離。
2. 常時表示画面から録画時間タイマー・説明テキストを削除し、超大文字速度メーター、スライダー4種 (セッション累計件数表示: `接近 70% 28件` 等)、形状グラフィック大録画ボタン（🔴 / ⏹️）に整理。
3. サブ画面TopAppBar左上に「🏠 戻る」固定ボタンを配置。

### Step 4: 通知ディープリンク ＆ スクロールハイライト表示
1. 通知タップ時に `targetEventId` を持たせてアプリを起動。
2. イベント履歴画面で該当アイテムまで `animateScrollToItem` で自動スクロールし、2秒間カードをハイライト表示。

### Step 5: ライド結果画面 (過去サマリー) & 設定画面の独立
1. ライド結果画面で `RideSummaryStore` から過去の走行履歴を選択・分析可能に。
2. 設定画面に撮影カメラ、リングバッファ時間、CSVデバッグ設定を集約。

---

## 3. 評価と期待される成果
- **走行中視認性・操作性の極限向上**: 超大文字・軽量画面によりチラ見でも即座に状況把握。
- **誤検知（自作自演ビープ音）の100%防止**: 接近警告音鳴動中の音量検知ミュートにより誤作動を排除。
- **通知からの最速アクセス**: 通知タップで目的の動画・ログへダイレクトにジャンプ。
- **ライド結果の長期蓄積**: 過去の走行ログをいつでも振り返り分析可能。
