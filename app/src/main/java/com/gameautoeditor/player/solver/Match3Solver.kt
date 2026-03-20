package com.gameautoeditor.player.solver

import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.util.PriorityQueue

/**
 * 神魔之塔/轉珠遊戲 本地演算法核心 - Max Combo 尋路
 */
object Match3Solver {
    
    // 預設盤面大小 (神魔之塔)
    const val COLUMNS = 6
    const val ROWS = 5
    
    // 預設珠子類型
    enum class OrbType {
        WATER, FIRE, WOOD, LIGHT, DARK, HEART, UNKNOWN
    }

    /**
     * 1. 網格切割與特徵辨識 (Grid Scanning)
     */
    fun analyzeGrid(boardImage: Bitmap): Array<Array<OrbType>> {
        val grid = Array(ROWS) { Array(COLUMNS) { OrbType.UNKNOWN } }
        val cellWidth = boardImage.width / COLUMNS
        val cellHeight = boardImage.height / ROWS
        
        for (row in 0 until ROWS) {
            for (col in 0 until COLUMNS) {
                val cx = (col * cellWidth) + (cellWidth / 2)
                val cy = (row * cellHeight) + (cellHeight / 2)
                
                // 為了避開珠子正中央的強化圖示 (白色星星、黑色外框)
                // 我們在珠子的中心及四個對角各採樣一次，共 5 個點
                val offsetW = cellWidth / 4
                val offsetH = cellHeight / 4
                
                val points = listOf(
                    Pair(cx, cy),
                    Pair(cx - offsetW, cy - offsetH), // Top-Left
                    Pair(cx + offsetW, cy - offsetH), // Top-Right
                    Pair(cx - offsetW, cy + offsetH), // Bottom-Left
                    Pair(cx + offsetW, cy + offsetH)  // Bottom-Right
                )
                
                val votes = mutableMapOf<OrbType, Int>()
                for ((px, py) in points) {
                    val safeX = px.coerceIn(0, boardImage.width - 1)
                    val safeY = py.coerceIn(0, boardImage.height - 1)
                    val pixelColor = boardImage.getPixel(safeX, safeY)
                    val type = classifyColor(pixelColor)
                    if (type != OrbType.UNKNOWN) {
                        votes[type] = votes.getOrDefault(type, 0) + 1
                    }
                }
                
                // 取票數最多且不是 UNKNOWN 的顏色，如果全都是 UNKNOWN (例如鎖定珠全黑)，則保底 UNKNOWN
                grid[row][col] = votes.maxByOrNull { it.value }?.key ?: OrbType.UNKNOWN
            }
        }
        
        // Print the grid for debugging
        val sb = java.lang.StringBuilder("\n👀 解析盤面如下：\n")
        for (row in 0 until ROWS) {
            for (col in 0 until COLUMNS) {
                val icon = when (grid[row][col]) {
                    OrbType.WATER -> "💧"
                    OrbType.FIRE -> "🔥"
                    OrbType.WOOD -> "🌿"
                    OrbType.LIGHT -> "☀️"
                    OrbType.DARK -> "🌑"
                    OrbType.HEART -> "💖"
                    OrbType.UNKNOWN -> "❓"
                }
                sb.append("$icon ")
            }
            sb.append("\n")
        }
        android.util.Log.i("GameAuto", sb.toString())
        
        return grid
    }

    private fun classifyColor(pixelColor: Int): OrbType {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(pixelColor, hsv)
        val h = hsv[0] // 0.0 ~ 360.0
        val s = hsv[1] // 0.0 ~ 1.0
        val v = hsv[2] // 0.0 ~ 1.0

        // 忽略完全灰階 (黑白圖示、暗色陰影)
        // 強化珠因為很亮，所以 v 還是很高，但中心白色 s 很低
        // 把 s 降低到 0.15 避免略過微黃的高亮光珠，v 降到 0.15 避免略過暗色珠的深色部分
        if (s < 0.15f || v < 0.15f) return OrbType.UNKNOWN

        return when {
            h >= 340f || h <= 20f -> OrbType.FIRE    // Red / Orange (0)
            h in 21f..75f -> OrbType.LIGHT           // Yellow / Bright (60)
            h in 76f..160f -> OrbType.WOOD           // Green (120)
            h in 161f..255f -> OrbType.WATER         // Cyan / Blue (210)
            h in 256f..300f -> OrbType.DARK          // Purple (270)
            h in 301f..339f -> OrbType.HEART         // Pink/Magenta (330)
            else -> OrbType.UNKNOWN
        }
    }

