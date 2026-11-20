package com.gorite.cyclemap.ui.components

import com.gorite.cyclemap.data.PrefectureDataStatus

internal fun offlineDataSummary(status: PrefectureDataStatus): String = listOfNotNull(
    "ルート".takeIf { status.hasRouting },
    "検索".takeIf { status.hasSearch },
    "地図".takeIf { status.hasVectorMap },
    "地形".takeIf { status.hasTerrain },
    "高速探索".takeIf { status.hasFastRouting },
).joinToString("・") + "が入っています"
