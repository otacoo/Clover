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
package org.otacoo.chan.core.presenter;


import android.widget.Toast;

import org.otacoo.chan.core.manager.BoardManager;
import org.otacoo.chan.core.model.orm.Board;
import org.otacoo.chan.core.repository.BoardRepository;
import org.otacoo.chan.core.site.Site;
import org.otacoo.chan.ui.helper.BoardHelper;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.BackgroundUtils;
import org.otacoo.chan.utils.SimpleObservable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.Iterator;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.inject.Inject;

public class BoardSetupPresenter implements SimpleObservable.SimpleObserver<Void> {
    private BoardManager boardManager;

    private Callback callback;
    private AddCallback addCallback;

    private Site site;

    private List<Board> savedBoards;

    private BoardRepository.SitesBoards allBoardsObservable;

    private Executor executor = Executors.newSingleThreadExecutor();
    private BackgroundUtils.Cancelable suggestionCall;

    private List<BoardSuggestion> suggestions = new ArrayList<>();
    private List<BoardSuggestion> customSuggestions = new ArrayList<>();
    private List<String> selectedSuggestions = new LinkedList<>();

    private String suggestionsQuery = null;

    @Inject
    public BoardSetupPresenter(BoardManager boardManager) {
        this.boardManager = boardManager;
    }

    public void create(Callback callback, Site site) {
        this.callback = callback;
        this.site = site;

        savedBoards = boardManager.getSiteSavedBoards(site);
        callback.setSavedBoards(savedBoards);

        allBoardsObservable = boardManager.getAllBoardsObservable();
        allBoardsObservable.addObserver(this);

        // Always refresh available boards from network when opening the setup screen if the site supports it.
        // This ensures the user can "clean up" or see an up-to-date list of boards if needed.
        if (site.boardsType().canList) {
            site.actions().boards(boards -> {
                boardManager.updateAvailableBoardsForSite(site, boards.boards);
                Toast.makeText(AndroidUtils.getAppContext(), "Board list refreshed.", Toast.LENGTH_SHORT).show();
            });
        }
    }

    public void destroy() {
        boardManager.updateBoardOrders(savedBoards);

        allBoardsObservable.deleteObserver(this);
    }

    @Override
    public void onUpdate(SimpleObservable<Void> o, Void arg) {
        if (o == allBoardsObservable) {
            if (addCallback != null) {
                // Update the boards shown in the query.
                queryBoardsWithQueryAndShowInAddDialog();
            }
        }
    }

    public void addClicked() {
        callback.showAddDialog();
    }

    public void bindAddDialog(AddCallback addCallback) {
        this.addCallback = addCallback;

        // ensure previous search state doesn't leak into a new dialog instance
        suggestionsQuery = null;
        selectedSuggestions.clear();
        suggestions.clear();
        customSuggestions.clear();

        queryBoardsWithQueryAndShowInAddDialog();
    }

    public void unbindAddDialog() {
        this.addCallback = null;
        suggestions.clear();
        selectedSuggestions.clear();
        suggestionsQuery = null;
    }

    public void onSelectAllClicked() {
        for (BoardSuggestion suggestion : suggestions) {
            suggestion.checked = true;
            selectedSuggestions.add(suggestion.getCode());
        }
        addCallback.suggestionsWereChanged();
    }

    public void onSuggestionClicked(BoardSuggestion suggestion) {
        suggestion.checked = !suggestion.checked;
        if (suggestion.checked) {
            selectedSuggestions.add(suggestion.getCode());
        } else {
            selectedSuggestions.remove(suggestion.getCode());
        }
    }

    // Add a board code manually (used by Chan8).
    public void addManualBoard(String code, String description) {
        String normalized = code.replace("/", "").trim();
        if (normalized.isEmpty()) return;

        BoardSuggestion suggestion = new BoardSuggestion(normalized);
        suggestion.checked = true;
        suggestion.setCustomDescription(description);
        customSuggestions.add(suggestion);
        suggestions.add(0, suggestion);
        if (!selectedSuggestions.contains(normalized)) {
            selectedSuggestions.add(normalized);
        }
        if (addCallback != null) {
            addCallback.suggestionsWereChanged();
        }
    }