    // --- Max Combo Solver State Representation ---
    data class State(
        val grid: Array<Array<OrbType>>,
        val position: Pair<Int, Int>, // Current parsed orb position
        val path: List<Pair<Int, Int>>, // Path taken so far
        val cost: Int, // Steps taken
        val comboCount: Int, // Evaluated combos for this state
        val heuristicScore: Int = 0 // Evaluated potential combo score
    ) : Comparable<State> {
        // We want to MAXIMIZE combos, MINIMIZE steps
        // Priority Queue uses Min-Heap, so smaller compareTo comes first
        override fun compareTo(other: State): Int {
            if (this.heuristicScore != other.heuristicScore) {
                return other.heuristicScore.compareTo(this.heuristicScore)
            }
            // First priority: More combos
            if (this.comboCount != other.comboCount) {
                return other.comboCount.compareTo(this.comboCount)
            }
            // Second priority: Fewer steps
            return this.cost.compareTo(other.cost)
        }
        
        override fun equals(other: Any?): Boolean {
           if (this === other) return true
           if (javaClass != other?.javaClass) return false
           other as State
           for (r in 0 until ROWS) {
               for (c in 0 until COLUMNS) {
                   if (grid[r][c] != other.grid[r][c]) return false
               }
           }
           if (position != other.position) return false
           return true
        }
        override fun hashCode(): Int {
           var result = 0
           for (r in 0 until ROWS) {
               for (c in 0 until COLUMNS) {
                   result = 31 * result + grid[r][c].hashCode()
               }
           }
           result = 31 * result + position.hashCode()
           return result
        }
    }

    private fun getScore(initialGrid: Array<Array<OrbType>>, stepsTaken: Int): Pair<Int, Int> {
        var currentGrid = Array(ROWS) { r -> Array(COLUMNS) { c -> initialGrid[r][c] } }
        var totalCombos = 0
        var cascadeMultiplier = 1
        var score = 0
        
        while (true) {
            val hMatches = Array(ROWS) { BooleanArray(COLUMNS) }
            val vMatches = Array(ROWS) { BooleanArray(COLUMNS) }
            
            // 掃描水平連續 >= 3
            for (r in 0 until ROWS) {
                var c = 0
                while (c < COLUMNS) {
                    val type = currentGrid[r][c]
                    if (type != OrbType.UNKNOWN) {
                        var len = 1
                        while (c + len < COLUMNS && currentGrid[r][c + len] == type) {
                            len++
                        }
                        if (len >= 3) {
                            for (i in 0 until len) hMatches[r][c + i] = true
                        }
                        c += len
                    } else {
                        c++
                    }
                }
            }
            
            // 掃描垂直連續 >= 3
            for (c in 0 until COLUMNS) {
                var r = 0
                while (r < ROWS) {
                    val type = currentGrid[r][c]
                    if (type != OrbType.UNKNOWN) {
                        var len = 1
                        while (r + len < ROWS && currentGrid[r + len][c] == type) {
                            len++
                        }
                        if (len >= 3) {
                            for (i in 0 until len) vMatches[r + i][c] = true
                        }
                        r += len
                    } else {
                        r++
                    }
                }
            }
            
            val isMatch = Array(ROWS) { r -> BooleanArray(COLUMNS) { c -> hMatches[r][c] || vMatches[r][c] } }
            var matchCountThisPass = 0
            val visited = Array(ROWS) { BooleanArray(COLUMNS) }
            
            fun dfsBlob(r: Int, c: Int, type: OrbType, blob: MutableList<Pair<Int, Int>>) {
                if (r !in 0 until ROWS || c !in 0 until COLUMNS || visited[r][c] || !isMatch[r][c] || currentGrid[r][c] != type) return
                visited[r][c] = true
                blob.add(Pair(r, c))
                dfsBlob(r+1, c, type, blob)
                dfsBlob(r-1, c, type, blob)
                dfsBlob(r, c+1, type, blob)
                dfsBlob(r, c-1, type, blob)
            }
            
            for (r in 0 until ROWS) {
                for (c in 0 until COLUMNS) {
                    if (isMatch[r][c] && !visited[r][c]) {
                        val blob = mutableListOf<Pair<Int, Int>>()
                        dfsBlob(r, c, currentGrid[r][c], blob)
                        
                        matchCountThisPass++ 
                        totalCombos++
                        
                        val size = blob.size
                        // 輕微懲罰 4+ 連線，但不至於蓋過多重 combo 和 drop 連鎖的總價值
                        if (size > 3) {
                            score -= 500 * (size - 3)
                        }
                        
                        var maxR = 0
                        blob.forEach { if (it.first > maxR) maxR = it.first }
                        val depthBonus = (maxR + 1) * 2000
                        
                        score += (10000 * cascadeMultiplier) + depthBonus
                    }
                }
            }
            
            if (matchCountThisPass == 0) break
            cascadeMultiplier++
            
            // 掉落連鎖模擬 (Gravity fall)
            for (c in 0 until COLUMNS) {
                var writeR = ROWS - 1
                for (readR in ROWS - 1 downTo 0) {
                    if (!isMatch[readR][c]) {
                        currentGrid[writeR][c] = currentGrid[readR][c]
                        writeR--
                    }
                }
                while (writeR >= 0) {
                    currentGrid[writeR][c] = OrbType.UNKNOWN
                    writeR--
                }
            }
        }
        
        val stepPenalty = stepsTaken * 50
        return Pair(totalCombos, score - stepPenalty)
    }

