# ChariSafe パッケージ構造定義書

本ドキュメントは、ChariSafe のソースコード設計におけるパッケージ分割ルール、クラス配置規約、および責務範囲を定義するものである。

## 1. ルートパッケージ

`jp.hisanet.charisafe`

---

## 2. パッケージ構造ツリー

```
jp.hisanet.charisafe
│
├── ChariSafeApplication.kt           # アプリケーションクラス（初期化処理）
├── MainActivity.kt                   # エントリポイント Activity（サービスバインドとComposeルート）
│
├── data                              # データ層 (Repositories, Services, Data Sources)
│   ├── config                        # 設定保持 (DataStore / Preferences)
│   │   └── ConfigRepositoryImpl.kt
│   │
│   ├── local
│   │   ├── ringbuffer                # リングバッファセグメント管理
│   │   │   ├── RingBufferManager.kt
│   │   │   └── Mp4Concatenator.kt
│   │   ├── gpx                       # GPX 1.1 XML ライター
│   │   │   └── GpxFileWriter.kt
│   │   └── log                       # ログ出力・CSVメトリクス
│   │       └── FileLogger.kt
│   │
│   ├── location                      # GPS 位置情報サービス
│   │   └── LocationTracker.kt
│   │
│   ├── repository                    # リポジトリ実装
│   │   ├── RecordingRepositoryImpl.kt
│   │   ├── GpxRepositoryImpl.kt
│   │   └── SystemRepositoryImpl.kt
│   │
│   └── service                       # Foreground Service 実装
│       └── RecordingService.kt
│
├── domain                            # ドメイン層 (Interfaces, UseCases, Models)
│   ├── model                         # ドメインモデル
│   │   ├── AppConfig.kt              # ★ 設定パラメータモデル (FPS, GPS間隔, リング時間, 解像度)
│   │   ├── VideoResolution.kt        # ★ 解像度Enum (480p, 720p, 1080p)
│   │   ├── RecordingState.kt         # 録画・スリープ・エラー状態Enum
│   │   ├── GpxPoint.kt               # 位置情報データ構造
│   │   ├── SystemStatus.kt           # バッテリー、速度、経過時間
│   │   └── EventTriggerType.kt       # 手動、衝撃、接近、スリープ等
│   │
│   ├── repository                    # リポジトリインターフェース
│   │   ├── ConfigRepository.kt       # ★ 設定リポジトリインターフェース
│   │   ├── RecordingRepository.kt
│   │   ├── GpxRepository.kt
│   │   └── SystemRepository.kt
│   │
│   └── usecase                       # ユースケース (ビジネスロジック)
│       ├── GetAppConfigUseCase.kt    # ★ 設定取得
│       ├── UpdateAppConfigUseCase.kt # ★ 設定更新
│       ├── StartRecordingUseCase.kt
│       ├── StopRecordingUseCase.kt
│       ├── SaveManualEventUseCase.kt # 手動イベント保存(前5分+後1分)
│       └── GetSystemStatusUseCase.kt
│
├── ui                                # UI層 (Compose, ViewModels, Theme)
│   ├── main                          # メインダッシュボード画面
│   │   ├── MainScreen.kt
│   │   ├── MainViewModel.kt
│   │   └── components                # UIコンポーネント
│   │       ├── StatusCard.kt
│   │       ├── SpeedGauge.kt
│   │       └── ActionButtons.kt
│   │
│   └── theme                         # Compose テーマ設定
│       ├── Color.kt
│       ├── Theme.kt
│       └── Type.kt
│
└── util                              # ユーティリティ
    ├── FileUtils.kt                  # パス生成・ストレージ空き管理
    └── TimeFormatter.kt              # 時間・日付フォーマット
```

---

## 3. クラス配置規約と責務範囲

### 3.1 `domain.model.AppConfig` / `VideoResolution` (★パラメータ変更対応)
- **`AppConfig`**: 動的変更可能な録画・動作パラメータを保持するイミュータブル・データクラス。
  - `fps`: 撮影フレームレート（デフォルト: `10` [5, 10, 15]）
  - `gpsIntervalSeconds`: GPS取得間隔（デフォルト: `30L` [5, 10, 30, 60 秒]）
  - `ringBufferMinutes`: リングバッファ保持時間（デフォルト: `5` [3, 5, 10 分]）
  - `resolution`: 録画解像度（デフォルト: `VideoResolution.HD_720P` [480p, 720p, 1080p]）
- **`VideoResolution`**: 解像度の幅・高さ・ラベルを定義するEnum。
