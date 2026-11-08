package com.gorite.cyclemap.ui.mapview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import org.maplibre.android.maps.Style

/**
 * 国道・県道の標識画像 (道路上の番号シールド用)。スタイルの roads_shields レイヤーが
 * `jp-national-<桁数>char` / `jp-prefectural-<桁数>char` を参照する (scripts/patch_map_style.py)。
 * 番号の文字そのものはスタイル側の text-field が描くので、ここでは枠だけを描く。
 *
 * - 国道: 青地の逆三角形 (おにぎり型) に白い縁。
 * - 県道: 青地の六角形に白い縁。
 * 文字はどちらも白 (標識の青との対比。形で国道と県道を区別する)。
 */
internal const val SHIELD_NATIONAL_PREFIX = "jp-national-"
internal const val SHIELD_PREFECTURAL_PREFIX = "jp-prefectural-"
internal val SHIELD_DIGIT_COUNTS = 1..4

private const val SHIELD_BLUE = 0xFF0B5DA8.toInt()
private const val SHIELD_WHITE = 0xFFFFFFFF.toInt()

/** 桁数に応じた標識の幅 (dp)。高さは [SHIELD_HEIGHT_DP] で固定。 */
internal fun shieldWidthDp(digits: Int): Float = 16f + 6f * digits

private const val SHIELD_HEIGHT_DP = 20f

/** 国道・県道の標識画像を、まだ登録されていなければスタイルに登録する。 */
internal fun registerRoadShieldImages(context: Context, style: Style) {
    val density = context.resources.displayMetrics.density
    for (digits in SHIELD_DIGIT_COUNTS) {
        val nationalKey = "$SHIELD_NATIONAL_PREFIX${digits}char"
        if (style.getImage(nationalKey) == null) {
            style.addImage(nationalKey, drawNationalShield(shieldWidthDp(digits), density))
        }
        val prefecturalKey = "$SHIELD_PREFECTURAL_PREFIX${digits}char"
        if (style.getImage(prefecturalKey) == null) {
            style.addImage(prefecturalKey, drawPrefecturalShield(shieldWidthDp(digits), density))
        }
    }
}

private fun newShieldBitmap(widthDp: Float, density: Float): Bitmap =
    Bitmap.createBitmap(
        (widthDp * density).toInt().coerceAtLeast(1),
        (SHIELD_HEIGHT_DP * density).toInt().coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )

private fun drawNationalShield(widthDp: Float, density: Float): Bitmap {
    val bitmap = newShieldBitmap(widthDp, density)
    val canvas = Canvas(bitmap)
    val w = bitmap.width.toFloat()
    val h = bitmap.height.toFloat()
    val stroke = 1.2f * density
    val inset = stroke
    // 上辺が広く、下に向かって細くなる逆三角形 (角は丸める)
    val path = Path().apply {
        moveTo(inset + 0.04f * w, inset + 0.08f * h)
        lineTo(w - inset - 0.04f * w, inset + 0.08f * h)
        lineTo(w / 2f, h - inset)
        close()
    }
    val round = CornerPathEffect(3f * density)
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = SHIELD_BLUE; pathEffect = round
    })
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = SHIELD_WHITE; strokeWidth = stroke
        strokeJoin = Paint.Join.ROUND; pathEffect = round
    })
    return bitmap
}

private fun drawPrefecturalShield(widthDp: Float, density: Float): Bitmap {
    val bitmap = newShieldBitmap(widthDp, density)
    val canvas = Canvas(bitmap)
    val w = bitmap.width.toFloat()
    val h = bitmap.height.toFloat()
    val stroke = 1.6f * density
    val inset = stroke
    val corner = h * 0.28f
    val path = Path().apply {
        moveTo(inset, h / 2f)
        lineTo(inset + corner, inset)
        lineTo(w - inset - corner, inset)
        lineTo(w - inset, h / 2f)
        lineTo(w - inset - corner, h - inset)
        lineTo(inset + corner, h - inset)
        close()
    }
    val round = CornerPathEffect(2.5f * density)
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = SHIELD_BLUE; pathEffect = round
    })
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = SHIELD_WHITE; strokeWidth = stroke
        strokeJoin = Paint.Join.ROUND; pathEffect = round
    })
    return bitmap
}
