package org.otacoo.chan.core.site;

import org.otacoo.chan.core.model.orm.Board;

import java.util.List;

public class Boards {
    public final List<Board> boards;

    public Boards(List<Board> boards) {
        this.boards = boards;
    }
}
