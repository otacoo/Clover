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
package org.otacoo.chan.ui.view;

import android.content.Context;
import android.util.AttributeSet;

import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;

public class CustomScaleImageView extends SubsamplingScaleImageView {
    private Callback callback;

    public CustomScaleImageView(Context context) {
        super(context);
    }

    public CustomScaleImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    @Override
    protected void onImageLoaded() {
        super.onImageLoaded();
        if (callback != null) {
            callback.onReady();
        }
    }

    @Override
    protected void onReady() {
        super.onReady();
        if (callback != null) {
            callback.onReady();
        }
    }

    public interface Callback {
        void onReady();
        void onError(boolean wasInitial);
    }
}
