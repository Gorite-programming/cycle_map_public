package com.gorite.cyclemap.contract

enum class TravelModeType {
    Bicycle,
    Walk,
    Car
}

sealed interface BusinessStatus {
    data object Open : BusinessStatus
    data class ClosingSoon(val closingTime: String) : BusinessStatus
    data object Closed : BusinessStatus
    data object Unknown : BusinessStatus
}

data class PlaceDetail(
    val id: String,
    val name: String,
    val category: String,
    val address: String,
    val distanceMeters: Double?,
    val lat: Double,
    val lon: Double,
    val businessStatus: BusinessStatus = BusinessStatus.Unknown,
    val openingHoursText: String? = null,
    val phone: String? = null,
    val website: String? = null,
    /** OSM の wheelchair (yes / limited / no / designated)。無ければ null。 */
    val wheelchair: String? = null,
)

data class RouteOption(
    val id: String,
    val title: String,
    val travelMode: TravelModeType,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val elevationGainMeters: Double,
    val points: List<Pair<Double, Double>> = emptyList(),
    val isPrimary: Boolean = false,
    /** 経路のグラフ上のノード id。points の nodePointOffset から順に対応する。無ければ空。 */
    val nodeIds: List<Long> = emptyList(),
    /** 表示線に起点側の接続点を足した場合の、最初のグラフノードの位置。 */
    val nodePointOffset: Int = 0,
)

enum class TurnType {
    Straight,
    SlightLeft,
    Left,
    SharpLeft,
    SlightRight,
    Right,
    SharpRight,
    Uturn,
    Roundabout,
    Arrive
}

data class LaneInfo(
    val direction: TurnType,
    val isRecommended: Boolean
)

data class GuidanceStep(
    val turnType: TurnType,
    val instructionText: String,
    val streetName: String?,
    val distanceToTurnMeters: Double
)

data class EtaInfo(
    val remainingDistanceMeters: Double,
    val remainingSeconds: Double,
    val arrivalEpochMillis: Long,
    val currentSpeedKmh: Double
)

data class NearbySpot(
    val name: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val distanceM: Double,
    val address: String? = null,
)
