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

import org.otacoo.chan.core.database.DatabaseManager;
import org.otacoo.chan.core.manager.BoardManager;
import org.otacoo.chan.core.model.orm.Board;
import org.otacoo.chan.core.model.orm.Loadable;
import org.otacoo.chan.core.repository.BoardRepository;
import org.otacoo.chan.core.site.Site;
import org.otacoo.chan.utils.SimpleObservable;

import javax.inject.Inject;

public class BrowsePresenter implements SimpleObservable.SimpleObserver<Void> {
    private final DatabaseManager databaseManager;
    private final BoardManager boardManager;
    private Callback callback;

    private boolean hadBoards = false;
    private Board currentBoard;

    private BoardRepository.SitesBoards savedBoardsObservable;

    @Inject
    public BrowsePresenter(DatabaseManager databaseManager, BoardManager boardManager) {
        this.databaseManager = databaseManager;
        this.boardManager = boardManager;

        savedBoardsObservable = boardManager.getSavedBoardsObservable();

        hadBoards = hasBoards();
    }

    public void create(Callback callback) {
        this.callback = callback;

        savedBoardsObservable.addObserver(this);
    }

    public void destroy() {
        savedBoardsObservable.deleteObserver(this);
    }

    public Board currentBoard() {
        return currentBoard;
    }

    public void setBoard(Board board) {
        loadBoard(board);
    }

    public void onBoardsFloatingMenuBoardClicked(Board board) {
        loadBoard(board);
    }

    public void loadWithDefaultBoard() {
        Board defaultBoard = null;

        // Find the first board that isn't a special board (like the ban status board)
        search:
        for (BoardRepository.SiteBoards item : savedBoardsObservable.get()) {
            for (Board board : item.boards) {
                if (!"ban".equals(board.code)) {
                    defaultBoard = board;
                    break search;
                }
            }
        }

        if (defaultBoard != null) {
            loadBoard(defaultBoard);
        }
    }

    public void onBoardsFloatingMenuSiteClicked(Site site) {
        callback.loadSiteSetup(site);
    }

    @Override
    public void onUpdate(SimpleObservable<Void> o, Void arg) {
        if (o == savedBoardsObservable) {
            boolean has = hasBoards();
            if (!hadBoards && has) {
                hadBoards = true;
                loadWithDefaultBoard();
            } else if (hadBoards && !has) {
                hadBoards = false;
            }
        }
    }

    private boolean hasBoards() {
        return firstBoard() != null;
    }

    private Board firstBoard() {
        for (BoardRepository.SiteBoards item : savedBoardsObservable.get()) {
            if (!item.boards.isEmpty()) {
                return item.boards.get(0);
            }
        }
        return null;
    }

    private Loadable getLoadableForBoard(Board board) {
        return databaseManager.getDatabaseLoadableManager().get(Loadable.forCatalog(board));
    }

    private void loadBoard(Board board) {
        if ("ban".equals(board.code)) {
            callback.openWebPage("https://www.4chan.org/banned", board.name, board.site);
            return;
        }

        currentBoard = board;
        callback.loadBoard(getLoadableForBoard(board));
        callback.showArchiveOption(board.site.boardFeature(Site.BoardFeature.ARCHIVE, board));
    }

    public interface Callback {
        void loadBoard(Loadable loadable);

        void loadSiteSetup(Site site);

        void showArchiveOption(boolean show);

        void openWebPage(String url, String title, Site site);
    }
}
