package com.orvlabs.helm.widget

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.orvlabs.helm.R
import java.io.File
import java.io.FileOutputStream
import com.orvlabs.helm.tr

object IncidentCard {
    private const val W = 1080
    private const val H = 1350

    private fun text(ctx: Context, size: Float, color: Int, weight: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(ctx, R.font.schibsted)
        fontVariationSettings = "'wght' $weight"
        textSize = size
        this.color = color
    }

    private fun Canvas.paragraph(s: String, p: TextPaint, x: Float, y: Float, width: Int, maxLines: Int): Float {
        val l = StaticLayout.Builder.obtain(s, 0, s.length, p, width).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines).setEllipsize(android.text.TextUtils.TruncateAt.END).setLineSpacing(0f, 1.1f).build()
        save()
        translate(x, y)
        l.draw(this)
        restore()
        return y + l.height
    }

    fun share(ctx: Context, headline: String, rows: List<Pair<String, String>>, summary: String) {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Art.PAPER)
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Art.CARD }
        c.drawRoundRect(40f, 40f, W - 40f, H - 40f, 56f, 56f, card)
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Art.MAGENTA }
        c.drawRoundRect(90f, 90f, 170f, 170f, 24f, 24f, mark)
        c.drawText(tr(R.string.helm), 200f, 148f, text(ctx, 34f, Art.MUTED, 700).apply { letterSpacing = 0.12f })
        var y = c.paragraph(headline, text(ctx, 104f, Art.BAD, 800), 90f, 230f, W - 180, 3) + 50f
        val label = text(ctx, 30f, Art.MUTED, 600)
        val value = text(ctx, 52f, Art.INK, 600)
        rows.forEach { (k, v) ->
            c.drawText(k, 90f, y + 30f, label)
            y = c.paragraph(v, value, 90f, y + 48f, W - 180, 2) + 30f
        }
        c.paragraph(summary, text(ctx, 42f, Art.MUTED, 400), 90f, y + 20f, W - 180, 7)
        c.drawText(tr(R.string.shared_from_helm), 90f, H - 90f, label)

        val dir = File(ctx.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, "incident-${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, "$headline. $summary").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("", uri)
        ctx.startActivity(Intent.createChooser(send, tr(R.string.share_this_incident)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
