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
package org.otacoo.chan.ui.controller;

import static org.otacoo.chan.utils.AndroidUtils.getAttrColor;

import android.content.Context;

import org.otacoo.chan.R;
import org.otacoo.chan.controller.Controller;
import org.otacoo.chan.controller.ControllerTransition;
import org.otacoo.chan.controller.ui.NavigationControllerContainerLayout;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.ui.theme.ThemeHelper;

public class StyledToolbarNavigationController extends ToolbarNavigationController {
    public StyledToolbarNavigationController(Context context) {
        super(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        view = inflateRes(R.layout.controller_navigation_toolbar);
        view.setBackgroundColor(getAttrColor(context, R.attr.backcolor));

        container = (NavigationControllerContainerLayout) view.findViewById(R.id.container);
        NavigationControllerContainerLayout nav = (NavigationControllerContainerLayout) container;
        nav.setNavigationController(this);
        nav.setSwipeEnabled(ChanSettings.controllerSwipeable.get());
        toolbar = view.findViewById(R.id.toolbar);
        toolbar.setBackgroundColor(ThemeHelper.getInstance().getTheme().primaryColor.color);
        toolbar.setCallback(this);

        // Optional bottom toolbar repositioning
        if (ChanSettings.toolbarBottom.get()) {
            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) toolbar.getLayoutParams();
            lp.gravity = android.view.Gravity.BOTTOM;
            toolbar.setLayoutParams(lp);
            toolbar.setElevation(0f);
            toolbar.setAtBottom(true);
        }
    }

    @Override
    public boolean popController(ControllerTransition controllerTransition) {
        return !toolbar.isTransitioning() && super.popController(controllerTransition);

    }

    @Override
    public boolean pushController(Controller to, ControllerTransition controllerTransition) {
        return !toolbar.isTransitioning() && super.pushController(to, controllerTransition);
    }

    @Override
    public void transition(Controller from, Controller to, boolean pushing, ControllerTransition controllerTransition) {
        super.transition(from, to, pushing, controllerTransition);

        if (to != null) {
            DrawerController drawerController = getDrawerController();
            if (drawerController != null) {
                drawerController.setDrawerEnabled(to.navigation.hasDrawer);
            }
        }
    }

    @Override
    public void endSwipeTransition(Controller from, Controller to, boolean finish) {
        super.endSwipeTransition(from, to, finish);

        if (finish) {
            DrawerController drawerController = getDrawerController();
            if (drawerController != null) {
                drawerController.setDrawerEnabled(to.navigation.hasDrawer);
            }
        }
    }

    @Override
    public boolean onBack() {
        if (super.onBack()) {
            return true;
        } else if (parentController instanceof PopupController && childControllers.size() == 1) {
            ((PopupController) parentController).dismiss();
            return true;
        } else if (doubleNavigationController != null && childControllers.size() == 1) {
            if (doubleNavigationController.getRightController() == this) {
                doubleNavigationController.setRightController(null);
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    @Override
    public void onMenuClicked() {
        DrawerController drawerController = getDrawerController();
        if (drawerController != null) {
            drawerController.onMenuClicked();
        }
    }

    private DrawerController getDrawerController() {
        if (parentController instanceof DrawerController) {
            return (DrawerController) parentController;
        } else if (doubleNavigationController != null) {
            Controller doubleNav = (Controller) doubleNavigationController;
            if (doubleNav.parentController instanceof DrawerController) {
                return (DrawerController) doubleNav.parentController;
            }
        }
        return null;
    }
}
