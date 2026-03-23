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

import static org.otacoo.chan.utils.AndroidUtils.getAppContext;
import static org.otacoo.chan.utils.AndroidUtils.getReadableFileSize;
import static org.otacoo.chan.utils.AndroidUtils.getRes;
import static org.otacoo.chan.utils.AndroidUtils.getString;

import android.text.TextUtils;

import android.widget.Toast;

import org.otacoo.chan.R;
import org.otacoo.chan.core.database.DatabaseManager;
import org.otacoo.chan.core.manager.ReplyManager;
import org.otacoo.chan.core.manager.WatchManager;
import org.otacoo.chan.core.model.ChanThread;
import org.otacoo.chan.core.model.Post;
import org.otacoo.chan.core.model.orm.Board;
import org.otacoo.chan.core.model.orm.Loadable;
import org.otacoo.chan.core.model.orm.SavedReply;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.core.site.Site;
import org.otacoo.chan.core.site.SiteActions;
import org.otacoo.chan.core.site.SiteAuthentication;
import org.otacoo.chan.core.site.http.HttpCall;
import org.otacoo.chan.core.site.http.Reply;
import org.otacoo.chan.core.site.http.ReplyResponse;
import org.otacoo.chan.ui.activity.ImagePickDelegate;
import org.otacoo.chan.ui.captcha.AuthenticationLayoutCallback;
import org.otacoo.chan.ui.captcha.AuthenticationLayoutInterface;
import org.otacoo.chan.ui.captcha.CaptchaLayout;
import org.otacoo.chan.ui.captcha.GenericWebViewAuthenticationLayout;
import org.otacoo.chan.ui.captcha.LynxchanCaptchaLayout;
import org.otacoo.chan.ui.captcha.NewCaptchaLayout;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

public class ReplyPresenter implements AuthenticationLayoutCallback, ImagePickDelegate.ImagePickCallback, SiteActions.PostListener {
    public enum Page {
        INPUT,
        AUTHENTICATION,
        LOADING
    }

    private static final String TAG = "ReplyPresenter";
    private static final Pattern QUOTE_PATTERN = Pattern.compile(">>\\d+");
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private ReplyPresenterCallback callback;

    private ReplyManager replyManager;
    private WatchManager watchManager;
    private DatabaseManager databaseManager;

    private boolean bound = false;
    private Loadable loadable;
    private Board board;
    private Reply draft;

    private Page page = Page.INPUT;
    private boolean moreOpen;
    private boolean previewOpen;
    private boolean pickingFile;
    private int selectedQuote = -1;
    
    private int editingAttachmentIndex = -1;
    
    /** Stores authentication error message to display when switching to authentication page */
    private String authenticationErrorMessage = null;

    @Inject
    public ReplyPresenter(ReplyManager replyManager,
                          WatchManager watchManager,
                          DatabaseManager databaseManager) {
        this.replyManager = replyManager;
        this.watchManager = watchManager;
        this.databaseManager = databaseManager;
    }

    public void create(ReplyPresenterCallback callback) {
        this.callback = callback;
    }