    public List<BoardSuggestion> getSuggestions() {
        return suggestions;
    }

    /**
     * Returns a hint string to display in the add-boards dialog, or {@code null} if no hint
     * is needed. A hint is shown for sites where boards cannot be listed (e.g. INFINITE sites)
     * so the user knows they must type a board code manually.
     */
    public String getAddDialogHint() {
        if (!site.boardsType().canList) {
            return "Type a board code (e.g. \"v\") and press Add.";
        }
        return null;
    }

    public boolean allowCustomBoardCode() {
        // right now only Chan8 needs this behaviour, but the check is generic
        return site.getClass().getSimpleName().equals("Chan8");
    }

    public void onAddDialogPositiveClicked() {
        int count = 0;

        List<Board> boardsToSave = new ArrayList<>();

        if (site.boardsType().canList) {
            List<Board> siteBoards = boardManager.getSiteBoards(site);
            Map<String, Board> siteBoardsByCode = new HashMap<>();
            for (Board siteBoard : siteBoards) {
                siteBoardsByCode.put(siteBoard.code, siteBoard);
            }
            for (String selectedSuggestion : selectedSuggestions) {
                Board board = siteBoardsByCode.get(selectedSuggestion);
                if (board == null) {
                    // not in the fetched list – manual entry (Chan8 custom code)
                    String name = selectedSuggestion; // fallback
                    String desc = null;
                    for (BoardSuggestion sug : customSuggestions) {
                        if (sug.getCode().equalsIgnoreCase(selectedSuggestion)) {
                            desc = sug.getDescription();
                            if (desc != null && !desc.isEmpty()) {
                                name = desc; // use description as display name
                            }
                            break;
                        }
                    }
                    board = site.createBoard(name, selectedSuggestion);
                    board.description = desc;
                }
                boardsToSave.add(board);
                savedBoards.add(board);
                count++;
            }
        } else {
            for (String suggestion : selectedSuggestions) {
                Board board = site.createBoard(suggestion, suggestion);
                boardsToSave.add(board);
                savedBoards.add(board);
                count++;
            }
        }

        boardManager.setAllSaved(boardsToSave, true);

        setOrder();
        callback.setSavedBoards(savedBoards);
        callback.boardsWereAdded(count);
    }

    public void move(int from, int to) {
        Board item = savedBoards.remove(from);
        savedBoards.add(to, item);
        setOrder();

        callback.setSavedBoards(savedBoards);
    }

    public void remove(int position) {
        Board board = savedBoards.remove(position);
        boardManager.setSaved(board, false);

        setOrder();
        callback.setSavedBoards(savedBoards);

        callback.showRemovedSnackbar(board);
    }

    public void undoRemoveBoard(Board board) {
        boardManager.setSaved(board, true);
        savedBoards.add(board.order, board);
        setOrder();
        callback.setSavedBoards(savedBoards);
    }

    public void searchEntered(String userQuery) {
        suggestionsQuery = userQuery;
        queryBoardsWithQueryAndShowInAddDialog();
    }