    private fun swapGrid(grid: Array<Array<OrbType>>, p1: Pair<Int, Int>, p2: Pair<Int, Int>): Array<Array<OrbType>> {
        val newGrid = Array(ROWS) { r -> Array(COLUMNS) { c -> grid[r][c] } }
        val temp = newGrid[p1.first][p1.second]
        newGrid[p1.first][p1.second] = newGrid[p2.first][p2.second]
        newGrid[p2.first][p2.second] = temp
        return newGrid
    }

    /**
     * 3. 尋路演算法 (Pathfinding Engine) - Heuristic Search (Max Combo)
     */
    fun calculateBestPath(grid: Array<Array<OrbType>>): List<Pair<Int, Int>> {
        val MAX_STEPS = 120
        val BEAM_WIDTH = 350
        
        var bestState: State? = null
        var currentStates = mutableListOf<State>()
        
        for (r in 0 until ROWS) {
            for (c in 0 until COLUMNS) {
                // 不准抓 UNKNOWN (陷阱/問號珠) 作為起手
                if (grid[r][c] == OrbType.UNKNOWN) continue
                
                val initialPos = Pair(r, c)
                val (combos, score) = getScore(grid, 0)
                val st = State(grid, initialPos, listOf(initialPos), 0, combos, score)
                currentStates.add(st)
                if (bestState == null || score > bestState!!.heuristicScore) {
                    bestState = st
                }
            }
        }
        
        val dirs = arrayOf(Pair(-1, 0), Pair(1, 0), Pair(0, -1), Pair(0, 1)) 
        
        for (step in 1..MAX_STEPS) {
            val nextStates = mutableListOf<State>()
            val visitedHashes = mutableSetOf<Int>()
            
            for (state in currentStates) {
                for (d in dirs) {
                    val nr = state.position.first + d.first
                    val nc = state.position.second + d.second
                    
                    if (nr in 0 until ROWS && nc in 0 until COLUMNS) {
                        val nextPos = Pair(nr, nc)
                        
                        // 避免踩入陷阱珠 (UNKNOWN) 的格子
                        if (state.grid[nr][nc] == OrbType.UNKNOWN) continue
                        
                        // 避免無意義的馬上回頭 (U-turn)
                        if (state.path.size >= 2 && state.path[state.path.size - 2] == nextPos) {
                            continue
                        }
                        
                        val nextGrid = swapGrid(state.grid, state.position, nextPos)
                        val (nextCombos, nextScore) = getScore(nextGrid, state.cost + 1)
                        
                        val nextPath = state.path.toMutableList()
                        nextPath.add(nextPos)
                        
                        val nextState = State(nextGrid, nextPos, nextPath, state.cost + 1, nextCombos, nextScore)
                        
                        // 更新全域最佳解
                        if (nextScore > bestState!!.heuristicScore || 
                            (nextScore == bestState!!.heuristicScore && nextState.cost < bestState!!.cost)) {
                            bestState = nextState
                        }
                        
                        val hash = nextState.hashCode()
                        if (visitedHashes.add(hash)) {
                            nextStates.add(nextState)
                        }
                    }
                }
            }
            
            if (nextStates.isEmpty()) break
            
            // 優先列隊：根據 Score 數排序，取前 BEAM_WIDTH 名繼續走
            nextStates.sort() 
            currentStates = nextStates.take(BEAM_WIDTH).toMutableList()
            
            // 提早收工
            if (bestState!!.comboCount >= 10) break
        }
        
        return bestState?.path ?: listOf(Pair(0,0))
    }

    /**
     * 4. 座標轉換 (Matrix to Relative Percentages)
     */
    fun convertPathToSwipeConfig(path: List<Pair<Int, Int>>, anchorTargetBox: JSONObject): JSONArray {
        val startX = anchorTargetBox.optDouble("x", 0.0)
        val startY = anchorTargetBox.optDouble("y", 0.0)
        val totalW = anchorTargetBox.optDouble("w", 100.0)
        val totalH = anchorTargetBox.optDouble("h", 100.0)
        
        val cellW = totalW / COLUMNS
        val cellH = totalH / ROWS
        
        val pointsArray = JSONArray()
        for ((row, col) in path) {
            val pt = JSONObject()
            
            // 將 Anchor 的原始座標傳遞給 ActionSystem 以防比例失真計算錯誤
            pt.put("anchorX", startX)
            pt.put("anchorY", startY)
            pt.put("anchorW", totalW)
            pt.put("anchorH", totalH)

            // 原始偏移量 (不適用 calculateTargetPoint)
            pt.put("colOffset", col + 0.5)
            pt.put("rowOffset", row + 0.5)
            
            // 舊版備用 (與編輯器相容的原始百分比表示法)
            pt.put("x", startX + (col + 0.5) * cellW)
            pt.put("y", startY + (row + 0.5) * cellH)
            pt.put("w", cellW)
            pt.put("h", cellH)
            
            pointsArray.put(pt)
        }
        
        return pointsArray
    }
}
