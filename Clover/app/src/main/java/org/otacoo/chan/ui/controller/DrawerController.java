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

import static org.otacoo.chan.Chan.inject;
import static org.otacoo.chan.ui.theme.ThemeHelper.theme;
import static org.otacoo.chan.utils.AndroidUtils.dp;
import static org.otacoo.chan.utils.AndroidUtils.fixSnackbarText;
import static org.otacoo.chan.utils.AndroidUtils.getAttrColor;

import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.snackbar.Snackbar;

import org.otacoo.chan.R;
import org.otacoo.chan.controller.Controller;
import org.otacoo.chan.controller.NavigationController;
import org.otacoo.chan.core.manager.WatchManager;
import org.otacoo.chan.core.manager.BoardManager;
import org.otacoo.chan.core.repository.SiteRepository;
import org.otacoo.chan.core.model.orm.Pin;
import org.otacoo.chan.core.model.orm.Board;
import org.otacoo.chan.core.model.orm.Loadable;
import org.otacoo.chan.core.site.Site;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.core.site.SiteRegistry;
import org.otacoo.chan.ui.adapter.DrawerAdapter;
import org.otacoo.chan.utils.AndroidUtils;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import de.greenrobot.event.EventBus;

public class DrawerController extends Controller implements DrawerAdapter.Callback {
    protected FrameLayout container;
    protected DrawerLayout drawerLayout;
    protected SwipeRefreshLayout drawer;
    protected RecyclerView recyclerView;
    protected DrawerAdapter drawerAdapter;

    @Inject
    WatchManager watchManager;

    @Inject
    BoardManager boardManager;

    @Inject
    SiteRepository siteRepository;

    public DrawerController(Context context) {
        super(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inject(this);

        EventBus.getDefault().register(this);

        view = inflateRes(R.layout.controller_navigation_drawer);
        container = view.findViewById(R.id.container);
        drawerLayout = view.findViewById(R.id.drawer_layout);
        drawerLayout.setDrawerShadow(R.drawable.drawer_shadow, Gravity.LEFT);
        drawer = view.findViewById(R.id.drawer);
        drawer.setBackgroundColor(getAttrColor(context, R.attr.backcolor));

        drawer.setOnRefreshListener(() -> {
            watchManager.forceUpdate();
            drawer.setRefreshing(false);
        });
        recyclerView = view.findViewById(R.id.drawer_recycler_view);
        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));

        drawerAdapter = new DrawerAdapter(this);
        recyclerView.setAdapter(drawerAdapter);

        drawerAdapter.setPinnedSearches(ChanSettings.getPinnedSearches());
        drawerAdapter.onPinsChanged(watchManager.getAllPins());

        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(drawerAdapter.getItemTouchHelperCallback());
        itemTouchHelper.attachToRecyclerView(recyclerView);

        org.otacoo.chan.core.net.Chan8PowNotifier.setRootView(drawerLayout);

