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
import static org.otacoo.chan.utils.AndroidUtils.getAttrColor;
import static org.otacoo.chan.utils.AndroidUtils.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.otacoo.chan.R;
import org.otacoo.chan.controller.Controller;
import org.otacoo.chan.core.database.DatabaseManager;
import org.otacoo.chan.core.manager.FilterEngine;
import org.otacoo.chan.core.manager.FilterType;
import org.otacoo.chan.core.model.orm.Filter;
import org.otacoo.chan.ui.helper.RefreshUIMessage;
import org.otacoo.chan.ui.layout.FilterLayout;
import org.otacoo.chan.ui.toolbar.ToolbarMenuItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import de.greenrobot.event.EventBus;

public class FiltersController extends Controller implements
        ToolbarNavigationController.ToolbarSearchCallback,
        View.OnClickListener {

    @Inject
    DatabaseManager databaseManager;

    @Inject
    FilterEngine filterEngine;

    private Button enableButton;
    private FilterAdapter adapter;
    private ItemTouchHelper itemTouchHelper;
    private boolean locked;

    public FiltersController(Context context) {
        super(context);
    }

    public static String filterTypeName(FilterType type) {
        return switch (type) {
            case TRIPCODE -> getString(R.string.filter_tripcode);
            case NAME -> getString(R.string.filter_name);
            case COMMENT -> getString(R.string.filter_comment);
            case ID -> getString(R.string.filter_id);
            case SUBJECT -> getString(R.string.filter_subject);
            case FILENAME -> getString(R.string.filter_filename);
            case COUNTRY -> getString(R.string.filter_country);
        };
    }

    public static String actionName(FilterEngine.FilterAction action) {
        return switch (action) {
            case HIDE -> getString(R.string.filter_hide);
            case COLOR -> getString(R.string.filter_color);
            case REMOVE -> getString(R.string.filter_remove);
        };
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inject(this);

        navigation.setTitle(R.string.filters_screen);

        navigation.buildMenu()
                .withItem(R.drawable.ic_search_white_24dp, this::searchClicked)
                .build();

        view = inflateRes(R.layout.controller_filters);

        RecyclerView recyclerView = view.findViewById(R.id.recycler_view);
        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));

        Button addButton = view.findViewById(R.id.add_button);
        addButton.setOnClickListener(this);

        enableButton = view.findViewById(R.id.enable_button);
        enableButton.setOnClickListener(this);

        adapter = new FilterAdapter();
        recyclerView.setAdapter(adapter);

        itemTouchHelper = new ItemTouchHelper(touchHelperCallback);
        itemTouchHelper.attachToRecyclerView(recyclerView);

        adapter.load();
        updateEnableButton();
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.add_button) {
            showFilterDialog(new Filter());
        } else if (v.getId() == R.id.enable_button && !locked) {
            locked = true;
            List<Filter> enabledFilters = filterEngine.getEnabledFilters();
            List<Filter> allFilters = filterEngine.getAllFilters();
            setFilters(allFilters, enabledFilters.isEmpty());
        }
    }

    private void updateEnableButton() {
        List<Filter> enabledFilters = filterEngine.getEnabledFilters();
        if (enabledFilters.isEmpty()) {
            enableButton.setText(R.string.filter_enable_all);
        } else {
            enableButton.setText(R.string.filter_disable_all);
        }
    }

    private void setFilters(List<Filter> filters, boolean enabled) {
        for (Filter filter : filters) {
            filter.enabled = enabled;
            filterEngine.createOrUpdateFilter(filter);
        }
        adapter.load();
        updateEnableButton();
    }

    private void searchClicked(ToolbarMenuItem item) {
        ((ToolbarNavigationController) navigationController).showSearch();
    }

    public void showFilterDialog(final Filter filter) {
        @SuppressLint("InflateParams")
        final ScrollView root = (ScrollView) LayoutInflater.from(context).inflate(R.layout.layout_filter, null);
        final FilterLayout filterLayout = (FilterLayout) root.getChildAt(0);

        final AlertDialog alertDialog = new AlertDialog.Builder(context)
                .setView(root)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    filterEngine.createOrUpdateFilter(filterLayout.getFilter());
                    updateEnableButton();
                    EventBus.getDefault().post(new RefreshUIMessage("filters"));
                    adapter.load();
                })
                .setNegativeButton(R.string.delete, (dialog, which) -> {
                    if (filter.id != 0) {
                        deleteFilter(filter);
                    }
                })
                .show();

        filterLayout.setCallback(enabled -> alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(enabled));

        filterLayout.setFilter(filter);
    }

    private void deleteFilter(Filter filter) {
        filterEngine.deleteFilter(filter);
        updateEnableButton();
        EventBus.getDefault().post(new RefreshUIMessage("filters"));
        adapter.load();
    }

    @Override
    public void onSearchVisibilityChanged(boolean visible) {
        if (!visible) {
            adapter.search(null);
        }
    }

    @Override
    public void onSearchEntered(String entered) {
        adapter.search(entered);
    }

    private final ItemTouchHelper.SimpleCallback touchHelperCallback = new ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.LEFT) {
        private boolean moved = false;

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
            int from = viewHolder.getBindingAdapterPosition();
            int to = target.getBindingAdapterPosition();
            if (from != RecyclerView.NO_POSITION && to != RecyclerView.NO_POSITION) {
                adapter.onItemMove(from, to);
                moved = true;
            }
            return true;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            int position = viewHolder.getBindingAdapterPosition();
            if (position >= 0 && position < adapter.displayList.size()) {
                Filter filter = adapter.displayList.remove(position);
                adapter.sourceList.remove(filter);
                adapter.notifyItemRemoved(position);
                adapter.notifyItemRangeChanged(position, adapter.getItemCount() - position);

                databaseManager.runTaskAsync(databaseManager.getDatabaseFilterManager().deleteFilter(filter), result -> {
                    updateEnableButton();
                    EventBus.getDefault().post(new RefreshUIMessage("filters"));
                });
            }
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            if (moved) {
                moved = false;
                adapter.saveOrders();
            }
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return false;
        }
    };

    private class FilterAdapter extends RecyclerView.Adapter<FilterCell> {
        private final List<Filter> sourceList = new ArrayList<>();
        private final List<Filter> displayList = new ArrayList<>();
        private String searchQuery;

        public FilterAdapter() {
            setHasStableIds(true);
        }

        @NonNull
        @Override
        public FilterCell onCreateViewHolder(ViewGroup parent, int viewType) {
            return new FilterCell(LayoutInflater.from(parent.getContext()).inflate(R.layout.cell_filter, parent, false));
        }

        @Override
        public void onBindViewHolder(FilterCell holder, int position) {
            Filter filter = displayList.get(position);
            holder.text.setText(filter.pattern);
            holder.text.setTextColor(getAttrColor(context, filter.enabled ? R.attr.text_color_primary : R.attr.text_color_hint));
            holder.subtext.setTextColor(getAttrColor(context, filter.enabled ? R.attr.text_color_secondary : R.attr.text_color_hint));
            int types = FilterType.forFlags(filter.type).size();
            String subText = context.getResources().getQuantityString(R.plurals.type, types, types);

            subText += " – ";
            if (filter.allBoards) {
                subText += context.getString(R.string.filter_summary_all_boards);
            } else {
                int size = filterEngine.getFilterBoardCount(filter);
                subText += context.getResources().getQuantityString(R.plurals.board, size, size);
            }

            subText += " – " + FiltersController.actionName(FilterEngine.FilterAction.forId(filter.action));

            holder.subtext.setText(subText);
        }

        @Override
        public int getItemCount() {
            return displayList.size();
        }

        @Override
        public long getItemId(int position) {
            return displayList.get(position).id;
        }

        public void search(String query) {
            this.searchQuery = query;
            filter();
        }

        private void load() {
            sourceList.clear();
            sourceList.addAll(filterEngine.getAllFilters());

            filter();
        }

        @SuppressLint("NotifyDataSetChanged")
        private void filter() {
            displayList.clear();
            if (!TextUtils.isEmpty(searchQuery)) {
                String query = searchQuery.toLowerCase(Locale.ENGLISH);
                for (Filter filter : sourceList) {
                    if (filter.pattern.toLowerCase().contains(query)) {
                        displayList.add(filter);
                    }
                }
            } else {
                displayList.addAll(sourceList);
            }

            notifyDataSetChanged();
            locked = false;
        }

        public void onItemMove(int fromPosition, int toPosition) {
            if (fromPosition < toPosition) {
                for (int i = fromPosition; i < toPosition; i++) {
                    Collections.swap(displayList, i, i + 1);
                }
            } else {
                for (int i = fromPosition; i > toPosition; i--) {
                    Collections.swap(displayList, i, i - 1);
                }
            }
            notifyItemMoved(fromPosition, toPosition);
        }

        public void saveOrders() {
            for (int i = 0; i < displayList.size(); i++) {
                Filter filter = displayList.get(i);
                filter.order = i;
                filterEngine.createOrUpdateFilter(filter);
            }
            load();
        }
    }

    private class FilterCell extends RecyclerView.ViewHolder implements View.OnClickListener, View.OnTouchListener {
        private final TextView text;
        private final TextView subtext;
        private final ImageView dragHandle;

        @SuppressLint("ClickableViewAccessibility")
        public FilterCell(View itemView) {
            super(itemView);

            text = itemView.findViewById(R.id.text);
            subtext = itemView.findViewById(R.id.subtext);
            dragHandle = itemView.findViewById(R.id.drag_handle);

            Drawable drawable = ContextCompat.getDrawable(context, R.drawable.ic_reorder_black_24dp);
            if (drawable != null) {
                drawable = drawable.mutate();
                DrawableCompat.setTint(drawable, getAttrColor(context, R.attr.text_color_hint));
                dragHandle.setImageDrawable(drawable);
            }

            dragHandle.setOnTouchListener(this);

            itemView.setOnClickListener(this);
        }

        @Override
        public void onClick(View v) {
            int position = getBindingAdapterPosition();
            if (!locked && position >= 0 && position < adapter.getItemCount()) {
                Filter filter = adapter.displayList.get(position);
                if (v == itemView) {
                    showFilterDialog(filter);
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (v == dragHandle && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (TextUtils.isEmpty(adapter.searchQuery)) {
                    itemTouchHelper.startDrag(this);
                }
                return true;
            }
            return false;
        }
    }
}
