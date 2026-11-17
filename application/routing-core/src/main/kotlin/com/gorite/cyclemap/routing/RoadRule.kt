package com.gorite.cyclemap.routing

/**
 * 今走っている道について、利用者に知らせる価値のある通行ルール。何も知らせなくてよいとき (普通の車道) は null。
 * 警告 ([isWarning]) は強めに、情報は控えめに出す。
 */
enum class RoadRuleKind(val isWarning: Boolean) {
    /** 自動車専用道路 (自転車は通行できない) */
    MOTOR_ONLY(true),
    /** 一方通行の逆向きに走っている */
    WRONG_WAY(true),
    /** 自転車通行不可の登録 (自動車専用以外) */
    BICYCLE_FORBIDDEN(true),
    /** 押して歩く区間 */
    DISMOUNT(true),
    /** 歩行者と共有の道 (歩行者優先) */
    PEDESTRIAN_SHARED(false),
    /** 自転車通行帯・自転車道・車道の共用表示の登録がある */
    BIKE_FACILITY(false),
    /** 一方通行だが、自転車は逆向きにも通行できる */
    CONTRAFLOW_ALLOWED(false),
}

/** 通行ルール ([info]) と、逆走の判定 ([wrongWay]) から、知らせる種類を決める。優先順位は上のほうが高い。 */
fun roadRuleKindOf(info: BicycleRoadInfo, wrongWay: Boolean): RoadRuleKind? {
    val flags = info.flags
    return when {
        // 自動車専用・通行不可は、逆走の判定より先に知らせる (自動車専用道路を逆向きに走っていても、まず「自転車は通れません」)
        info.roadway == BikeAccess.FORBIDDEN && flags and BicycleRoadInfo.FLAG_MOTORROAD != 0 -> RoadRuleKind.MOTOR_ONLY
        // 逆走は、この辺が自転車にとっても一方通行 (ONEWAY) で、逆向き許可が無いときだけ
        wrongWay && flags and BicycleRoadInfo.FLAG_ONEWAY != 0 && flags and BicycleRoadInfo.FLAG_CONTRAFLOW_ALLOWED == 0 -> RoadRuleKind.WRONG_WAY
        info.roadway == BikeAccess.FORBIDDEN -> RoadRuleKind.BICYCLE_FORBIDDEN
        info.roadway == BikeAccess.DISMOUNT -> RoadRuleKind.DISMOUNT
        flags and BicycleRoadInfo.FLAG_PEDESTRIAN_SHARED != 0 -> RoadRuleKind.PEDESTRIAN_SHARED
        info.left.facility in BIKE_FACILITIES || info.right.facility in BIKE_FACILITIES -> RoadRuleKind.BIKE_FACILITY
        flags and BicycleRoadInfo.FLAG_CONTRAFLOW_ALLOWED != 0 -> RoadRuleKind.CONTRAFLOW_ALLOWED
        else -> null
    }
}

private val BIKE_FACILITIES = setOf(SideFacility.SHARED_LANE, SideFacility.BIKE_LANE, SideFacility.BIKE_TRACK)
