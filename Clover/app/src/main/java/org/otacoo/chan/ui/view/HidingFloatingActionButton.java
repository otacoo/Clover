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
import android.view.View;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;

import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.otacoo.chan.ui.toolbar.Toolbar;
import org.otacoo.chan.utils.AndroidUtils;

import de.greenrobot.event.EventBus;

public class HidingFloatingActionButton extends FloatingActionButton implements Toolbar.ToolbarCollapseCallback {
    private boolean attachedToWindow;
    private Toolbar toolbar;
    private boolean attachedToToolbar;
    private CoordinatorLayout coordinatorLayout;
    private int currentCollapseTranslation;
    private boolean snackbarShowing;
    private int desiredVisibility = VISIBLE;

    public HidingFloatingActionButton(Context context) {
        super(context);
    }

    public HidingFloatingActionButton(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public HidingFloatingActionButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setToolbar(Toolbar toolbar) {
        this.toolbar = toolbar;

        if (attachedToWindow && !attachedToToolbar) {
            toolbar.addCollapseCallback(this);
            attachedToToolbar = true;
        }
    }

    @Override
    public void setVisibility(int visibility) {
        desiredVisibility = visibility;
        if (!snackbarShowing) {
            super.setVisibility(visibility);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attachedToWindow = true;
        
        desiredVisibility = getVisibility();

        ViewParent parent = getParent();
        while (parent != null && !(parent instanceof CoordinatorLayout)) {
            parent = parent.getParent();
        }

        if (parent instanceof CoordinatorLayout) {
            coordinatorLayout = (CoordinatorLayout) parent;
        }

        if (toolbar != null && !attachedToToolbar) {
            toolbar.addCollapseCallback(this);
            attachedToToolbar = true;
        }

        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this);
        }
        
        updateSnackbarShowing(AndroidUtils.isAnySnackbarShowing());
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attachedToWindow = false;
        if (attachedToToolbar) {
            toolbar.removeCollapseCallback(this);
            attachedToToolbar = false;
        }
        coordinatorLayout = null;
        
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this);
        }
    }

    public void onEventMainThread(AndroidUtils.SnackbarEvent event) {
        updateSnackbarShowing(event.showing());
    }

    private void updateSnackbarShowing(boolean showing) {
        if (snackbarShowing != showing) {
            snackbarShowing = showing;
            if (showing) {
                hide();
            } else if (desiredVisibility == VISIBLE) {
                show();
            }
        }
    }

    @Override
    public void onCollapseTranslation(float offset) {
        if (snackbarShowing) {
            currentCollapseTranslation = -1;
            return;
        }

        int translation = (int) (getTotalHeight() * offset);
        if (translation != currentCollapseTranslation) {
            currentCollapseTranslation = translation;
            float diff = Math.abs(translation - getTranslationY());
            if (diff >= getHeight()) {
                animate().translationY(translation).setDuration(300).setStartDelay(0).setInterpolator(new DecelerateInterpolator(2f)).start();
            } else {
                setTranslationY(translation);
            }
        }
    }

    @Override
    public void onCollapseAnimation(boolean collapse) {
        if (snackbarShowing) {
            currentCollapseTranslation = -1;
            return;
        }

        int translation = collapse ? getTotalHeight() : 0;
        if (translation != currentCollapseTranslation) {
            currentCollapseTranslation = translation;
            animate().translationY(translation).setDuration(300).setStartDelay(0).setInterpolator(new DecelerateInterpolator(2f)).start();
        }
    }

    private int getTotalHeight() {
        return getHeight() + dp(16); // Fallback to 16dp if layout params are not available or nested
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
