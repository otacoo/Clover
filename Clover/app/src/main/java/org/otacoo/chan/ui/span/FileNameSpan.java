/*
 * Clover - 4chan browser https://github.com/Floens/Clover/
 * Copyright (C) 2014  Floens
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

import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.view.View;

import androidx.annotation.NonNull;

/**
 * Marks a post's filename in the title span as tappable: tapping it copies
 * the filename (name.ext) to the clipboard. Draw state is left untouched so
 * the existing filename styling applies.
 */
public class FileNameSpan extends ClickableSpan {
    public final String filename;

    public FileNameSpan(String filename) {
        this.filename = filename;
    }

    @Override
    public void onClick(@NonNull View widget) {
        // Handled by PostCell's movement method, which needs the span context.
    }

    @Override
    public void updateDrawState(@NonNull TextPaint ds) {
        // Keep the regular filename styling.
    }
}