    public void bindLoadable(Loadable loadable) {
        if (this.loadable != null) {
            unbindLoadable();
        }
        bound = true;
        this.loadable = loadable;

        // Prefer the repository's live board object (updated by the boards API fetch)
        // over the possibly-stale snapshot stored in the loadable.
        Board cachedBoard = loadable.getSite().board(loadable.boardCode);
        this.board = (cachedBoard != null) ? cachedBoard : loadable.board;
        if (board == null) {
            Logger.e(TAG, "bindLoadable: board is null!");
            return;
        }

        draft = replyManager.getReply(loadable);
        draft.loadable = loadable;

        if (TextUtils.isEmpty(draft.name)) {
            draft.name = loadable.draftName;
        }
        if (TextUtils.isEmpty(draft.name)) {
            draft.name = ChanSettings.postDefaultName.get();
        }
        
        if (TextUtils.isEmpty(draft.subject)) {
            draft.subject = loadable.draftSubject;
        }
        
        if (TextUtils.isEmpty(draft.comment)) {
            draft.comment = loadable.draftComment;
        }
        
        if (TextUtils.isEmpty(draft.options)) {
            draft.options = loadable.draftOptions;
        }
        
        if (TextUtils.isEmpty(draft.flag)) {
            draft.flag = loadable.draftFlag;
        }

        callback.loadDraftIntoViews(draft);
        callback.updateCommentCount(0, board.maxCommentChars, false);
        callback.setCommentHint(getString(loadable.isThreadMode() ? R.string.reply_comment_thread : R.string.reply_comment_board));
        callback.showCommentCounter(board.maxCommentChars > 0);
        callback.showFlag(!board.boardFlags.isEmpty());

        org.otacoo.chan.core.site.FileUploadLimits limits = loadable.getSite().fileUploadLimits();
        
        // Reset previewOpen before checking attachments
        previewOpen = false;
        
        // Show multi-file attachments if they exist
        if (limits.maxFileCount > 1 && !draft.fileAttachments.isEmpty()) {
            callback.openFileAttachments(draft.fileAttachments, draft.fileAttachments.size(), limits.maxFileCount);
            previewOpen = true;
        } else if (draft.file != null) {
            // Legacy single file mode
            showPreview(draft.fileName, draft.file);
        } else {
            // No file, ensure preview is closed
            callback.openPreview(false, null);
        }

        switchPage(Page.INPUT, false);
        updateButtons();
    }

    public void unbindLoadable() {
        bound = false;
        callback.loadViewsIntoDraft(draft);
        
        // Save to Loadable for persistent storage
        if (loadable.isThreadMode()) {
            loadable.setDraftName(draft.name);
            loadable.setDraftSubject(draft.subject);
            loadable.setDraftComment(draft.comment);
            loadable.setDraftOptions(draft.options);
            loadable.setDraftFlag(draft.flag);
        }
        
        // Clear file attachment data before saving
        draft.file = null;
        draft.fileName = "";
        draft.fileAttachments.clear();
        
        replyManager.putReply(loadable, draft);

        closeAll();
    }

    public Map<String, String> getBoardFlags() {
        return loadable.board.boardFlags;
    }

    public void onOpen(boolean open) {
        if (open) {
            callback.focusComment();
        }
    }

    public boolean onBack() {
        if (page == Page.LOADING) {
            return true;
        } else if (page == Page.AUTHENTICATION) {
            switchPage(Page.INPUT, true);
            return true;
        } else if (moreOpen) {
            onMoreClicked();
            return true;
        }
        return false;
    }

    public void onMoreClicked() {
        moreOpen = !moreOpen;
        callback.setExpanded(moreOpen);
        callback.openNameOptions(moreOpen);
        if (!loadable.isThreadMode()) {
            callback.openSubject(moreOpen);
        }
        updateButtons();
        if (previewOpen) {
            // Only show global filename field for single-file sites
            if (isSingleFileMode()) {
                callback.openFileName(moreOpen);
            }
            callback.openSpoiler(loadable.getSite().boardFeature(Site.BoardFeature.POSTING_SPOILER, board) && moreOpen && isSingleFileMode(), false);
        }
    }

    private void updateButtons() {
        boolean alwaysShow = ChanSettings.alwaysShowReplyTags.get();
        callback.openCommentQuoteButton(moreOpen || alwaysShow);
        callback.openCommentSpoilerButton(loadable.getSite().boardFeature(Site.BoardFeature.POSTING_SPOILER, board) && (moreOpen || alwaysShow));
        callback.openCommentCodeButton(board.codeTags && (moreOpen || alwaysShow));
        callback.openCommentMathButton(board.mathTags && (moreOpen || alwaysShow));
        callback.openCommentEqnButton(board.mathTags && (moreOpen || alwaysShow));
        callback.openCommentRedtextButton(loadable.getSite().boardFeature(Site.BoardFeature.FORMATTING_REDTEXT, board) && (moreOpen || alwaysShow));
        callback.openCommentItalicButton(loadable.getSite().boardFeature(Site.BoardFeature.FORMATTING_ITALIC, board) && (moreOpen || alwaysShow));
        callback.openCommentBoldButton(loadable.getSite().boardFeature(Site.BoardFeature.FORMATTING_BOLD, board) && (moreOpen || alwaysShow));
    }

