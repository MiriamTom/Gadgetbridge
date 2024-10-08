package nodomain.freeyourgadget.gadgetbridge.activities.dashboard;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.TypedValue;
import android.widget.ImageView;

import androidx.annotation.ColorInt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;

public class GaugeDrawer {
    private static final Logger LOG = LoggerFactory.getLogger(GaugeDrawer.class);
    protected @ColorInt int color_unknown = Color.argb(25, 128, 128, 128);

    /**
     * Draw a simple gauge.
     *
     * @param color     the gauge color
     * @param value     the gauge value. Range: [0, 1]
     */
    public void drawSimpleGauge(ImageView gaugeBar, final int color,
                                   final float value) {

        final int width = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                150,
                GBApplication.getContext().getResources().getDisplayMetrics()
        );

        // Draw gauge
        gaugeBar.setImageBitmap(drawSimpleGaugeInternal(
                width,
                Math.round(width * 0.075f),
                color,
                value
        ));
    }

    /**
     * @param width        Bitmap width in pixels
     * @param barWidth     Gauge bar width in pixels
     * @param filledColor  Color of the filled part of the gauge
     * @param filledFactor Factor between 0 and 1 that determines the amount of the gauge that should be filled
     * @return Bitmap containing the gauge
     */
    private Bitmap drawSimpleGaugeInternal(final int width, final int barWidth, @ColorInt final int filledColor, final float filledFactor) {
        final int height = width / 2;
        final int barMargin = (int) Math.ceil(barWidth / 2f);

        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(barWidth * 0.75f);
        paint.setColor(color_unknown);
        canvas.drawArc(barMargin, barMargin, width - barMargin, width - barMargin, 180 + 180 * filledFactor, 180 - 180 * filledFactor, false, paint);

        if (filledFactor >= 0) {
            paint.setStrokeWidth(barWidth);
            paint.setColor(filledColor);
            canvas.drawArc(barMargin, barMargin, width - barMargin, width - barMargin, 180, 180 * filledFactor, false, paint);
        }

        return bitmap;
    }

    /**
     * Draws a segmented gauge.
     *
     * @param colors             the colors of each segment
     * @param segments           the size of each segment. The sum of all segments should be 1
     * @param value              the gauge value, in range [0, 1], or -1 for no value and only segments
     * @param fadeOutsideDot     whether to fade out colors outside the dot value
     * @param gapBetweenSegments whether to introduce a small gap between the segments
     */
    public void drawSegmentedGauge(ImageView gaugeBar,
                                      final int[] colors,
                                      final float[] segments,
                                      final float value,
                                      final boolean fadeOutsideDot,
                                      final boolean gapBetweenSegments) {
        if (colors.length != segments.length) {
            LOG.error("Colors length {} differs from segments length {}", colors.length, segments.length);
            return;
        }

        final int width = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                150,
                GBApplication.getContext().getResources().getDisplayMetrics()
        );

        final int barWidth = Math.round(width * 0.075f);

        final int height = width / 2;
        final int barMargin = (int) Math.ceil(barWidth / 2f);

        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeWidth(barWidth);

        final double cornersGapRadians = Math.asin((width * 0.055f) / (double) height);
        final double cornersGapFactor = cornersGapRadians / Math.PI;

        int dotColor = 0;
        float angleSum = 0;
        for (int i = 0; i < segments.length; i++) {
            if (segments[i] == 0) {
                continue;
            }

            paint.setColor(colors[i]);
            paint.setStrokeWidth(barWidth);

            if (value < 0 || (value >= angleSum && value <= angleSum + segments[i])) {
                dotColor = colors[i];
            } else {
                if (fadeOutsideDot) {
                    paint.setColor(colors[i] - 0xB0000000);
                } else {
                    paint.setStrokeWidth(barWidth * 0.75f);
                }
            }

            float startAngleDegrees = 180 + angleSum * 180;
            float sweepAngleDegrees = segments[i] * 180;

            if (value >= 0) {
                // Do not draw to the end if it will be overlapped by the dot
                if (i == 0 && value <= cornersGapFactor) {
                    startAngleDegrees += (float) Math.toDegrees(cornersGapRadians);
                    sweepAngleDegrees -= (float) Math.toDegrees(cornersGapRadians);
                } else if (i == segments.length - 1 && value >= 1 - cornersGapFactor) {
                    sweepAngleDegrees -= (float) Math.toDegrees(cornersGapRadians);
                }
            }

            if (gapBetweenSegments) {
                if (i + 1 < segments.length) {
                    sweepAngleDegrees -= 2;
                }
            }

            canvas.drawArc(
                    barMargin,
                    barMargin,
                    width - barMargin,
                    width - barMargin,
                    startAngleDegrees,
                    sweepAngleDegrees,
                    false,
                    paint
            );
            angleSum += segments[i];
        }

        if (value >= 0) {
            // Prevent the dot from going outside the widget in the extremities
            final float angleRadians = (float) normalize(value, 0, 1, cornersGapRadians, Math.toRadians(180) - cornersGapRadians);

            paint.setColor(Color.TRANSPARENT);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

            // In the corners the circle is slightly offset, so adjust it slightly
            final float widthAdjustment = width * 0.04f * (float) normalize(Math.abs(value - 0.5d), 0, 0.5d);

            final float x = ((width - (barWidth / 2f) - widthAdjustment) / 2f) * (float) Math.cos(angleRadians);
            final float y = (height - (barWidth / 2f)) * (float) Math.sin(angleRadians);

            // Draw hole
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle((width / 2f) - x, height - y, barMargin * 1.6f, paint);

            // Draw dot
            paint.setColor(dotColor);
            paint.setXfermode(null);
            canvas.drawCircle((width / 2f) - x, height - y, barMargin, paint);
        }

        gaugeBar.setImageBitmap(bitmap);
    }

    public static double normalize(final double value, final double min, final double max) {
        return normalize(value, min, max, 0, 1);
    }

    public static double normalize(final double value, final double minSource, final double maxSource, final double minTarget, final double maxTarget) {
        return ((value - minSource) * (maxTarget - minTarget)) / (maxSource - minSource) + minTarget;
    }

}
