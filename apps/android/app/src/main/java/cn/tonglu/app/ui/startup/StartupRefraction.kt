package cn.tonglu.app.ui.startup

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** One shared backdrop. The glass bends its pixels rather than painting opaque tiles over it. */
internal class StartupRefraction(
    photo: Bitmap?,
    canvasColor: Int,
    accentColor: Int,
    private val dark: Boolean,
) {
    private val source = photo ?: createBackdrop(canvasColor, accentColor, dark)
    private val sampler = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private val gpu = if (Build.VERSION.SDK_INT >= 33) try {
        RefractionShader(sampler)
    } catch (error: Exception) {
        Log.w("TongluStartup", "折射着色器不可用，改用兼容透镜：${error.javaClass.simpleName}")
        null
    } else null

    fun draw(canvas: Canvas, width: Float, height: Float, density: Float, progress: Float) {
        if (width <= 0f || height <= 0f) return
        val scale = max(width / source.width, height / source.height)
        val tx = (width - source.width * scale) * .5f
        val ty = (height - source.height * scale) * .5f
        matrix.setScale(scale, scale)
        matrix.postTranslate(tx, ty)
        sampler.setLocalMatrix(matrix)
        if (Build.VERSION.SDK_INT >= 33 && gpu != null && canvas.isHardwareAccelerated) {
            gpu.draw(canvas, width, height, density, progress, dark)
            return
        }

        // API 24–32 and software Canvas: a convex centre plus an independently sampled bevel.
        // No per-frame bitmap allocation or full-screen blur layers.
        paint.shader = sampler
        canvas.drawRect(0f, 0f, width, height, paint)
        val cell = width / 5f
        val rows = ceil(height / cell).toInt()
        for (row in 0 until rows) for (col in 0 until 5) {
            val wave = startupWave(progress, (col + .5f) / 5f, (row + .5f) * cell / height)
            val s = abs(wave)
            val cx = (col + .5f) * cell
            val cy = (row + .5f) * cell
            val lift = -wave * 8f * density
            val gap = .8f * density
            val radius = 5f * density
            rect.set(col * cell + gap, row * cell + gap + lift,
                (col + 1) * cell - gap, (row + 1) * cell - gap + lift)
            val zoom = 1.025f + s * .045f
            matrix.setScale(scale * zoom, scale * zoom)
            matrix.postTranslate(tx * zoom + cx * (1f - zoom) + wave * density * 3f,
                ty * zoom + cy * (1f - zoom) + lift)
            sampler.setLocalMatrix(matrix)
            canvas.drawRoundRect(rect, radius, radius, paint)
            clip.rewind()
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(clip)
            // The rim samples a slightly wider area of the same image, visibly bending its edge.
            edgePaint.style = Paint.Style.STROKE
            edgePaint.strokeWidth = (3f + s * 3f) * density
            edgePaint.shader = sampler
            matrix.setScale(scale * (1f - .025f - s * .025f), scale * (1f - .025f - s * .025f))
            matrix.postTranslate(tx + density * (2f + wave * 3f), ty - density * (2f + wave * 3f))
            sampler.setLocalMatrix(matrix)
            canvas.drawRoundRect(rect, radius, radius, edgePaint)
            edgePaint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(withAlpha(Color.WHITE, if (dark) .36f + s * .22f else .62f + s * .20f),
                    withAlpha(Color.WHITE, .035f), withAlpha(Color.BLACK, .07f)), null, Shader.TileMode.CLAMP)
            edgePaint.strokeWidth = (.65f + s * .35f) * density
            canvas.drawRoundRect(rect, radius, radius, edgePaint)
            edgePaint.shader = null
            canvas.restoreToCount(save)
        }
    }
}

private fun withAlpha(color: Int, alpha: Float): Int =
    (color and 0x00ffffff) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

