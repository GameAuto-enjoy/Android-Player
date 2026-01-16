# GameAuto Player (Open Source Engine)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

這是 **GameAuto** 的核心執行引擎 (Android Player)。為了消除使用者對於「自動化外掛」的安全疑慮，我們決定將此核心組件完全開源。

## 🛡️ 安全承諾 (Safety Guarantee)

我們深知 Accessibility (無障礙服務) 權限的敏感性。此專案公開證明了我們：

1.  **不竊取個資**：你可以檢查 `AccessibilityService` 的實作，確認我們只讀取遊戲畫面與執行點擊，絕不存取您的鍵盤輸入或背景敏感視窗。
2.  **本地運算優先**：圖像識別主要依賴 OpenCV 於手機本地執行。若需 AI 輔助，僅上傳必要的截圖區域，絕不再背景側錄螢幕。
3.  **無隱藏後門**：所有網路通訊僅限於 `game-auto-editor.vercel.app` (下載腳本) 與 Supabase (驗證訂閱)，無第三方未授權連線。

## 🛠️ 編譯指南 (Build Instructions)

若您不信任我們發布的 APK，您可以自行編譯：

### 前置需求
*   Android Studio Ladybug | 2024.2.1+
*   JDK 17
*   Android SDK 34 (UpsideDownCake)

### 建置步驟
1.  Clone 此專案：
    ```bash
    git clone https://github.com/GameAuto-enjoy/Android-Player.git
    cd Android-Player
    ```
2.  使用 Gradle 編譯：
    ```bash
    ./gradlew assembleDebug
    ```
3.  產出的 APK 位於 `app/build/outputs/apk/debug/app-debug.apk`。

## 🧩 架構簡介

*   **SceneGraphEngine.kt**: FSM 狀態機核心，負責載入 JSON 腳本並執行邏輯。
*   **PerceptionSystem.kt**: (Eyes) 負責 OpenCV 模板匹配、色彩判斷與 OCR。
*   **ActionSystem.kt**: (Hands) 負責執行點擊與滑動，包含擬人化隨機偏移演算法。
*   **AutomationService.kt**: Android Accessibility Service 的進入點。

## 🤝 貢獻

歡迎提交 Pull Request！如果您發現了繞過遊戲檢測的新方法，或優化了圖像識別效率，請務必分享給社群。

---
*GameAuto Team*
 
 
 
 
 
