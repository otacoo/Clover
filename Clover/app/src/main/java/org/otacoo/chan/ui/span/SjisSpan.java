/*
 * Clover - 4chan browser https://github.com/otacoo/Clover/
 * Copyright (C) 2026  otacoo
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.otacoo.chan.ui.span;

import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;

import androidx.annotation.NonNull;

import org.otacoo.chan.utils.AndroidUtils;

public class SjisSpan extends MetricAffectingSpan {
    private static Typeface sjisTypeface;

    public SjisSpan() {
    }

    @Override
    public void updateDrawState(TextPaint ds) {
        apply(ds);
    }

    @Override
    public void updateMeasureState(@NonNull TextPaint paint) {
        apply(paint);
    }

    private void apply(TextPaint paint) {
        if (sjisTypeface == null) {
            try {
                sjisTypeface = Typeface.createFromAsset(AndroidUtils.getAppContext().getAssets(), "font/submona.ttf");
            } catch (Exception e) {
                sjisTypeface = Typeface.MONOSPACE;
            }
        }

        paint.setTypeface(sjisTypeface);
        // SJIS art is designed for a specific font size (usually 12pt/16px)
        // We set it to a fixed size to help with the "broken" layout
        paint.setTextSize(AndroidUtils.sp(12f));
    }
}
