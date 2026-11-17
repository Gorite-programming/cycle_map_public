package com.gorite.cyclemap.ui.effects

import android.content.ContextWrapper
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner

/** 同じ画面の位置追跡とグラフ効果が、Activity の寿命で案内中の保持状態を共有する。 */
internal class NavigationGraphState : ViewModel() {
    var active = false
}

internal fun navigationGraphState(context: Context): NavigationGraphState? {
    var ownerContext = context
    while (ownerContext !is ViewModelStoreOwner && ownerContext is ContextWrapper) {
        val base = ownerContext.baseContext
        if (base === ownerContext) break
        ownerContext = base
    }
    return (ownerContext as? ViewModelStoreOwner)?.let { ViewModelProvider(it)[NavigationGraphState::class.java] }
}