/** Original soft light and a broad curved ribbon give the lens something visible to refract. */
private fun createBackdrop(base: Int, accent: Int, dark: Boolean): Bitmap {
    val bitmap = Bitmap.createBitmap(480, 960, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawColor(base)
    paint.shader = LinearGradient(0f, 0f, 480f, 960f,
        intArrayOf(withAlpha(accent, .03f), withAlpha(accent, if (dark) .34f else .22f),
            withAlpha(accent, .02f)), null, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, 480f, 960f, paint)
    paint.shader = RadialGradient(90f, 260f, 370f,
        intArrayOf(withAlpha(accent, if (dark) .50f else .32f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, 480f, 960f, paint)
    paint.shader = RadialGradient(420f, 120f, 370f,
        intArrayOf(withAlpha(Color.WHITE, if (dark) .10f else .48f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, 480f, 960f, paint)
    val ribbon = Path().apply { moveTo(-140f, 650f); cubicTo(200f, 730f, 490f, 120f, 640f, 210f) }
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 110f
    paint.shader = LinearGradient(60f, 600f, 430f, 250f,
        intArrayOf(withAlpha(accent, .08f), withAlpha(Color.WHITE, if (dark) .17f else .55f),
            withAlpha(accent, .10f)), null, Shader.TileMode.CLAMP)
    canvas.drawPath(ribbon, paint)
    return bitmap
}

@TargetApi(33)
private class RefractionShader(private val sampler: BitmapShader) {
    private val shader = RuntimeShader(SOURCE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = this@RefractionShader.shader }

    fun draw(canvas: Canvas, width: Float, height: Float, density: Float, progress: Float, dark: Boolean) {
        // Child shader bindings capture their native instance; rebind after the crop matrix changes.
        shader.setInputShader("backdrop", sampler)
        shader.setFloatUniform("viewport", width, height)
        shader.setFloatUniform("density", density)
        shader.setFloatUniform("progress", progress.coerceIn(0f, 1f))
        shader.setFloatUniform("dark", if (dark) 1f else 0f)
        canvas.drawRect(0f, 0f, width, height, paint)
    }

    companion object {
        // Refraction is sampled from the actual selected photo, not from an unrelated overlay.
        // Three samples keep colour dispersion confined to a sub-pixel rim, not the entire image.
        private const val SOURCE = """
uniform shader backdrop;
uniform float2 viewport;
uniform float density;
uniform float progress;
uniform float dark;

half4 main(float2 coord) {
    float cell = viewport.x / 5.0;
    float2 tile = floor(coord / cell);
    float2 centre = (tile + 0.5) * cell;
    float wave = 0.0;
    if (progress > 0.0 && progress < 0.68) {
        float t = progress / 0.68;
        float d = centre.y / viewport.y - (1.18 - 1.42 * t)
            + abs(centre.x / viewport.x - 0.5) * 0.08;
        wave = exp(-d * d / 0.026) * sin(d * 19.0 + 1.4);
    }
    float strength = abs(wave);
    float2 local = coord - centre - float2(0.0, -wave * 8.0 * density);
    float halfCell = cell * 0.5 - 0.8 * density;
    float radius = 5.0 * density;
    float2 q = abs(local) - halfCell + radius;
    float sd = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
    float coverage = 1.0 - smoothstep(-0.8, 0.8, sd);
    half4 background = backdrop.eval(clamp(coord, float2(0.0), viewport));
    if (coverage <= 0.0) return background;

    float edgeDistance = max(-sd, 0.0);
    float bevel = 1.0 - smoothstep(0.0, (6.0 + strength * 4.0) * density, edgeDistance);
    float2 normal = normalize(sign(local) * max(q, float2(0.001)));
    if (max(q.x, q.y) < 0.0) {
        normal = abs(local.x) > abs(local.y) ? float2(sign(local.x), 0.0) : float2(0.0, sign(local.y));
    }
    float2 unit = local / halfCell;
    float dome = max(0.0, 1.0 - dot(unit, unit) * 0.5);
    float2 bend = -local * (0.025 + strength * 0.045) * dome
        + normal * bevel * bevel * (3.4 + strength * 5.0) * density;
    float2 uv = coord + bend + float2(wave * 2.5, wave * 3.0) * density;
    float2 fringe = normal * bevel * bevel * 0.24 * density;
    half4 middle = backdrop.eval(clamp(uv, float2(0.0), viewport));
    half3 colour = half3(backdrop.eval(clamp(uv + fringe, float2(0.0), viewport)).r,
        middle.g, backdrop.eval(clamp(uv - fringe, float2(0.0), viewport)).b);

    float rim = 1.0 - smoothstep(0.25 * density, 1.10 * density, edgeDistance);
    float facing = dot(normal, normalize(float2(-0.7, -1.0)));
    float shine = pow(max(facing, 0.0), 2.0);
    float shade = pow(max(-facing, 0.0), 2.0);
    float reflection = pow(max(0.0, 1.0 - abs(unit.x + unit.y * 0.7 + 0.35) * 0.65), 8.0);
    colour = mix(colour, half3(1.0), half((0.018 + strength * 0.035) * reflection));
    colour = mix(colour, half3(1.0), half(rim * shine * (0.42 - dark * 0.14 + strength * 0.12)));
    colour *= half(1.0 - rim * shade * (0.08 + strength * 0.04));
    return mix(background, half4(colour, 1.0), half(coverage));
}
"""
    }
}
