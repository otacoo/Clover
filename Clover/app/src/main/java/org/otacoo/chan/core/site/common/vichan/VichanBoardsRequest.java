/*
 * Clover - 4chan browser https://github.com/otacoo/Clover/
 * Copyright (C) 2014  floens
 * Copyright (C) 2026  otacoo
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
package org.otacoo.chan.core.site.common.vichan;

import android.util.JsonReader;

import org.otacoo.chan.core.model.orm.Board;
import org.otacoo.chan.core.net.JsonReaderRequest;
import org.otacoo.chan.core.site.Site;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.HttpUrl;

public class VichanBoardsRequest extends JsonReaderRequest<List<Board>> {
    private final Site site;

    public VichanBoardsRequest(Site site, RequestListener<List<Board>> listener) {
        super(listener);
        this.site = site;
    }

    public HttpUrl getUrl() {
        return site.endpoints().boards();
    }

    @Override
    public List<Board> readJson(JsonReader reader) throws Exception {
        List<Board> list = new ArrayList<>();

        // Vichan boards.json is a top-level array
        reader.beginArray();
        while (reader.hasNext()) {
            Board board = readBoardEntry(reader);
            if (board != null) {
                list.add(board);
            }
        }
        reader.endArray();

        return list;
    }

    private Board readBoardEntry(JsonReader reader) throws IOException {
        reader.beginObject();

        Board board = Board.fromSiteNameCode(site, null, null);

        while (reader.hasNext()) {
            String key = reader.nextName();

            switch (key) {
                case "title":
                    board.name = reader.nextString();
                    break;
                case "board":
                    board.code = reader.nextString();
                    break;
                case "ws_board":
                    board.workSafe = reader.nextInt() == 1;
                    break;
                case "per_page":
                    board.perPage = reader.nextInt();
                    break;
                case "pages":
                    board.pages = reader.nextInt();
                    break;
                case "max_filesize":
                    board.maxFileSize = reader.nextInt();
                    break;
                case "max_webm_filesize":
                    board.maxWebmSize = reader.nextInt();
                    break;
                case "max_comment_chars":
                    board.maxCommentChars = reader.nextInt();
                    break;
                case "bump_limit":
                    board.bumpLimit = reader.nextInt();
                    break;
                case "image_limit":
                    board.imageLimit = reader.nextInt();
                    break;
                case "cooldowns":
                    reader.beginObject();
                    while (reader.hasNext()) {
                        switch (reader.nextName()) {
                            case "threads":
                                board.cooldownThreads = reader.nextInt();
                                break;
                            case "replies":
                                board.cooldownReplies = reader.nextInt();
                                break;
                            case "images":
                                board.cooldownImages = reader.nextInt();
                                break;
                            default:
                                reader.skipValue();
                                break;
                        }
                    }
                    reader.endObject();
                    break;
                case "spoilers":
                    board.spoilers = reader.nextInt() == 1;
                    break;
                case "custom_spoilers":
                    board.customSpoilers = reader.nextInt();
                    break;
                default:
                    reader.skipValue();
                    break;
            }
        }

        reader.endObject();

        if (!board.finish()) {
            return null;
        }

        return board;
    }
}
