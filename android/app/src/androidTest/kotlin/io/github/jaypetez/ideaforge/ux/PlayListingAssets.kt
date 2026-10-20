package io.github.jaypetez.ideaforge.ux

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Typeface
import io.github.jaypetez.ideaforge.R
import java.io.File

internal fun writePlayListingAssets(context: Context, output: File, screenshots: List<Pair<String, File>>) {
    require(screenshots.size >= 4) { "listing needs at least four real phone screenshots" }
    val images = File(output, "images")
    val icon = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
    try {
        val drawable = context.applicationInfo.loadIcon(context.packageManager).mutate()
        drawable.setBounds(0, 0, icon.width, icon.height)
        val canvas = Canvas(icon)
        drawable.draw(canvas)
        val background = icon.getPixel(icon.width / 2, 0)
        check(Color.alpha(background) == 255) { "the launcher icon needs an opaque top-centre background" }
        // Play supplies the corner mask; extend the launcher's own background, not its radius.
        canvas.drawColor(background, PorterDuff.Mode.DST_OVER)
        icon.setHasAlpha(true)
        writePng(icon, File(images, "icon.png"))

        val feature = Bitmap.createBitmap(1024, 500, Bitmap.Config.ARGB_8888)
        try {
            val graphic = Canvas(feature)
            graphic.drawColor(background)
            graphic.drawBitmap(icon, null, Rect(128, 122, 384, 378), Paint(Paint.FILTER_BITMAP_FLAG))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                textSize = 64f
            }
            val title = context.getString(R.string.app_name)
            check(paint.measureText(title) <= 480f) { "listing title does not fit the feature graphic" }
            graphic.drawText(title, 448f, 236f, paint)
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            paint.textSize = 28f
            val line = "From an idea to a clear prompt"
            check(paint.measureText(line) <= 480f) { "listing tagline does not fit the feature graphic" }
            graphic.drawText(line, 448f, 292f, paint)
            feature.setHasAlpha(false)
            writePng(feature, File(images, "featureGraphic.png"))
        } finally {
            feature.recycle()
        }
    } finally {
        icon.recycle()
    }
    screenshots.forEachIndexed { index, (name, source) ->
        val bitmap = checkNotNull(BitmapFactory.decodeFile(source.path,
            BitmapFactory.Options().apply { inMutable = true })) { "cannot decode captured screenshot $source" }
        try {
            check(bitmap.width == 1080 && bitmap.height == 1920) {
                "listing needs native 1080x1920 frames, got ${bitmap.width}x${bitmap.height}; use pixel2Api34Visual"
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            check(pixels.all { Color.alpha(it) == 255 }) { "captured phone frame contains transparency" }
            // Lossless format conversion only: retain every original display pixel and both system bars.
            bitmap.setHasAlpha(false)
            writePng(bitmap, File(images, "phoneScreenshots/${(index + 1).toString().padStart(2, '0')}-$name.png"))
        } finally {
            bitmap.recycle()
        }
    }
}

private fun writePng(bitmap: Bitmap, file: File) {
    val parent = checkNotNull(file.parentFile)
    check(parent.isDirectory || parent.mkdirs()) { "cannot create $parent" }
    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "cannot encode $file" } }
}