    public boolean isExpanded() {
        return moreOpen;
    }
    
    public boolean isSingleFileMode() {
        if (loadable == null) return true;
        org.otacoo.chan.core.site.FileUploadLimits limits = loadable.getSite().fileUploadLimits();
        if (limits == null) return true; // Default to single-file mode if limits unavailable
        return limits.maxFileCount == 1;
    }

    public void onAttachClicked() {
        if (loadable == null) return;
        
        org.otacoo.chan.core.site.FileUploadLimits limits = loadable.getSite().fileUploadLimits();
        if (limits == null) return; // Can't proceed without limits
        
        int maxFiles = limits.maxFileCount;
        
        if (!pickingFile) {
            // For multi-file sites, clear all attachments or add more if under the limit
            if (maxFiles > 1) {
                if (previewOpen || !draft.fileAttachments.isEmpty()) {
                    // Main button acts as "remove all files" when attachments exist
                    draft.fileAttachments.clear();
                    callback.openFileAttachments(draft.fileAttachments, 0, maxFiles);
                    previewOpen = false;
                } else {
                    int remainingFiles = maxFiles - draft.fileAttachments.size();
                    if (remainingFiles <= 0) {
                        return;
                    }

                    // Enable multi-select based on remaining slots
                    boolean allowMultiple = remainingFiles > 1;
                    pickingFile = callback.getImagePickDelegate().pick(this, allowMultiple, remainingFiles);
                }
            } else if (maxFiles == 1) {
                // Single file mode - toggle preview
                if (previewOpen) {
                    callback.openPreview(false, null);
                    draft.file = null;
                    draft.fileName = "";
                    if (moreOpen) {
                        callback.openFileName(false);
                        callback.openSpoiler(false, false);
                    }
                    previewOpen = false;
                } else {
                    pickingFile = callback.getImagePickDelegate().pick(this, false, 1);
                }
            }
        }
    }

    /**
     * Remove a file attachment at the specified index.
     * Called when user clicks the remove button on a file.
     */
    public void removeFileAttachment(int index) {
        if (index >= 0 && index < draft.fileAttachments.size()) {
            draft.fileAttachments.get(index).file = null; // Clean up
            draft.fileAttachments.remove(index);
            
            org.otacoo.chan.core.site.FileUploadLimits limits = loadable.getSite().fileUploadLimits();
            int maxFiles = limits.maxFileCount;
            
            if (draft.fileAttachments.isEmpty()) {
                // All files removed - hide file attachments and update button state
                callback.openFileAttachments(draft.fileAttachments, 0, maxFiles);
                previewOpen = false;
            } else {
                // Some files remain - update the display
                callback.openFileAttachments(draft.fileAttachments, draft.fileAttachments.size(), maxFiles);
            }
        }
    }

    /**
     * Toggle the spoiler status of a file attachment.
     * @param index Index of the attachment
     * @param spoiler Whether to spoiler the image
     */
    public void setFileAttachmentSpoiler(int index, boolean spoiler) {
        if (index >= 0 && index < draft.fileAttachments.size()) {
            draft.fileAttachments.get(index).spoiler = spoiler;
        }
    }
    
    /**
     * Update the filename of a file attachment.
     * @param index Index of the attachment
     * @param fileName New filename
     */
    public void setFileAttachmentFileName(int index, String fileName) {
        if (index >= 0 && index < draft.fileAttachments.size()) {
            draft.fileAttachments.get(index).fileName = fileName;
        }
    }

