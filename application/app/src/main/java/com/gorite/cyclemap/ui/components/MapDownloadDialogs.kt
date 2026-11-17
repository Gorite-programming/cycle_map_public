package com.gorite.cyclemap.ui.components

import android.content.Context
import android.location.Location
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import com.gorite.cyclemap.data.DownloadStatusManager
import com.gorite.cyclemap.data.MapSourceType
import com.gorite.cyclemap.data.Prefecture
import com.gorite.cyclemap.data.OfflineDataDeleter
import com.gorite.cyclemap.search.SearchResult
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint

@Composable
internal fun MapDownloadDialogs(
    context: Context,
    showAreaDownloadConfirmDialog: Boolean,
    selectedAreaBounds: BoundingBox?,
    isDownloading: Boolean,
    onStartAreaDownload: (BoundingBox, MapSourceType) -> Unit,
    onDismissAreaDownload: () -> Unit,
    showDestinationSearch: Boolean,
    currentLocation: Location?,
    onDismissDestinationSearch: () -> Unit,
    /** 検索結果を選んだとき: 地図をその場所へ動かし、場所の詳細を開く。 */
    onSearchResultSelected: (com.gorite.cyclemap.contract.NearbySpot) -> Unit,
    pendingSelectedSpot: GeoPoint?,
    onAddWaypoint: (GeoPoint) -> Unit,
    onChangeDestination: (GeoPoint) -> Unit,
    onDismissPendingSpot: () -> Unit,
    showPrefectureListDialog: Boolean,
    onDismissPrefectureListDialog: () -> Unit,
    onPrefectureSelected: (Prefecture) -> Unit,
    showAlreadyDownloadedDialog: Boolean,
    selectedPrefectureForAction: Prefecture?,
    onRedownloadPrefecture: () -> Unit,
    onDismissAlreadyDownloaded: () -> Unit,
    showDownloadConfirmDialog: Boolean,
    onDownloadPrefectureGsi: (Prefecture) -> Unit,
    onDownloadPrefectureOsm: (Prefecture) -> Unit,
    onDismissDownloadConfirm: () -> Unit,
    offlineDataUsage: () -> OfflineDataDeleter.Usage = { OfflineDataDeleter.Usage(busy = true) },
) {
    // Area download confirmation dialog (high-zoom z15-z16 for user-selected bbox)
    if (showAreaDownloadConfirmDialog && selectedAreaBounds != null) {
        val areaBounds = selectedAreaBounds
        AreaDownloadConfirmDialog(
            context = context,
            areaBounds = areaBounds,
            isDownloading = isDownloading,
            onStartDownload = { sourceType ->
                if (!isDownloading) {
                    onStartAreaDownload(areaBounds, sourceType)
                }
            },
            onDismiss = onDismissAreaDownload,
        )
    }

    if (showDestinationSearch) {
        DestinationSearchDialog(
            context = context,
            liveLocation = currentLocation?.let { it.latitude to it.longitude },
            onDismiss = onDismissDestinationSearch,
            onResultSelected = { result ->
                onSearchResultSelected(
                    com.gorite.cyclemap.contract.NearbySpot(
                        name = result.name,
                        category = result.category,
                        latitude = result.latitude,
                        longitude = result.longitude,
                        distanceM = result.distanceMeters ?: 0.0,
                    ),
                )
            },
        )
    }

    if (pendingSelectedSpot != null) {
        val spot = pendingSelectedSpot
        WaypointOrDestinationDialog(
            spot = spot,
            onAddWaypoint = { onAddWaypoint(spot) },
            onChangeDestination = { onChangeDestination(spot) },
            onDismiss = onDismissPendingSpot,
        )
    }

    // 1. Prefecture List Dialog (47 Prefectures grouped by Region)
    if (showPrefectureListDialog) {
        PrefectureListDialog(
            context = context,
            offlineDataUsage = offlineDataUsage,
            onDismiss = onDismissPrefectureListDialog,
            onPrefectureSelected = onPrefectureSelected,
            currentLocation = currentLocation,
        )
    }

    // 2. Already Downloaded Dialog
    if (showAlreadyDownloadedDialog && selectedPrefectureForAction != null) {
        AlreadyDownloadedPrefectureDialog(
            context = context,
            pref = selectedPrefectureForAction,
            onRedownload = onRedownloadPrefecture,
            onDismiss = onDismissAlreadyDownloaded,
        )
    }

    // 3. Download Confirm / Source Selection Dialog
    if (showDownloadConfirmDialog && selectedPrefectureForAction != null) {
        val pref = selectedPrefectureForAction
        PrefectureDownloadConfirmDialog(
            pref = pref,
            isDownloading = isDownloading,
            onDownloadGsi = {
                if (!isDownloading) {
                    onDownloadPrefectureGsi(pref)
                }
            },
            onDownloadOsm = {
                if (!isDownloading) {
                    onDownloadPrefectureOsm(pref)
                }
            },
            onDismiss = onDismissDownloadConfirm,
        )
    }
}
