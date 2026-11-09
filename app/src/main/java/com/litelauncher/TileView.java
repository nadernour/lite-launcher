package com.litelauncher;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.View;

/** Draws a tile directly on the canvas, so each tile is one view rather than a layout. */
final class TileView extends View {
    private static final float FOCUS_SCALE = 1.08f;

    /** A rendered tile image. Icon-only images need the app name drawn over them. */
    static final class Thumb {
        final Bitmap bitmap;
        final boolean iconOnly;

        Thumb(Bitmap bitmap, boolean iconOnly) {
            this.bitmap = bitmap;
            this.iconOnly = iconOnly;
        }
    }

    /** Values shared by every tile, so tiles don't each carry their own paints. */
    static final class Style {
        final int width;
        final int height;
        final float radius;
        final float ringInset;
        final float labelBaseline;
        final float labelMaxWidth;
        final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint liftedRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);

        Style(Resources res, int width, int height) {
            this.width = width;
            this.height = height;
            DisplayMetrics metrics = res.getDisplayMetrics();
            float density = metrics.density;
            radius = 6 * density;
            float ringWidth = 3 * density;
            ringInset = ringWidth / 2;
            labelBaseline = height - 10 * density;
            labelMaxWidth = width - 16 * density;
            base.setColor(res.getColor(R.color.bg1));
            setUpRing(ring, res.getColor(R.color.yellow), ringWidth);
            setUpRing(liftedRing, res.getColor(R.color.orange), ringWidth);
            label.setColor(res.getColor(R.color.fg));
            label.setTextSize(13 * metrics.scaledDensity);
            label.setTextAlign(Paint.Align.CENTER);
        }

        private static void setUpRing(Paint paint, int color, float strokeWidth) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(strokeWidth);
        }
    }

    private final Style style;
    private final RectF bounds = new RectF();
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private AppEntry entry;
    private CharSequence label;
    private boolean showLabel = true;
    private boolean lifted;

    TileView(Context context, Style style) {
        super(context);
        this.style = style;
        bounds.set(0, 0, style.width, style.height);
    }

    void bind(AppEntry newEntry, Thumb thumb) {
        entry = newEntry;
        label = TextUtils.ellipsize(newEntry.label, style.label, style.labelMaxWidth, TextUtils.TruncateAt.END);
        setThumb(thumb);
    }

    AppEntry entry() {
        return entry;
    }

    /** Marks this tile as the one being moved. */
    void setLifted(boolean newLifted) {
        lifted = newLifted;
        invalidate();
    }

    void setThumb(Thumb thumb) {
        showLabel = thumb == null || thumb.iconOnly;
        if (thumb == null) {
            thumbPaint.setShader(null);
        } else {
            // A shader gives rounded corners without a clip or an offscreen layer.
            Bitmap bitmap = thumb.bitmap;
            BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix scale = new Matrix();
            scale.setScale((float) style.width / bitmap.getWidth(), (float) style.height / bitmap.getHeight());
            shader.setLocalMatrix(scale);
            thumbPaint.setShader(shader);
        }
        invalidate();
    }

    @Override
    public void setSelected(boolean selected) {
        super.setSelected(selected);
        // Applied instantly: no animators to run while a remote key is held down.
        float scale = selected ? FOCUS_SCALE : 1f;
        setScaleX(scale);
        setScaleY(scale);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(style.width, style.height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        boolean hasThumb = thumbPaint.getShader() != null;
        canvas.drawRoundRect(bounds, style.radius, style.radius, hasThumb ? thumbPaint : style.base);
        if (entry != null && showLabel) {
            canvas.drawText(label, 0, label.length(), style.width / 2f, style.labelBaseline, style.label);
        }
        if (isSelected()) {
            float inset = style.ringInset;
            canvas.drawRoundRect(inset, inset, style.width - inset, style.height - inset,
                    style.radius, style.radius, lifted ? style.liftedRing : style.ring);
        }
    }
}
