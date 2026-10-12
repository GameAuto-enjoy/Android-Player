package com.gameautoeditor.player

import android.util.Log

class ScriptEngine(private val service: AutomationService) {
    
    private val TAG = "GameAuto"
    private var sceneGraphEngine: SceneGraphEngine? = null

    /**
     * Executes the script using the FSM Engine (SceneGraphEngine).
     * Linear/Dumb execution is deprecated and removed to enforce state-based logic.
     */
    fun executeScript(scriptJson: String) {
        Log.i(TAG, "🚀 Requesting Script Execution (Enforcing FSM)")
        
        if (sceneGraphEngine == null) {
            sceneGraphEngine = SceneGraphEngine(service)
        }
        
        // Always delegate to FSM Engine
        sceneGraphEngine?.start(scriptJson)
    }

    fun stop() {
        sceneGraphEngine?.stop()
        Log.i(TAG, "⏹️ Script Stopped")
    }
}