    public void onSubmitClicked() {
        if (loadable == null) {
            Toast.makeText(getAppContext(), "Error: No loadable", Toast.LENGTH_SHORT).show();
            return;
        }
        
        callback.loadViewsIntoDraft(draft);

        draft.loadable = loadable;
        draft.spoilerImage = draft.spoilerImage
                && loadable.getSite().boardFeature(Site.BoardFeature.POSTING_SPOILER, board);
        draft.captchaResponse = null;

        // For backwards compatibility with single-file mode
        if (!draft.fileAttachments.isEmpty()) {
            Reply.FileAttachment first = draft.fileAttachments.get(0);
            draft.file = first.file;
            draft.fileName = first.fileName;
            draft.spoilerImage = first.spoiler;
        }

        if (loadable.site.actions().postRequiresAuthentication()) {
            switchPage(Page.AUTHENTICATION, true);
        } else {
            makeSubmitCall();
        }
    }

    @Override
    public void onPostComplete(HttpCall httpCall, ReplyResponse replyResponse) {
        // Guard against the user navigating away before the async callback arrived
        if (!bound || loadable == null || board == null) {
            //Logger.w(TAG, "onPostComplete: ignoring callback");
            return;
        }
        if (replyResponse.posted) {
            if (ChanSettings.postPinThread.get()) {
                if (loadable.isThreadMode()) {
                    ChanThread thread = callback.getThread();
                    if (thread != null) {
                        watchManager.createPin(loadable, thread.op);
                    }
                } else {
                    Loadable postedLoadable = databaseManager.getDatabaseLoadableManager()
                            .get(Loadable.forThread(loadable.site, loadable.board,
                                    replyResponse.postNo));

                    watchManager.createPin(postedLoadable);
                }
            }

            SavedReply savedReply = SavedReply.fromSiteBoardNoPassword(
                    loadable.site, loadable.board, replyResponse.postNo, replyResponse.password);
            // Use runTask (synchronous) so savedRepliesByNo is populated before the thread
            // refresh below triggers post parsing — otherwise isSavedReply stays false.
            databaseManager.runTask(databaseManager.getDatabaseSavedReplyManager()
                    .saveReply(savedReply));

            switchPage(Page.INPUT, false);
            closeAll();
            highlightQuotes();
            String name = draft.name;
            String flag = draft.flag;
            draft = new Reply();
            draft.name = name;
            draft.flag = flag;
            replyManager.putReply(loadable, draft);
            
            // Clear drafts from loadable after successful post
            if (loadable.isThreadMode()) {
                loadable.setDraftName("");
                loadable.setDraftSubject("");
                loadable.setDraftComment("");
                loadable.setDraftOptions("");
                loadable.setDraftFlag("");
            }

            callback.loadDraftIntoViews(draft);
            callback.onPosted();

            if (bound && !loadable.isThreadMode()) {
                callback.showThread(databaseManager.getDatabaseLoadableManager().get(
                        Loadable.forThread(loadable.site, loadable.board, replyResponse.postNo)));
            }
        } else if (replyResponse.requireAuthentication) {
            if (loadable.site.actions().postAuthenticate().type == SiteAuthentication.Type.NONE) {
                // The user has a 4chan Pass but the server rejected it (expired/invalid).
                // Fall back to the input page and surface the error instead.
                String errorMessage = replyResponse.errorMessage != null
                        ? replyResponse.errorMessage : getString(R.string.reply_error);
                switchPage(Page.INPUT, true);
                Toast.makeText(getAppContext(), errorMessage, Toast.LENGTH_LONG).show();
                callback.openMessage(true, false, errorMessage, true);
            } else {
                // Store the error message to display in the authentication layout
                authenticationErrorMessage = replyResponse.errorMessage != null ?
                    replyResponse.errorMessage : "Captcha authentication failed.\nPlease solve the captcha again.";
                switchPage(Page.AUTHENTICATION, true);
            }
        } else {
            String errorMessage = getString(R.string.reply_error);
            if (replyResponse.errorMessage != null) {
                String cleanMessage = replyResponse.errorMessage
                        .split("4chan Pass users ", 2)[0]
                        .replace("Duplicate file exists. here.", "Duplicate file exists.")
                        .replace("[More Info]", "").trim();

                // Handle 4chan's common captcha failed message and its typos
                if (cleanMessage.toLowerCase(Locale.ENGLISH).contains("mistyped") || 
                    cleanMessage.toLowerCase(Locale.ENGLISH).contains("catcha")) {
                    cleanMessage = "Looks like you failed the captcha.";
                }

                // Strip redundant "error posting." prefix if it exists, as the resource adds "Error posting: "
                String lowerMsg = cleanMessage.toLowerCase(Locale.ENGLISH);
                if (lowerMsg.startsWith("error: error posting.")) {
                    cleanMessage = cleanMessage.substring(21).trim();
                } else if (lowerMsg.startsWith("error posting.")) {
                    cleanMessage = cleanMessage.substring(14).trim();
                }

                if (cleanMessage.isEmpty() || cleanMessage.equals(".")) {
                    errorMessage = getString(R.string.reply_error);
                } else {
                    errorMessage = getAppContext().getString(
                            R.string.reply_error_message, cleanMessage);
                }
            }

            Logger.e(TAG, "onPostComplete error — posted=" + replyResponse.posted
                    + " requireAuth=" + replyResponse.requireAuthentication
                    + " rawError=" + replyResponse.errorMessage
                    + " probablyBanned=" + replyResponse.probablyBanned
                    + " displayMessage=" + errorMessage);
            switchPage(Page.INPUT, true);
            Toast.makeText(getAppContext(), errorMessage, Toast.LENGTH_LONG).show();
            callback.openMessage(true, false, errorMessage, true);
        }
    }