    private void queryBoardsWithQueryAndShowInAddDialog() {
        if (suggestionCall != null) {
            suggestionCall.cancel();
        }

        final String query = suggestionsQuery == null ? null :
                suggestionsQuery.replace("/", "").replace("\\", "");
        suggestionCall = BackgroundUtils.runWithExecutor(executor, () -> {
            List<BoardSuggestion> suggestions = new ArrayList<>();
            if (site.boardsType().canList) {
                List<Board> siteBoards = boardManager.getSiteBoards(site);
                List<Board> allUnsavedBoards = new ArrayList<>();
                for (Board siteBoard : siteBoards) {
                    if (!siteBoard.saved) {
                        allUnsavedBoards.add(siteBoard);
                    }
                }

                List<Board> toSuggest;
                if (query == null || query.equals("")) {
                    toSuggest = new ArrayList<>(allUnsavedBoards);
                    Collections.sort(toSuggest, (a, b) -> a.code.compareToIgnoreCase(b.code));
                } else {
                    toSuggest = BoardHelper.search(allUnsavedBoards, query);
                }

                for (Board board : toSuggest) {
                    BoardSuggestion suggestion = new BoardSuggestion(board);
                    suggestions.add(suggestion);
                }

                if (site.getClass().getSimpleName().equals("Chan8")) {
                    // if user is on Chan8 and typed something, ensure a custom suggestion exists
                    if (query != null && !query.equals("")) {
                        boolean exists = false;
                        for (BoardSuggestion s : suggestions) {
                            if (s.getCode().equalsIgnoreCase(query)) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            // record the custom code for future queries
                            BoardSuggestion manual = new BoardSuggestion(query);
                            manual.checked = true;
                            customSuggestions.add(manual);
                        }
                    }

                    // merge persistent custom suggestions into the result list (preserve order added).
                    // drop any codes that have already been saved since they no longer apply.
                    Iterator<BoardSuggestion> it = customSuggestions.iterator();
                    while (it.hasNext()) {
                        BoardSuggestion custom = it.next();
                        Board existing = boardManager.getBoard(site, custom.getCode());
                        if (existing != null && existing.saved) {
                            // prune so we don't carry around stale entries
                            it.remove();
                            continue;
                        }

                        boolean already = false;
                        for (BoardSuggestion s : suggestions) {
                            if (s.getCode().equalsIgnoreCase(custom.getCode())) {
                                already = true;
                                break;
                            }
                        }
                        if (!already) {
                            suggestions.add(0, custom);
                        }
                    }
                } else {
                    // non Chan8 behaviour: if nothing matched and query non-empty, allow manual entry
                    if (query != null && !query.equals("") && toSuggest.isEmpty()) {
                        BoardSuggestion manual = new BoardSuggestion(query);
                        manual.checked = true;
                        suggestions.add(manual);
                    }
                }
            } else {
                if (query != null && !query.equals("")) {
                    suggestions.add(new BoardSuggestion(query));
                }
            }

            return suggestions;
        }, result -> {
            updateSuggestions(result);

            if (addCallback != null) {
                addCallback.suggestionsWereChanged();
            }
        });
    }

    private void updateSuggestions(List<BoardSuggestion> suggestions) {
        this.suggestions = suggestions;
        if (!site.boardsType().canList) {
            // For INFINITE sites there is no list to tick — auto-select whatever the user typed.
            selectedSuggestions.clear();
            for (BoardSuggestion suggestion : this.suggestions) {
                selectedSuggestions.add(suggestion.getCode());
            }
        }
        for (BoardSuggestion suggestion : this.suggestions) {
            if (suggestion.board == null && suggestion.checked) {
                if (!selectedSuggestions.contains(suggestion.getCode())) {
                    selectedSuggestions.add(suggestion.getCode());
                }
            }
            suggestion.checked = selectedSuggestions.contains(suggestion.getCode());
        }
    }

    private void setOrder() {
        for (int i = 0; i < savedBoards.size(); i++) {
            Board b = savedBoards.get(i);
            b.order = i;
        }
    }

    public interface Callback {
        void showAddDialog();

        void setSavedBoards(List<Board> savedBoards);

        void showRemovedSnackbar(Board board);

        void boardsWereAdded(int count);
    }

    public interface AddCallback {
        void suggestionsWereChanged();
    }

    public static class BoardSuggestion {
        private final Board board;
        private final String code;
        private String customDescription; // only used when board == null

        public boolean hasBoard() {
            return board != null;
        }

        public Board getBoard() {
            return board;
        }

        private boolean checked = false;

        BoardSuggestion(Board board) {
            this.board = board;
            this.code = board.code;
        }

        BoardSuggestion(String code) {
            this.board = null;
            this.code = code;
        }

        public void setCustomDescription(String desc) {
            this.customDescription = desc;
        }

        public String getName() {
            if (board != null) {
                return BoardHelper.getName(board);
            } else {
                return "/" + code + "/";
            }
        }

        public String getDescription() {
            if (board != null) {
                return BoardHelper.getDescription(board);
            } else {
                return customDescription != null ? customDescription : "";
            }
        }

        public String getCode() {
            return code;
        }

        public boolean isChecked() {
            return checked;
        }

        public long getId() {
            return code.hashCode();
        }
    }
}
