package com.gorite.cyclemap.ui.screens

/** 表示中の経路で案内を開始したかを覚え、経路を消すときに一度だけ確認する。 */
internal class NavigationEndPromptTracker(didStartNavigation: Boolean = false) {
    var didStartNavigation = didStartNavigation
        private set

    fun onNavigationStarted() {
        didStartNavigation = true
    }

    fun onRouteReplaced(isReroute: Boolean = false) {
        if (!isReroute) didStartNavigation = false
    }

    fun shouldPromptOnClear(isRecording: Boolean): Boolean {
        val shouldPrompt = didStartNavigation && isRecording
        didStartNavigation = false
        return shouldPrompt
    }
}