    @Override
    public void onUploadingProgress(int percent) {
        //called on a background thread!

        AndroidUtils.runOnUiThread(() -> callback.onUploadingProgress(percent));
    }

    @Override
    public void onPostError(HttpCall httpCall, Exception exception) {
        Logger.e(TAG, "onPostError", exception);

        switchPage(Page.INPUT, true);

        String errorMessage = getString(R.string.reply_error);
        if (exception != null) {
            String message = exception.getMessage();
            if (message != null) {
                errorMessage = getAppContext().getString(R.string.reply_error_message, message);
            }
        }

        Toast.makeText(getAppContext(), errorMessage, Toast.LENGTH_LONG).show();
        callback.openMessage(true, false, errorMessage, true);
    }

    @Override
    public void onAuthenticationComplete(AuthenticationLayoutInterface authenticationLayout, String challenge, String response) {
        Logger.i(TAG, "onAuthenticationComplete received from " + authenticationLayout.getClass().getSimpleName() + 
                ". Challenge present: " + (challenge != null && !challenge.isEmpty()) + 
                ", Response length: " + (response != null ? response.length() : 0));
        
        draft.captchaChallenge = challenge;
        draft.captchaResponse = response;

        if (authenticationLayout.requireResetAfterComplete()) {
            Logger.i(TAG, "onAuthenticationComplete: Reset requested by auth layout");
            authenticationLayout.reset();
        }

        Logger.i(TAG, "onAuthenticationComplete: Triggering makeSubmitCall()");
        makeSubmitCall();
    }

    @Override
    public void onFallbackToV1CaptchaView() {
        callback.onFallbackToV1CaptchaView();
    }

    @Override
    public void onVerificationComplete() {
        switchPage(Page.INPUT, true);
    }

    public void onCommentTextChanged(CharSequence text) {
        int length = text.toString().getBytes(UTF_8).length;
        callback.updateCommentCount(length, board.maxCommentChars, length > board.maxCommentChars);
    }

    public void onSelectionChanged() {
        callback.loadViewsIntoDraft(draft);
        highlightQuotes();
    }

    public void commentQuoteClicked() {
        commentInsert(">");
    }

    public void commentSpoilerClicked() {
        commentInsert("[spoiler]", "[/spoiler]");
    }

    public void commentCodeClicked() {
        commentInsert("[code]", "[/code]");
    }

    public void commentMathClicked() {
        commentInsert("[math]", "[/math]");
    }

    public void commentEqnClicked() {
        commentInsert("[eqn]", "[/eqn]");
    }

    public void commentRedtextClicked() {
        commentInsert("==", "==");
    }

    public void commentItalicClicked() {
        commentInsert("''", "''");
    }

    public void commentBoldClicked() {
        commentInsert("'''", "'''");
    }

