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
package org.otacoo.chan.ui.layout;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.otacoo.chan.R;
import org.otacoo.chan.core.presenter.BoardSetupPresenter;

public class BoardAddLayout extends LinearLayout implements SearchLayout.SearchLayoutCallback, BoardSetupPresenter.AddCallback, View.OnClickListener {
    private BoardSetupPresenter presenter;

    private SuggestionsAdapter suggestionsAdapter;

    private SearchLayout search;
    private RecyclerView suggestionsRecycler;
    private TextView hintText;
    private Button selectAllButton; // top-row button for non-Chan8 sites

    // manual entry UI for Chan8
    private View manualContainer;
    private android.widget.EditText manualName;
    private android.widget.EditText manualDescription;
    private Button addBoardButton;

    private AlertDialog dialog;

    public BoardAddLayout(Context context) {
        this(context, null);
    }

    public BoardAddLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BoardAddLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        // View binding
        search = findViewById(R.id.search);
        suggestionsRecycler = findViewById(R.id.suggestions);
        hintText = findViewById(R.id.board_add_hint);
        selectAllButton = findViewById(R.id.select_all);

        manualContainer = findViewById(R.id.manual_container);
        manualName = findViewById(R.id.manual_board_name);
        manualDescription = findViewById(R.id.manual_board_desc);
        addBoardButton = findViewById(R.id.add_board);

        // Adapters
        suggestionsAdapter = new SuggestionsAdapter();

        // View setup
        search.setCallback(this);

        addBoardButton.setOnClickListener(this);
        selectAllButton.setOnClickListener(v -> presenter.onSelectAllClicked());

        suggestionsRecycler.setLayoutManager(new LinearLayoutManager(getContext()));
        suggestionsRecycler.setAdapter(suggestionsAdapter);

        suggestionsRecycler.requestFocus();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        presenter.bindAddDialog(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        presenter.unbindAddDialog();
    }

    @Override
    public void onClick(View v) {
        if (v == addBoardButton) {
            String code = manualName.getText().toString().trim();
            String desc = manualDescription.getText().toString().trim();
            if (!code.isEmpty()) {
                // validate alphanumeric only
                if (!code.matches("[A-Za-z0-9]+")) {
                    Toast.makeText(getContext(), "Board code may only contain letters and numbers", Toast.LENGTH_SHORT).show();
                    manualName.setText("");
                    manualDescription.setText("");
                } else {
                    presenter.addManualBoard(code, desc);
                    // clear inputs for next entry
                    manualName.setText("");
                    manualDescription.setText("");
                }
            }
        }
    }

    @Override
    public void onSearchEntered(String entered) {
        presenter.searchEntered(entered);
    }

    @Override
    public void suggestionsWereChanged() {
        suggestionsAdapter.notifyDataSetChanged();
    }

    public void setPresenter(BoardSetupPresenter presenter) {
        this.presenter = presenter;

        boolean custom = presenter.allowCustomBoardCode();
        // toggle manual vs search UI
        manualContainer.setVisibility(custom ? VISIBLE : GONE);
        View searchContainer = findViewById(R.id.search_container);
        searchContainer.setVisibility(custom ? GONE : VISIBLE);
        selectAllButton.setVisibility(custom ? GONE : VISIBLE);

        if (custom) {
            manualName.setText("");
            manualDescription.setText("");
        }

        // Show a hint for sites where boards can't be listed (e.g. INFINITE).
        String hint = presenter.getAddDialogHint();
        if (hint != null && !custom) {
            hintText.setText(hint);
            hintText.setVisibility(VISIBLE);
            suggestionsRecycler.setVisibility(GONE);
            setMinimumHeight(0);
            setMinimumWidth(0);
        } else {
            hintText.setVisibility(GONE);
            suggestionsRecycler.setVisibility(VISIBLE);
        }
    }

    public void setDialog(AlertDialog dialog) {
        this.dialog = dialog;
    }

    private void onSuggestionClicked(BoardSetupPresenter.BoardSuggestion suggestion) {
        presenter.onSuggestionClicked(suggestion);
    }

    public void onPositiveClicked() {
        presenter.onAddDialogPositiveClicked();
    }

    private class SuggestionsAdapter extends RecyclerView.Adapter<SuggestionCell> {
        public SuggestionsAdapter() {
            setHasStableIds(true);
        }

        @Override
        public long getItemId(int position) {
            return presenter.getSuggestions().get(position).getId();
        }

        @Override
        public int getItemCount() {
            return presenter.getSuggestions().size();
        }

        @Override
        public SuggestionCell onCreateViewHolder(ViewGroup parent, int viewType) {
            return new SuggestionCell(
                    LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.cell_board_suggestion, parent, false));
        }

        @Override
        public void onBindViewHolder(SuggestionCell holder, int position) {
            BoardSetupPresenter.BoardSuggestion boardSuggestion = presenter.getSuggestions().get(position);
            holder.setSuggestion(boardSuggestion);
        if (!boardSuggestion.hasBoard()) {
            // manual entry
            String desc = boardSuggestion.getDescription();
            holder.text.setText(boardSuggestion.getName()
                    + (desc != null && !desc.isEmpty() ? " - " + desc : ""));
            holder.description.setVisibility(View.GONE);
        } else {
            holder.text.setText(boardSuggestion.getName());
            String desc = boardSuggestion.getDescription();
            if (desc != null && !desc.isEmpty()) {
                holder.description.setVisibility(View.VISIBLE);
                holder.description.setText(desc);
            } else {
                holder.description.setVisibility(View.GONE);
            }
        }
        }
    }

    private class SuggestionCell extends RecyclerView.ViewHolder implements OnClickListener, CompoundButton.OnCheckedChangeListener {
        private TextView text;
        private TextView description;
        private CheckBox check;

        private BoardSetupPresenter.BoardSuggestion suggestion;

        private boolean ignoreCheckChange = false;

        public SuggestionCell(View itemView) {
            super(itemView);

            text = itemView.findViewById(R.id.text);
            description = itemView.findViewById(R.id.description);
            check = itemView.findViewById(R.id.check);
            check.setOnCheckedChangeListener(this);

            itemView.setOnClickListener(this);
        }

        public void setSuggestion(BoardSetupPresenter.BoardSuggestion suggestion) {
            this.suggestion = suggestion;
            ignoreCheckChange = true;
            check.setChecked(suggestion.isChecked());
            ignoreCheckChange = false;
        }

        @Override
        public void onClick(View v) {
            if (v == itemView) {
                toggle();
            }
        }

        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (!ignoreCheckChange && buttonView == check) {
                toggle();
            }
        }

        private void toggle() {
            onSuggestionClicked(suggestion);
            ignoreCheckChange = true;
            check.setChecked(suggestion.isChecked());
            ignoreCheckChange = false;
        }
    }
}
