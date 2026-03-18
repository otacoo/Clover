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
package org.otacoo.chan.ui.adapter;

import static org.otacoo.chan.Chan.inject;
import static org.otacoo.chan.ui.theme.ThemeHelper.theme;
import static org.otacoo.chan.utils.AndroidUtils.ROBOTO_MEDIUM;
import static org.otacoo.chan.utils.AndroidUtils.dp;
import static org.otacoo.chan.utils.AndroidUtils.getAttrColor;
import static org.otacoo.chan.utils.AndroidUtils.setRoundItemBackground;
import static org.otacoo.chan.utils.AndroidUtils.sp;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.otacoo.chan.R;
import org.otacoo.chan.core.manager.WatchManager;
import org.otacoo.chan.core.model.orm.Pin;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.ui.helper.PinHelper;
import org.otacoo.chan.ui.helper.PostHelper;
import org.otacoo.chan.ui.view.ThumbnailView;
import org.otacoo.chan.utils.AndroidUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

public class DrawerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public enum HeaderAction {
        CLEAR, CLEAR_ALL, ADD
    }

    private static final int PIN_OFFSET = 2;

    private static final int TYPE_SETTINGS = 0;
    private static final int TYPE_SEARCH_PIN = 1;
    private static final int TYPE_DIVIDER = 2;
    private static final int TYPE_HEADER = 3;
    private static final int TYPE_PIN = 4;

    private static final Comparator<Pin> SORT_PINS = new Comparator<Pin>() {
        @Override
        public int compare(Pin lhs, Pin rhs) {
            return lhs.order - rhs.order;
        }
    };

    @Inject
    WatchManager watchManager;

    private final Callback callback;
    private List<Pin> pins = new ArrayList<>();
    private List<ChanSettings.PinnedSearch> pinnedSearches = new ArrayList<>();
    private Pin highlighted;
    private Pin previousHighlighted;

    @SuppressWarnings("this-escape")
    public DrawerAdapter(Callback callback) {
        inject(this);
        this.callback = callback;
        setHasStableIds(true);
    }

    public void setPinHighlighted(Pin highlighted) {
        this.previousHighlighted = this.highlighted;
        this.highlighted = highlighted;
    }

    public void setPinnedSearches(List<ChanSettings.PinnedSearch> searches) {
        this.pinnedSearches.clear();
        this.pinnedSearches.addAll(searches);
        notifyDataSetChanged();
    }

    public ItemTouchHelper.Callback getItemTouchHelperCallback() {
        return new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                int type = getItemViewType(viewHolder.getBindingAdapterPosition());
                boolean pin = type == TYPE_PIN || type == TYPE_SEARCH_PIN;
                int dragFlags = pin ? ItemTouchHelper.UP | ItemTouchHelper.DOWN : 0;
                int swipeFlags = pin ? ItemTouchHelper.RIGHT | ItemTouchHelper.LEFT : 0;

                return makeMovementFlags(dragFlags, swipeFlags);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int from = viewHolder.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();
                int fromType = getItemViewType(from);
                int toType = getItemViewType(to);

                if (fromType == TYPE_PIN && toType == TYPE_PIN) {
                    int fromIndex = from - getPinOffset();
                    int toIndex = to - getPinOffset();
                    Pin item = pins.remove(fromIndex);
                    pins.add(toIndex, item);
                    notifyItemMoved(from, to);
                    applyOrder();
                    return true;
                } else if (fromType == TYPE_SEARCH_PIN && toType == TYPE_SEARCH_PIN) {
                    int fromIndex = from - getSearchPinOffset();
                    int toIndex = to - getSearchPinOffset();
                    ChanSettings.PinnedSearch item = pinnedSearches.remove(fromIndex);
                    pinnedSearches.add(toIndex, item);
                    notifyItemMoved(from, to);
                    ChanSettings.savePinnedSearches(pinnedSearches);
                    return true;
                }
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int pos = viewHolder.getBindingAdapterPosition();
                int type = getItemViewType(pos);
                if (type == TYPE_PIN) {
                    callback.onPinRemoved(pins.get(pos - getPinOffset()));
                } else if (type == TYPE_SEARCH_PIN) {
                    pinnedSearches.remove(pos - getSearchPinOffset());
                    ChanSettings.savePinnedSearches(pinnedSearches);
                    notifyItemRemoved(pos);
                    if (pinnedSearches.isEmpty()) {
                        notifyDataSetChanged(); // Remove divider if it was the last search pin
                    }
                }
            }
        };
    }

    private int getPinOffset() {
        int offset = ChanSettings.toolbarBottom.get() ? 1 : 2; // HEADER only, or SETTINGS+HEADER
        if (showPinnedSearches() && !pinnedSearches.isEmpty()) {
            offset += pinnedSearches.size();
            offset += 1; // TYPE_DIVIDER
        }
        return offset;
    }

    private int getSearchPinOffset() {
        return ChanSettings.toolbarBottom.get() ? 1 : 2;
    }

    private boolean showPinnedSearches() {
        return ChanSettings.pinnedSearchesEnabled.get();
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        switch (viewType) {
            case TYPE_SETTINGS:
                return new SettingsHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.cell_settings, parent, false));
            case TYPE_HEADER:
                return new HeaderHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.cell_header, parent, false));
            case TYPE_SEARCH_PIN:
                return new SearchPinViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.cell_pin, parent, false));
            case TYPE_DIVIDER:
                return new DividerHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.drawer_divider, parent, false));
            case TYPE_PIN:
                return new PinViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.cell_pin, parent, false));
        }
        return null;
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        switch (holder.getItemViewType()) {
            case TYPE_SETTINGS:
                SettingsHolder settingsHolder = (SettingsHolder) holder;
                theme().settingsDrawable.apply(settingsHolder.imageSettings);
                theme().historyDrawable.apply(settingsHolder.imageHistory);

                break;
            case TYPE_HEADER:
                HeaderHolder headerHolder = (HeaderHolder) holder;
                headerHolder.text.setText(R.string.drawer_pinned);
                theme().clearDrawable.apply(headerHolder.clear);
                headerHolder.add.setVisibility(showPinnedSearches() ? View.VISIBLE : View.GONE);
                theme().pinSearchDrawable.apply(headerHolder.add);

                break;
            case TYPE_SEARCH_PIN:
                SearchPinViewHolder searchPinHolder = (SearchPinViewHolder) holder;
                int searchIndex = position - getSearchPinOffset();
                if (searchIndex >= 0 && searchIndex < pinnedSearches.size()) {
                    ChanSettings.PinnedSearch search = pinnedSearches.get(searchIndex);
                    String displayTerm = Uri.decode(search.searchTerm);
                    searchPinHolder.textView.setText(">>>/" + search.boardCode + "/" + displayTerm);
                    searchPinHolder.image.setVisibility(View.VISIBLE);
                    searchPinHolder.image.setCircular(true);
                    searchPinHolder.image.setImageDrawable(null);

                    String label = "";
                    if (displayTerm != null && displayTerm.length() > 0) {
                        label = displayTerm.substring(0, Math.min(displayTerm.length(), 3)).toLowerCase(Locale.ROOT);
                    } else {
                        label = search.boardCode;
                    }
                    searchPinHolder.image.setLabelText(label);

                    searchPinHolder.watchCountText.setVisibility(View.GONE);
                }
                break;
            case TYPE_DIVIDER:
                break;
            case TYPE_PIN:
                int pinIndex = position - getPinOffset();
                if (pinIndex >= 0 && pinIndex < pins.size()) {
                    final Pin pin = pins.get(pinIndex);
                    PinViewHolder pinHolder = (PinViewHolder) holder;
                    updatePinViewHolder(pinHolder, pin);
                }
                break;
        }
    }

    @Override
    public int getItemCount() {
        int count = ChanSettings.toolbarBottom.get() ? 0 : 1; // SETTINGS only when not bottom toolbar
        count += 1; // HEADER
        if (showPinnedSearches() && !pinnedSearches.isEmpty()) {
            count += pinnedSearches.size();
            count += 1; // DIVIDER
        }
        count += pins.size();
        return count;
    }

    @Override
    public long getItemId(int position) {
        int type = getItemViewType(position);
        if (type == TYPE_PIN) {
            int index = position - getPinOffset();
            if (index >= 0 && index < pins.size()) {
                return pins.get(index).id + 1000;
            }
        } else if (type == TYPE_SEARCH_PIN) {
            int index = position - getSearchPinOffset();
            if (index >= 0 && index < pinnedSearches.size()) {
                return pinnedSearches.get(index).hashCode();
            }
        }
        return position;
    }

    @Override
    public int getItemViewType(int position) {
        if (ChanSettings.toolbarBottom.get()) {
            // Settings row is a fixed footer outside the RecyclerView
            if (position == 0) return TYPE_HEADER;
            int current = 1; // HEADER
            if (showPinnedSearches() && !pinnedSearches.isEmpty()) {
                if (position < current + pinnedSearches.size()) {
                    return TYPE_SEARCH_PIN;
                }
                current += pinnedSearches.size();
                if (position == current) return TYPE_DIVIDER;
                current += 1;
            }
            return TYPE_PIN;
        } else {
            if (position == 0) return TYPE_SETTINGS;
            if (position == 1) return TYPE_HEADER;
            int current = 2; // SETTINGS + HEADER
            if (showPinnedSearches() && !pinnedSearches.isEmpty()) {
                if (position < current + pinnedSearches.size()) {
                    return TYPE_SEARCH_PIN;
                }
                current += pinnedSearches.size();
                if (position == current) return TYPE_DIVIDER;
                current += 1;
            }
            return TYPE_PIN;
        }
    }

    public void onPinsChanged(List<Pin> pins) {
        this.pins.clear();
        this.pins.addAll(pins);
        Collections.sort(this.pins, SORT_PINS);
        notifyDataSetChanged();
    }

    public void onPinAdded(Pin pin) {
        pins.add(pin);
        Collections.sort(pins, SORT_PINS);
        notifyDataSetChanged();
    }

    public void onPinRemoved(Pin pin) {
        pins.remove(pin);
        Collections.sort(pins, SORT_PINS);
        notifyDataSetChanged();
    }

    public void onPinChanged(RecyclerView recyclerView, Pin pin) {
        int index = pins.indexOf(pin);
        if (index != -1) {
            RecyclerView.ViewHolder vh = recyclerView.findViewHolderForAdapterPosition(index + getPinOffset());
            if (vh instanceof PinViewHolder) {
                updatePinViewHolder((PinViewHolder) vh, pin);
            } else {
                notifyItemChanged(index + getPinOffset());
            }
        }
    }

    public void updateHighlighted(RecyclerView recyclerView) {
        int offset = getPinOffset();
        for (int i = 0; i < pins.size(); i++) {
            Pin pin = pins.get(i);
            boolean isTarget = (highlighted != null && pin.id == highlighted.id)
                    || (previousHighlighted != null && pin.id == previousHighlighted.id);
            if (!isTarget) continue;
            RecyclerView.ViewHolder vh = recyclerView.findViewHolderForAdapterPosition(i + offset);
            if (vh instanceof PinViewHolder) {
                updatePinViewHolder((PinViewHolder) vh, pin);
            }
        }
    }

    private void updatePinViewHolder(PinViewHolder holder, Pin pin) {
        CharSequence text = pin.loadable == null ? "" : pin.loadable.title;
        if (pin.isError) {
            text = TextUtils.concat(PostHelper.addIcon(PostHelper.trashIcon, sp(14 + 2)), text);
        } else if (pin.archived) {
            text = TextUtils.concat(PostHelper.addIcon(PostHelper.archivedIcon, sp(14 + 2)), text);
        }

        holder.textView.setText(text);
        holder.image.setUrl(pin.thumbnailUrl, dp(40), dp(40));

        if (ChanSettings.watchEnabled.get()) {
            String count = PinHelper.getShortUnreadCount(pin.getNewPostCount());
            holder.watchCountText.setVisibility(View.VISIBLE);
            holder.watchCountText.setText(count);

            if (!pin.watching) {
                holder.watchCountText.setTextColor(0xff898989); // TODO material colors
            } else if (pin.getNewQuoteCount() > 0) {
                holder.watchCountText.setTextColor(0xffFF4444);
            } else {
                holder.watchCountText.setTextColor(0xff33B5E5);
            }

            // The 16dp padding now belongs to the counter, for a bigger touch area
            holder.textView.setPadding(holder.textView.getPaddingLeft(), holder.textView.getPaddingTop(),
                    0, holder.textView.getPaddingBottom());
            holder.watchCountText.setPadding(dp(16), holder.watchCountText.getPaddingTop(),
                    holder.watchCountText.getPaddingRight(), holder.watchCountText.getPaddingBottom());
        } else {
            // The 16dp padding now belongs to the textview, for better ellipsize
            holder.watchCountText.setVisibility(View.GONE);
            holder.textView.setPadding(holder.textView.getPaddingLeft(), holder.textView.getPaddingTop(),
                    dp(16), holder.textView.getPaddingBottom());
        }

        boolean isHighlighted = this.highlighted != null && pin.id == this.highlighted.id;
        if (isHighlighted && !holder.highlighted) {
            Drawable highlight = new ColorDrawable(0x33888888);
            Drawable ripple = AndroidUtils.getAttrDrawable(holder.itemView.getContext(), android.R.attr.selectableItemBackground);
            holder.itemView.setBackground(new LayerDrawable(new Drawable[]{highlight, ripple}));
            holder.highlighted = true;
        } else if (!isHighlighted && holder.highlighted) {
            holder.itemView.setBackground(AndroidUtils.getAttrDrawable(holder.itemView.getContext(), android.R.attr.selectableItemBackground));
            holder.highlighted = false;
        }
    }

    private void applyOrder() {
        watchManager.reorder(pins);
        notifyDataSetChanged();
    }

    private class SearchPinViewHolder extends RecyclerView.ViewHolder {
        private ThumbnailView image;
        private TextView textView;
        private TextView watchCountText;

        private SearchPinViewHolder(View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.thumb);
            textView = itemView.findViewById(R.id.text);
            textView.setTypeface(ROBOTO_MEDIUM);
            watchCountText = itemView.findViewById(R.id.watch_count);

            itemView.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                int spOffset = getSearchPinOffset();
                if (pos >= spOffset && pos < spOffset + pinnedSearches.size()) {
                    callback.onPinnedSearchClicked(pinnedSearches.get(pos - spOffset));
                }
            });
        }
    }

    private class PinViewHolder extends RecyclerView.ViewHolder {
        private boolean highlighted;
        private ThumbnailView image;
        private TextView textView;
        private TextView watchCountText;

        private PinViewHolder(View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.thumb);
            image.setCircular(true);
            textView = itemView.findViewById(R.id.text);
            textView.setTypeface(ROBOTO_MEDIUM);
            watchCountText = itemView.findViewById(R.id.watch_count);
            watchCountText.setTypeface(ROBOTO_MEDIUM);

            setRoundItemBackground(watchCountText);

            itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int pos = getBindingAdapterPosition();
                    int offset = getPinOffset();
                    if (pos >= offset && pos < offset + pins.size()) {
                        callback.onPinClicked(pins.get(pos - offset));
                    }
                }
            });

            /*itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    int pos = getBindingAdapterPosition();
                    int offset = getPinOffset();
                    if (pos >= offset && pos < offset + pins.size()) {
                        callback.onPinLongClicked(pins.get(pos - offset));
                    }

                    return true;
                }
            });*/

            watchCountText.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int pos = getBindingAdapterPosition();
                    int offset = getPinOffset();
                    if (pos >= offset && pos < offset + pins.size()) {
                        callback.onWatchCountClicked(pins.get(pos - offset));
                    }
                }
            });
        }
    }

    public class HeaderHolder extends RecyclerView.ViewHolder {
        private TextView text;
        private ImageView clear;
        private ImageView add;

        private HeaderHolder(View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.text);
            text.setTypeface(ROBOTO_MEDIUM);
            clear = itemView.findViewById(R.id.clear);
            setRoundItemBackground(clear);
            add = itemView.findViewById(R.id.add);
            setRoundItemBackground(add);

            clear.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    callback.onHeaderClicked(HeaderHolder.this, HeaderAction.CLEAR);
                }
            });
            clear.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    callback.onHeaderClicked(HeaderHolder.this, HeaderAction.CLEAR_ALL);
                    return true;
                }
            });
            add.setOnClickListener(v -> callback.onHeaderClicked(HeaderHolder.this, HeaderAction.ADD));
        }
    }

    private class SettingsHolder extends RecyclerView.ViewHolder {
        private ImageView imageSettings;
        private TextView textSettings;
        private ImageView imageHistory;

        private SettingsHolder(View itemView) {
            super(itemView);
            imageSettings = itemView.findViewById(R.id.image_settings);
            textSettings = itemView.findViewById(R.id.text_settings);
            imageHistory = itemView.findViewById(R.id.image_history);
            textSettings.setTypeface(ROBOTO_MEDIUM);

            itemView.findViewById(R.id.settings).setOnClickListener(v -> callback.openSettings());
            imageHistory.setOnClickListener(v -> callback.openHistory());
        }
    }

    private class BoardInputHolder extends RecyclerView.ViewHolder {
        private EditText input;

        private BoardInputHolder(View itemView) {
            super(itemView);
            input = itemView.findViewById(R.id.input);
        }
    }

    private class DividerHolder extends RecyclerView.ViewHolder {
        private boolean withBackground = false;
        private View divider;

        private DividerHolder(View itemView) {
            super(itemView);
            divider = itemView.findViewById(R.id.divider);
        }

        private void withBackground(boolean withBackground) {
            if (withBackground != this.withBackground) {
                this.withBackground = withBackground;
                if (withBackground) {
                    divider.setBackgroundColor(getAttrColor(itemView.getContext(), R.attr.divider_color));
                } else {
                    divider.setBackgroundColor(0);
                }
            }
        }
    }

    public interface Callback {
        void onPinClicked(Pin pin);

        void onPinnedSearchClicked(ChanSettings.PinnedSearch search);

        void onWatchCountClicked(Pin pin);

        void onHeaderClicked(HeaderHolder holder, HeaderAction headerAction);

        void onPinRemoved(Pin pin);

        void onPinLongClicked(Pin pin);

        void openSettings();

        void openHistory();
    }
}