    public void quote(Post post, boolean withText) {
        handleQuote(post, withText ? post.comment.toString() : null);
    }

    public void quote(Post post, CharSequence text) {
        handleQuote(post, text.toString());
    }

    private void handleQuote(Post post, String textQuote) {
        callback.loadViewsIntoDraft(draft);

        String extraNewline = "";
        if (draft.selectionStart - 1 >= 0 && draft.selectionStart - 1 < draft.comment.length() &&
                draft.comment.charAt(draft.selectionStart - 1) != '\n') {
            extraNewline = "\n";
        }

        String postQuote = "";
        if (post != null) {
            if (!draft.comment.contains(">>" + post.no)) {
                postQuote = ">>" + post.no + "\n";
            }
        }

        StringBuilder textQuoteResult = new StringBuilder();
        if (textQuote != null) {
            String[] lines = textQuote.split("\n+");
            // matches for >>123, >>123 (OP), >>123 (You), >>>/fit/123
            final Pattern quotePattern = Pattern.compile("^>>(>/[a-z0-9]+/)?\\d+.*$");
            for (String line : lines) {
                // do not include post no from quoted post
                if (!quotePattern.matcher(line).matches()) {
                    textQuoteResult.append(">").append(line).append("\n");
                }
            }
        }

        commentInsert(extraNewline + postQuote + textQuoteResult.toString());

        highlightQuotes();
    }

    private void commentInsert(String insertBefore) {
        commentInsert(insertBefore, "");
    }

    private void commentInsert(String insertBefore, String insertAfter) {
        draft.comment = new StringBuilder(draft.comment)
                .insert(draft.selectionStart, insertBefore)
                .insert(draft.selectionEnd + insertBefore.length(), insertAfter)
                .toString();
        /* Since this method is only used for quote insertion and spoilers,
        both of which should set the cursor to right after the selected text for more typing,
        set the selection start to the new end */
        draft.selectionEnd += insertBefore.length();
        draft.selectionStart = draft.selectionEnd;
        callback.loadDraftIntoViews(draft);
    }

    @Override
    public void onFilePickLoading() {
        callback.onFilePickLoading();
    }

    @Override
    public void onFilePicked(String name, File file) {
        pickingFile = false;
        
        org.otacoo.chan.core.site.FileUploadLimits limits = loadable.getSite().fileUploadLimits();
        int maxFiles = limits.maxFileCount;
        
        String finalFileName = maybeRandomizeFilename(name);
        
        // For sites that support multiple files
        if (maxFiles > 1 && draft.fileAttachments.size() < maxFiles) {
            draft.fileAttachments.add(new Reply.FileAttachment(file, finalFileName, false));
            callback.openFileAttachments(draft.fileAttachments, draft.fileAttachments.size(), maxFiles);
            previewOpen = true;
        } else {
            // Legacy single file mode (for 4chan and single-file sites)
            draft.file = file;
            draft.fileName = finalFileName;
            draft.spoilerImage = false;
            showPreview(finalFileName, file);
        }
    }

    private String maybeRandomizeFilename(String originalName) {
        if (ChanSettings.randomizeFilename.get()) {
            long nowMicros = System.currentTimeMillis() * 1000L;
            long oneDayMillis = 24L * 60L * 60L * 1000L;
            long randomPastYearMicros = (long) (Math.random() * 365L * oneDayMillis * 1000L);
            long randomizedTimestamp = nowMicros - randomPastYearMicros;
            
            // Extract file extension from original name if it exists
            String extension = "";
            int dotIndex = originalName.lastIndexOf('.');
            if (dotIndex > 0 && dotIndex < originalName.length() - 1) {
                extension = originalName.substring(dotIndex);
            }
            
            return randomizedTimestamp + extension;
        }
        return originalName;
    }

    @Override
    public void onFilePickError(boolean cancelled) {
        pickingFile = false;
        if (!cancelled) {
            callback.onFilePickError();
        }
    }