        updateBadge();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        org.otacoo.chan.core.net.Chan8PowNotifier.setRootView(null);
        EventBus.getDefault().unregister(this);
    }

    public void setChildController(Controller childController) {
        addChildController(childController);
        childController.attachToParentView(container);
        childController.onShow();
    }

    public void onMenuClicked() {
        if (getMainToolbarNavigationController().getTop().navigation.hasDrawer) {
            drawerLayout.openDrawer(drawer);
        }
    }

    @Override
    public boolean onBack() {
        if (drawerLayout.isDrawerOpen(drawer)) {
            drawerLayout.closeDrawer(drawer);
            return true;
        } else {
            return super.onBack();
        }
    }

    @Override
    public void onPinClicked(Pin pin) {
        openPin(pin);
        drawerLayout.closeDrawer(drawer);
    }

    @Override
    public void onPinnedSearchClicked(ChanSettings.PinnedSearch search) {
        // Resolve board first (cheap), navigate after.
        // Look up the site by its stable SiteRegistry class ID.
        // siteClassId defaults to 0 (4chan) for entries saved before this field existed.
        List<Site> allSites = siteRepository.all().getAll();
        Class<? extends Site> siteClass = SiteRegistry.SITE_CLASSES.get(search.siteClassId);
        Site site = null;
        if (siteClass != null) {
            for (Site s : allSites) {
                if (s.getClass() == siteClass) { site = s; break; }
            }
        }
        if (site == null) {
            // Fallback: scan all sites for a board with this code
            for (Site s : allSites) {
                if (boardManager.getBoard(s, search.boardCode) != null) { site = s; break; }
            }
        }
        if (site == null) site = allSites.get(0);

        Board board = boardManager.getBoard(site, search.boardCode);
        if (board == null) board = site.board(search.boardCode);

        if (board == null) {
            Toast.makeText(context, "Board /" + search.boardCode + "/ not found", Toast.LENGTH_SHORT).show();
            return;
        }

        final Board finalBoard = board;
        final Loadable loadable = Loadable.forCatalog(finalBoard);
        loadable.searchQuery = Uri.decode(search.searchTerm);

        openPinnedSearch(finalBoard, loadable);
        drawerLayout.closeDrawer(drawer);
    }

    private void openPinnedSearch(Board board, Loadable loadable) {
        Controller top = getTop();
        if (top instanceof DoubleNavigationController) {
            DoubleNavigationController dnc = (DoubleNavigationController) top;
            if (dnc.getLeftController() instanceof BrowseController) {
                BrowseController bc = (BrowseController) dnc.getLeftController();
                bc.setBoard(board);
                bc.loadBoard(loadable);
                dnc.switchToController(true);
            }
        } else if (top instanceof ToolbarNavigationController) {
            ToolbarNavigationController tnc = (ToolbarNavigationController) top;
            if (tnc.getTop() instanceof BrowseController) {
                BrowseController bc = (BrowseController) tnc.getTop();
                bc.setBoard(board);
                bc.loadBoard(loadable);
            } else {
                BrowseController browseController = new BrowseController(context);
                tnc.pushController(browseController);
                browseController.setBoard(board);
                browseController.loadBoard(loadable);
            }
        }
    }

    public void openPin(Pin pin) {
        ThreadController threadController = getTopThreadController();
        if (threadController != null) {
            threadController.openPin(pin);
        }
    }

    @Override
    public void onWatchCountClicked(Pin pin) {
        watchManager.toggleWatch(pin);
    }

    @Override
    public void onHeaderClicked(DrawerAdapter.HeaderHolder holder, DrawerAdapter.HeaderAction headerAction) {
        if (headerAction == DrawerAdapter.HeaderAction.CLEAR || headerAction == DrawerAdapter.HeaderAction.CLEAR_ALL) {
            boolean all = headerAction == DrawerAdapter.HeaderAction.CLEAR_ALL || !ChanSettings.watchEnabled.get();
            final List<Pin> pins = watchManager.clearPins(all);
            if (!pins.isEmpty()) {
                String text = context.getResources().getQuantityString(R.plurals.bookmark, pins.size(), pins.size());
                //noinspection WrongConstant
                Snackbar snackbar = Snackbar.make(drawerLayout, context.getString(R.string.drawer_pins_cleared, text), 4000);
                fixSnackbarText(context, snackbar);
                snackbar.setAction(R.string.undo, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        watchManager.addAll(pins);
                    }
                });
                snackbar.show();
            } else {
                int text = watchManager.getAllPins().isEmpty() ? R.string.drawer_pins_non_cleared : R.string.drawer_pins_non_cleared_try_all;
                Snackbar snackbar = Snackbar.make(drawerLayout, text, Snackbar.LENGTH_LONG);
                fixSnackbarText(context, snackbar);
                snackbar.show();
            }
        } else if (headerAction == DrawerAdapter.HeaderAction.ADD) {
            showAddSearchPinDialog();
        }
    }

    private void showAddSearchPinDialog() {
        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(16), dp(16), dp(16), 0);

        final Spinner boardSpinner = new Spinner(context);
        List<Site> sites = siteRepository.all().getAll();
        if (sites.isEmpty()) return;

        List<Board> boards = new ArrayList<>();
        for (Site site : sites) {
            String siteName = site.name().toLowerCase();
            if (siteName.contains("sushichan") || siteName.contains("lainchan")) {
                continue;
            }

            List<Board> siteSaved = boardManager.getSiteSavedBoards(site);
            if (!siteSaved.isEmpty()) {
                boards.addAll(siteSaved);
            } else {
                boards.addAll(boardManager.getSiteBoards(site));
            }
        }

        final List<Board> finalBoards = boards;
        List<String> boardNames = new ArrayList<>();
        for (Board b : finalBoards) {
            String siteName = b.site != null ? b.site.name() : "Unknown";
            boardNames.add("[" + siteName + "] /" + b.code + "/ - " + b.name);
        }

        int textColor = getAttrColor(context, R.attr.text_color_primary);
        int backColor = getAttrColor(context, R.attr.backcolor);

        wrap.setBackgroundColor(backColor);

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, boardNames) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(textColor);
                return view;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(textColor);
                view.setBackgroundColor(backColor);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        boardSpinner.setAdapter(adapter);
        wrap.addView(boardSpinner, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        final EditText termInput = new EditText(context);
        termInput.setSingleLine();
        termInput.setHint(R.string.pin_search_term_hint);
        termInput.setTextColor(textColor);
        termInput.setHintTextColor(textColor & 0x88ffffff);
        termInput.setBackgroundTintList(ColorStateList.valueOf(getAttrColor(context, R.attr.colorAccent)));
        wrap.addView(termInput, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setPositiveButton(R.string.add, (d, which) -> {
                    int selectedPos = boardSpinner.getSelectedItemPosition();
                    if (selectedPos < 0 || selectedPos >= finalBoards.size()) return;

                    String term = termInput.getText().toString().trim();

                    if (!TextUtils.isEmpty(term)) {
                        Board selectedBoard = finalBoards.get(selectedPos);
                        String encodedTerm = Uri.encode(term);
                        // Store the stable SiteRegistry class ID, not the volatile DB instance id.
                        int classId = 0;
                        if (selectedBoard.site != null) {
                            int idx = SiteRegistry.SITE_CLASSES.indexOfValue(selectedBoard.site.getClass());
                            if (idx >= 0) classId = SiteRegistry.SITE_CLASSES.keyAt(idx);
                        }
                        List<ChanSettings.PinnedSearch> searches = ChanSettings.getPinnedSearches();
                        searches.add(0, new ChanSettings.PinnedSearch(selectedBoard.code, encodedTerm, classId));
                        ChanSettings.savePinnedSearches(searches);
                        drawerAdapter.setPinnedSearches(searches);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .setTitle(R.string.pin_search_title)
                .setView(wrap)
                .create();

        AndroidUtils.requestKeyboardFocus(dialog, termInput);
        dialog.show();

        dialog.getWindow().getDecorView().setBackgroundColor(backColor);
    }

    @Override
    public void onPinRemoved(Pin pin) {
        final Pin undoPin = pin.copy();
        watchManager.deletePin(pin);
        Snackbar snackbar = Snackbar.make(drawerLayout, context.getString(R.string.drawer_pin_removed, pin.loadable.title), Snackbar.LENGTH_LONG);
        fixSnackbarText(context, snackbar);
        snackbar.setAction(R.string.undo, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                watchManager.createPin(undoPin);
            }
        });
        snackbar.show();
    }

    @Override
    public void onPinLongClicked(final Pin pin) {
        LinearLayout wrap = new LinearLayout(context);
        wrap.setPadding(dp(16), dp(16), dp(16), 0);
        final EditText text = new EditText(context);
        text.setSingleLine();
        text.setText(pin.loadable.title);
        wrap.addView(text, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setPositiveButton(R.string.action_rename, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String value = text.getText().toString();

                        if (!TextUtils.isEmpty(value)) {
                            pin.loadable.title = value;
                            watchManager.updatePin(pin);
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .setTitle(R.string.action_rename_pin)
                .setView(wrap)
                .create();

        AndroidUtils.requestKeyboardFocus(dialog, text);

        dialog.show();
    }

    @Override
    public void openSettings() {
        openController(new MainSettingsController(context));
    }

    @Override
    public void openHistory() {
        openController(new HistoryController(context));
    }

    public void setPinHighlighted(Pin pin) {
        drawerAdapter.setPinHighlighted(pin);
        drawerAdapter.updateHighlighted(recyclerView);
    }

    public void onEvent(WatchManager.PinAddedMessage message) {
        drawerAdapter.onPinAdded(message.pin);
        drawerLayout.openDrawer(drawer);
        updateBadge();
    }

    public void onEvent(WatchManager.PinRemovedMessage message) {
        drawerAdapter.onPinRemoved(message.pin);
        updateBadge();
    }

    public void onEvent(WatchManager.PinChangedMessage message) {
        drawerAdapter.onPinChanged(recyclerView, message.pin);
        updateBadge();
    }

    public void onEvent(ChanSettings.SettingChanged<?> message) {
        if (message.setting == ChanSettings.pinnedSearchesEnabled) {
            drawerAdapter.notifyDataSetChanged();
        }
    }

    public void setDrawerEnabled(boolean enabled) {
        drawerLayout.setDrawerLockMode(enabled ? DrawerLayout.LOCK_MODE_UNLOCKED : DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.LEFT);
        if (!enabled) {
            drawerLayout.closeDrawer(drawer);
        }
    }

    private void updateBadge() {
        List<Pin> list = watchManager.getWatchingPins();
        int count = 0;
        boolean color = false;
        for (Pin p : list) {
            count += p.getNewPostCount();
            if (p.getNewQuoteCount() > 0) color = true;
        }

        if (getTop() != null) {
            getMainToolbarNavigationController().toolbar.getArrowMenuDrawable().setBadge(count, color);
        }
    }

    private void openController(Controller controller) {
        Controller top = getTop();
        if (top instanceof NavigationController) {
            ((NavigationController) top).pushController(controller);
        } else if (top instanceof DoubleNavigationController) {
            ((DoubleNavigationController) top).pushController(controller);
        }

        drawerLayout.closeDrawer(Gravity.LEFT);
    }

    private ThreadController getTopThreadController() {
        ToolbarNavigationController nav = getMainToolbarNavigationController();
        if (nav.getTop() instanceof ThreadController) {
            return (ThreadController) nav.getTop();
        } else if (nav.getTop() instanceof ThreadSlideController) {
            ThreadSlideController slideNav = (ThreadSlideController) nav.getTop();
            if (slideNav.leftController instanceof ThreadController) {
                return (ThreadController) slideNav.leftController;
            }
        }

        return null;
    }

    private ToolbarNavigationController getMainToolbarNavigationController() {
        ToolbarNavigationController navigationController = null;

        Controller top = getTop();
        if (top instanceof StyledToolbarNavigationController) {
            navigationController = (StyledToolbarNavigationController) top;
        } else if (top instanceof SplitNavigationController) {
            SplitNavigationController splitNav = (SplitNavigationController) top;
            if (splitNav.getLeftController() instanceof StyledToolbarNavigationController) {
                navigationController = (StyledToolbarNavigationController) splitNav.getLeftController();
            }
        } else if (top instanceof ThreadSlideController) {
            ThreadSlideController slideNav = (ThreadSlideController) top;
            navigationController = (StyledToolbarNavigationController) slideNav.leftController;
        }

        if (navigationController == null) {
            throw new IllegalStateException("The child controller of a DrawerController must either be StyledToolbarNavigationController" +
                    "or an DoubleNavigationController that has a ToolbarNavigationController.");
        }

        return navigationController;
    }
}
