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
package org.otacoo.chan.ui.activity;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static org.otacoo.chan.Chan.inject;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.otacoo.chan.R;
import org.otacoo.chan.controller.Controller;
import org.otacoo.chan.controller.NavigationController;
import org.otacoo.chan.core.database.DatabaseLoadableManager;
import org.otacoo.chan.core.database.DatabaseManager;
import org.otacoo.chan.core.manager.WatchManager;
import org.otacoo.chan.core.model.orm.Board;
import org.otacoo.chan.core.model.orm.Loadable;
import org.otacoo.chan.core.model.orm.Pin;
import org.otacoo.chan.core.repository.SiteRepository;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.core.site.Site;
import org.otacoo.chan.core.site.SiteResolver;
import org.otacoo.chan.core.site.SiteService;
import org.otacoo.chan.ui.controller.BrowseController;
import org.otacoo.chan.ui.controller.DoubleNavigationController;
import org.otacoo.chan.ui.controller.DrawerController;
import org.otacoo.chan.ui.controller.SplitNavigationController;
import org.otacoo.chan.ui.controller.StyledToolbarNavigationController;
import org.otacoo.chan.ui.controller.ThreadSlideController;
import org.otacoo.chan.ui.controller.ViewThreadController;
import org.otacoo.chan.ui.helper.VersionHandler;
import org.otacoo.chan.ui.state.ChanState;
import org.otacoo.chan.ui.theme.ThemeHelper;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.Logger;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