    private void closeAll() {
        moreOpen = false;
        previewOpen = false;
        selectedQuote = -1;
        editingAttachmentIndex = -1;
        callback.openMessage(false, true, "", false);
        callback.setExpanded(false);
        callback.openSubject(false);
        updateButtons();
        callback.openNameOptions(false);
        callback.openFileName(false);
        callback.openSpoiler(false, false);
        callback.openPreview(false, null);
        callback.openPreviewMessage(false, null);
        callback.destroyCurrentAuthentication();
    }

    private void makeSubmitCall() {
        if (draft.loadable == null) {
            Logger.e(TAG, "Draft loadable is null before posting!");
            onPostError(null, new IOException("loadable is null"));
            return;
        }
        
        Logger.i(TAG, "makeSubmitCall: site=" + loadable.site.name() + ", board=" + loadable.board + ", thread=" + loadable.no);
        Logger.i(TAG, "makeSubmitCall: captchaChallenge present: " + (draft.captchaChallenge != null && !draft.captchaChallenge.isEmpty()) + 
            ", captchaResponse present: " + (draft.captchaResponse != null && !draft.captchaResponse.isEmpty())
            + ", spoilerImage=" + draft.spoilerImage);

        loadable.getSite().actions().post(draft, this);
        switchPage(Page.LOADING, true);
        Logger.i(TAG, "makeSubmitCall: switchPage(LOADING) called");
    }

    public void switchPage(Page page, boolean animate) {
        if (this.page != page) {
            this.page = page;
            switch (page) {
                case LOADING:
                    callback.setPage(Page.LOADING, true);
                    break;
                case INPUT:
                    callback.setPage(Page.INPUT, animate);
                    break;
                case AUTHENTICATION:
                    SiteAuthentication authentication = loadable.site.actions().postAuthenticate();

                    // callback.initializeAuthentication already handles reuse/destruction based on type
                    callback.initializeAuthentication(loadable, authentication, this);
                    callback.setPage(Page.AUTHENTICATION, true);
                    
                    // Display the error message if one was captured from the failed post
                    if (authenticationErrorMessage != null) {
                        callback.showAuthenticationError(authenticationErrorMessage);
                        authenticationErrorMessage = null;
                    }

                    break;
            }
        }
    }

    private void highlightQuotes() {
        Matcher matcher = QUOTE_PATTERN.matcher(draft.comment);

        // Find all occurrences of >>\d+ with start and end between selectionStart
        int no = -1;
        while (matcher.find()) {
            if (matcher.start() <= draft.selectionStart && matcher.end() >= draft.selectionStart - 1) {
                String quote = matcher.group().substring(2);
                try {
                    no = Integer.parseInt(quote);
                    break;
                } catch (NumberFormatException ignored) {
                }
            }
        }

        // Allow no = -1 removing the highlight
        if (no != selectedQuote) {
            selectedQuote = no;
            callback.highlightPostNo(no);
        }
    }

    private void showPreview(String name, File file) {
        callback.openPreview(true, file);
        if (moreOpen) {
            callback.openFileName(true);
            callback.openSpoiler(
                    loadable.getSite().boardFeature(Site.BoardFeature.POSTING_SPOILER, board)
                            && isSingleFileMode(),
                draft.spoilerImage);
        }
        callback.setFileName(name);
        previewOpen = true;

        // Get file upload limits from the site
        org.otacoo.chan.core.site.FileUploadLimits limits = board.getSite().fileUploadLimits();
        
        boolean probablyWebm = name.toLowerCase(Locale.ENGLISH).endsWith(".webm");
        long maxSize = probablyWebm ? limits.maxWebmSize : limits.maxFileSize;
        
        // If site limits don't specify a max size, fall back to board settings
        if (maxSize <= 0) {
            maxSize = probablyWebm ? board.maxWebmSize : board.maxFileSize;
        }
        
        if (maxSize > 0 && file.length() > maxSize) {
            String fileSize = getReadableFileSize(file.length());
            String maxSizeString = getReadableFileSize(maxSize);
            String text = getRes().getString(probablyWebm ? R.string.reply_webm_too_big : R.string.reply_file_too_big, fileSize, maxSizeString);
            callback.openPreviewMessage(true, text);
        } else if (name.toLowerCase(Locale.ENGLISH).endsWith(".webp")) {
            callback.openPreviewMessage(true, getRes().getString(R.string.reply_file_is_webp));
        } else {
            callback.openPreviewMessage(false, null);
        }
    }
    
