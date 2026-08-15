package com.infocaller.app.util

object T9Search {
    private val t9Map = mapOf(
        '2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl",
        '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz"
    )

    fun matches(input: String, name: String): Boolean {
        if (input.isEmpty()) return true
        val normalizedName = name.lowercase()
        
        // 1. Direct contains check (numeric)
        if (normalizedName.contains(input)) return true
        
        // 2. T9 Sequence Match
        var nameIdx = 0
        var inputIdx = 0
        
        // This is a simplified T9 check: does the name contain a sequence that matches the input?
        // More advanced: check start of every word in name.
        val words = normalizedName.split(" ", "-", ".")
        
        for (word in words) {
            if (word.length >= input.length) {
                var match = true
                for (i in input.indices) {
                    val digit = input[i]
                    val charAtPos = word[i]
                    val validChars = t9Map[digit] ?: digit.toString()
                    if (!validChars.contains(charAtPos)) {
                        match = false
                        break
                    }
                }
                if (match) return true
            }
        }
        
        return false
    }
}