public class StartActivity extends AppCompatActivity implements
        ActivityResultHelper.ActivityResultStarter {
    private static final String TAG = "StartActivity";

    private static final String STATE_KEY = "chan_state";

    private ViewGroup contentView;
    private final List<Controller> stack = new ArrayList<>();

    private DrawerController drawerController;
    private NavigationController mainNavigationController;
    private BrowseController browseController;

    private ImagePickDelegate imagePickDelegate;
    private RuntimePermissionsHelper runtimePermissionsHelper;
    private ActivityResultHelper.ActivityStarterHelper resultHelper;
    private VersionHandler versionHandler;

    private boolean intentMismatchWorkaroundActive = false;

    @Inject
    DatabaseManager databaseManager;

    @Inject
    WatchManager watchManager;

    @Inject
    SiteResolver siteResolver;

    @Inject
    SiteService siteService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        inject(this);

        if (intentMismatchWorkaround()) {
            return;
        }

        ThemeHelper.getInstance().setupContext(this);

        imagePickDelegate = new ImagePickDelegate(this);
        runtimePermissionsHelper = new RuntimePermissionsHelper(this);
        resultHelper = new ActivityResultHelper.ActivityStarterHelper();
        versionHandler = new VersionHandler(this, runtimePermissionsHelper);

        contentView = findViewById(android.R.id.content);

        // Setup base controllers, and decide if to use the split layout for tablets
        drawerController = new DrawerController(this);
        drawerController.onCreate();
        drawerController.onShow();

        setupLayout();

        setContentView(drawerController.view);
        addController(drawerController);

        // Prevent overdraw
        // Do this after setContentView, or the decor creating will reset the background to a default non-null drawable
        getWindow().setBackgroundDrawable(null);

        setupFromStateOrFreshLaunch(savedInstanceState);

        versionHandler.run();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (intentMismatchWorkaround()) {
            return;
        }

        // TODO: clear whole stack?
        stackTop().onHide();
        stackTop().onDestroy();
        stack.clear();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resultHelper.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resultHelper.onPause();
    }

    private void setupFromStateOrFreshLaunch(Bundle savedInstanceState) {
        boolean handled;
        if (savedInstanceState != null) {
            handled = restoreFromSavedState(savedInstanceState);
        } else {
            handled = restoreFromUrl();
        }

        // Not from a state or from a url, launch the setup controller if no boards are set up yet,
        // otherwise load the default saved board.
        if (!handled) {
            restoreFresh();
        }
    }

    private void restoreFresh() {
        if (!siteService.areSitesSetup()) {
            browseController.showSitesNotSetup();
        } else {
            browseController.loadWithDefaultBoard();
        }
    }

    private boolean restoreFromUrl() {
        boolean handled = false;

        final Uri data = getIntent().getData();
        // Start from a url launch.
        if (data != null) {
            final SiteResolver.LoadableResult loadableResult =
                    siteResolver.resolveLoadableForUrl(data.toString());

            if (loadableResult != null) {
                handled = true;

                Loadable loadable = loadableResult.loadable;
                browseController.setBoard(loadable.board);

                if (loadable.isThreadMode()) {
                    browseController.showThread(loadable, false);
                }
            } else {
                new AlertDialog.Builder(this)
                        .setMessage(R.string.open_link_not_matched)
                        .setPositiveButton(R.string.ok, (dialog, which) ->
                                AndroidUtils.openLink(data.toString()))
                        .show();
            }
        }

        return handled;
    }

    private boolean restoreFromSavedState(Bundle savedInstanceState) {
        boolean handled = false;

        // Restore the activity state from the previously saved state.
        ChanState chanState = savedInstanceState.getParcelable(STATE_KEY);
        if (chanState == null) {
            Logger.w(TAG, "savedInstanceState was not null, but no ChanState was found!");
        } else {
            Pair<Loadable, Loadable> boardThreadPair = resolveChanState(chanState);

            if (boardThreadPair.first != null) {
                handled = true;

                browseController.setBoard(boardThreadPair.first.board);

                if (boardThreadPair.second != null) {
                    browseController.showThread(boardThreadPair.second, false);
                }
            }
        }

        return handled;
    }

    private Pair<Loadable, Loadable> resolveChanState(ChanState state) {
        Loadable boardLoadable = resolveLoadable(state.board, false);
        Loadable threadLoadable = resolveLoadable(state.thread, true);

        return new Pair<>(boardLoadable, threadLoadable);
    }

    private Loadable resolveLoadable(Loadable stateLoadable, boolean forThread) {
        // invalid (no state saved).
        if (stateLoadable.mode != (forThread ? Loadable.Mode.THREAD : Loadable.Mode.CATALOG)) {
            return null;
        }

        Site site = SiteRepository.forId(stateLoadable.siteId);
        if (site != null) {
            Board board = site.board(stateLoadable.boardCode);
            if (board != null) {
                stateLoadable.site = site;
                stateLoadable.board = board;

                if (forThread) {
                    // When restarting the parcelable isn't actually deserialized, but the same
                    // object instance is reused. This means that the loadable we gave to the
                    // state are the same instance, and also have the id set etc. We don't need to
                    // query these from the loadable manager.
                    DatabaseLoadableManager loadableManager =
                            databaseManager.getDatabaseLoadableManager();
                    if (stateLoadable.id == 0) {
                        stateLoadable = loadableManager.get(stateLoadable);
                    }
                }

                return stateLoadable;
            }
        }

        return null;
    }

    @SuppressLint("InflateParams")
    private void setupLayout() {
        mainNavigationController = new StyledToolbarNavigationController(this);

        ChanSettings.LayoutMode layoutMode = ChanSettings.layoutMode.get();
        if (layoutMode == ChanSettings.LayoutMode.AUTO) {
            if (AndroidUtils.isTablet(this)) {
                layoutMode = ChanSettings.LayoutMode.SPLIT;
            } else {
                layoutMode = ChanSettings.LayoutMode.SLIDE;
            }
        }

        switch (layoutMode) {
            case SPLIT:
                SplitNavigationController split = new SplitNavigationController(this);
                split.setEmptyView((ViewGroup) LayoutInflater.from(this).inflate(R.layout.layout_split_empty, null));

                drawerController.setChildController(split);

                split.setLeftController(mainNavigationController);
                break;
            case PHONE:
            case SLIDE:
                drawerController.setChildController(mainNavigationController);
                break;
        }

        browseController = new BrowseController(this);

        if (layoutMode == ChanSettings.LayoutMode.SLIDE) {
            ThreadSlideController slideController = new ThreadSlideController(this);
            slideController.setEmptyView((ViewGroup) LayoutInflater.from(this).inflate(R.layout.layout_split_empty, null));
            mainNavigationController.pushController(slideController, false);
            slideController.setLeftController(browseController);
        } else {
            mainNavigationController.pushController(browseController, false);
        }
    }

    public void restart() {
        Intent intent = getIntent();
        finish();
        startActivity(intent);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        // Handle notification clicks
        if (intent.getExtras() != null) {
            int pinId = intent.getExtras().getInt("pin_id", -2);
            if (pinId != -2) {
                if (pinId == -1) {
                    drawerController.onMenuClicked();
                } else {
                    Pin pin = watchManager.findPinById(pinId);
                    if (pin != null) {
                        drawerController.openPin(pin);
                    }
                }
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_DOWN) {
            drawerController.onMenuClicked();
            return true;
        }

        return stackTop().dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        Loadable board = browseController.getLoadable();
        if (board == null) {
            Logger.w(TAG, "Can not save instance state, the board loadable is null");
        } else {
            Loadable thread = null;

            if (drawerController.childControllers.get(0) instanceof SplitNavigationController) {
                SplitNavigationController doubleNav = (SplitNavigationController) drawerController.childControllers.get(0);
                if (doubleNav.getRightController() instanceof NavigationController) {
                    NavigationController rightNavigationController = (NavigationController) doubleNav.getRightController();
                    for (Controller controller : rightNavigationController.childControllers) {
                        if (controller instanceof ViewThreadController) {
                            thread = ((ViewThreadController) controller).getLoadable();
                            break;
                        }
                    }

                }
            } else {
                List<Controller> controllers = mainNavigationController.childControllers;
                for (Controller controller : controllers) {
                    if (controller instanceof ViewThreadController) {
                        thread = ((ViewThreadController) controller).getLoadable();
                        break;
                    } else if (controller instanceof ThreadSlideController) {
                        ThreadSlideController slideNav = (ThreadSlideController) controller;
                        if (slideNav.getRightController() instanceof ViewThreadController) {
                            thread = ((ViewThreadController) slideNav.getRightController()).getLoadable();
                            break;
                        }
                    }
                }
            }

            if (thread == null) {
                // Make the parcel happy
                thread = Loadable.emptyLoadable();
            }

            outState.putParcelable(STATE_KEY, new ChanState(board.copy(), thread.copy()));
        }
    }

    public void addController(Controller controller) {
        stack.add(controller);
    }

    public boolean isControllerAdded(Controller.ControllerPredicate predicate) {
        for (Controller controller : stack) {
            if (predicate.test(controller)) {
                return true;
            }
        }

        return false;
    }

    public void removeController(Controller controller) {
        stack.remove(controller);
    }

    public ViewGroup getContentView() {
        return contentView;
    }

    public ImagePickDelegate getImagePickDelegate() {
        return imagePickDelegate;
    }

    public VersionHandler getVersionHandler() {
        return versionHandler;
    }

    public RuntimePermissionsHelper getRuntimePermissionsHelper() {
        return runtimePermissionsHelper;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        for (Controller controller : stack) {
            controller.onConfigurationChanged(newConfig);
        }
    }

    @Override
    public void onBackPressed() {
        if (!stackTop().onBack()) {
            if (ChanSettings.confirmExit.get()) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.action_confirm_exit_title)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.exit, (dialog, which) -> StartActivity.super.onBackPressed())
                        .show();
            } else {
                super.onBackPressed();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        runtimePermissionsHelper.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public void startActivityForResultWithCallback(
            Intent intent, int requestCode, ActivityResultHelper.ActivityResultCallback callback) {
        resultHelper.startActivityForResult(this, intent, requestCode, callback);
    }

    @Override
    public boolean isActivityResumed() {
        return resultHelper.isActivityResumed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        resultHelper.onActivityResult(requestCode, resultCode, data);

        // TODO: move to resultHelper.
        imagePickDelegate.onActivityResult(requestCode, resultCode, data);

        // Go through the controller stack.
        drawerController.onActivityResult(requestCode, resultCode, data);
    }

    private Controller stackTop() {
        return stack.get(stack.size() - 1);
    }

    private boolean intentMismatchWorkaround() {
        // Workaround for an intent mismatch that causes a new activity instance to be started
        // every time the app is launched from the launcher.
        // See https://issuetracker.google.com/issues/36907463
        if (intentMismatchWorkaroundActive) {
            return true;
        }

        if (!isTaskRoot()) {
            Intent intent = getIntent();
            if (intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
                    Intent.ACTION_MAIN.equals(intent.getAction())) {
                Logger.w(TAG, "Workaround for intent mismatch.");
                intentMismatchWorkaroundActive = true;
                finish();
                return true;
            }
        }
        return false;
    }

    // This method is called to apply new imported settings as well as when a site is deleted.
    // It is a hack but it works.
    // The other restart() method does not work for this case, so I'm using this one instead
    public void restartApp() {
        Intent intent = new Intent(this, BoardActivity.class);
        intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();

        Runtime.getRuntime().exit(0);
    }
}