    /**
     * Called when an image attachment is clicked to open re-encoding options.
     * @param index Index of the attachment
     */
    public void onImageAttachmentClicked(int index) {
        org.otacoo.chan.core.site.FileUploadLimits limits = loadable.getSite().fileUploadLimits();
        if (limits.maxFileCount > 1 && index >= 0 && index < draft.fileAttachments.size()) {
            editingAttachmentIndex = index;
            Reply.FileAttachment attachment = draft.fileAttachments.get(index);
            // Temporarily set single file fields so re-encoder picks them up
            draft.file = attachment.file;
            draft.fileName = attachment.fileName;
        } else {
            editingAttachmentIndex = -1;
        }
    }

    /**
     * Applies the new file and filename if they have been changed. They may change when user
     * re-encodes the picked image file (they may want to scale it down/remove metadata/change quality etc.)
     */
    public void onImageOptionsApplied(Reply reply) {
        if (editingAttachmentIndex != -1 && editingAttachmentIndex < draft.fileAttachments.size()) {
            Reply.FileAttachment attachment = draft.fileAttachments.get(editingAttachmentIndex);
            attachment.file = reply.file;
            attachment.fileName = reply.fileName;
            
            org.otacoo.chan.core.site.FileUploadLimits limits = loadable.getSite().fileUploadLimits();
            callback.openFileAttachments(draft.fileAttachments, draft.fileAttachments.size(), limits.maxFileCount);
            
            // Clear temporary fields
            draft.file = null;
            draft.fileName = "";
            editingAttachmentIndex = -1;
        } else {
            draft.file = reply.file;
            draft.fileName = reply.fileName;
            showPreview(draft.fileName, draft.file);
        }
    }

    public interface ReplyPresenterCallback {
        void loadViewsIntoDraft(Reply draft);

        void loadDraftIntoViews(Reply draft);

        void setPage(Page page, boolean animate);

        void initializeAuthentication(Loadable loadable,
                                      SiteAuthentication authentication,
                                      AuthenticationLayoutCallback callback);

        void resetAuthentication();

        void openMessage(boolean open, boolean animate, String message, boolean autoHide);

        void onPosted();

        void setCommentHint(String hint);

        void showCommentCounter(boolean show);

        void showFlag(boolean show);

        void setExpanded(boolean expanded);

        void openNameOptions(boolean open);

        void openSubject(boolean open);

        void openCommentQuoteButton(boolean open);

        void openCommentSpoilerButton(boolean open);

        void openCommentCodeButton(boolean open);

        void openCommentMathButton(boolean open);

        void openCommentEqnButton(boolean open);

        void openCommentRedtextButton(boolean open);

        void openCommentItalicButton(boolean open);

        void openCommentBoldButton(boolean open);

        void openFileName(boolean open);

        void setFileName(String fileName);

        void updateCommentCount(int count, int maxCount, boolean over);

        void openPreview(boolean show, File previewFile);

        void openPreviewMessage(boolean show, String message);

        void openSpoiler(boolean show, boolean checked);
        
        /**
         * Display multiple file attachments for sites that support multi-file uploads.
         * @param attachments List of FileAttachment objects
         * @param currentCount Current number of files
         * @param maxCount Maximum files allowed
         */
        void openFileAttachments(List<Reply.FileAttachment> attachments, int currentCount, int maxCount);
        
        /**
         * Remove a file attachment at the specified index.
         * @param index Index of the attachment to remove
         */
        void removeFileAttachment(int index);

        void onFilePickLoading();

        void onFilePickError();

        void highlightPostNo(int no);

        void showThread(Loadable loadable);

        ImagePickDelegate getImagePickDelegate();

        ChanThread getThread();

        void focusComment();

        void onUploadingProgress(int percent);

        void onFallbackToV1CaptchaView();

        void destroyCurrentAuthentication();
        
        void showAuthenticationError(String errorMessage);
    }
}
