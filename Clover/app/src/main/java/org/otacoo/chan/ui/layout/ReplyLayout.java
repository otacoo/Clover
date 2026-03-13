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

import static org.otacoo.chan.Chan.inject;
import static org.otacoo.chan.ui.theme.ThemeHelper.theme;
import static org.otacoo.chan.utils.AndroidUtils.dp;
import static org.otacoo.chan.utils.AndroidUtils.fixSnackbarText;
import static org.otacoo.chan.utils.AndroidUtils.getAttrColor;
import static org.otacoo.chan.utils.AndroidUtils.getString;
import static org.otacoo.chan.utils.AndroidUtils.setRoundItemBackground;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.snackbar.Snackbar;

import org.otacoo.chan.R;
import org.otacoo.chan.core.model.ChanThread;
import org.otacoo.chan.core.model.orm.Loadable;
import org.otacoo.chan.core.presenter.ReplyPresenter;
import org.otacoo.chan.core.settings.ChanSettings;
import org.otacoo.chan.core.site.SiteAuthentication;
import org.otacoo.chan.core.site.http.Reply;
import org.otacoo.chan.ui.activity.ImagePickDelegate;
import org.otacoo.chan.ui.activity.StartActivity;
import org.otacoo.chan.ui.captcha.AuthenticationLayoutCallback;
import org.otacoo.chan.ui.captcha.AuthenticationLayoutInterface;
import org.otacoo.chan.ui.captcha.CaptchaLayout;
import org.otacoo.chan.ui.captcha.GenericWebViewAuthenticationLayout;
import org.otacoo.chan.ui.captcha.LynxchanBypassLayout;
import org.otacoo.chan.ui.captcha.LynxchanCaptchaLayout;
import org.otacoo.chan.ui.captcha.NewCaptchaLayout;
import org.otacoo.chan.ui.drawable.DropdownArrowDrawable;
import org.otacoo.chan.ui.helper.HintPopup;
import org.otacoo.chan.utils.Logger;
import org.otacoo.chan.ui.view.FloatingMenu;
import org.otacoo.chan.ui.view.FloatingMenuItem;
import org.otacoo.chan.ui.view.LoadView;
import org.otacoo.chan.ui.view.SelectionListeningEditText;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.ImageDecoder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ReplyLayout extends LoadView implements
        View.OnClickListener,
        View.OnLongClickListener,
        ReplyPresenter.ReplyPresenterCallback,
        TextWatcher,
        ImageDecoder.ImageDecoderCallback,
        SelectionListeningEditText.SelectionChangedListener {
    @Inject
    ReplyPresenter presenter;
    
    @Inject
    OkHttpClient okHttpClient;

    private ReplyLayoutCallback callback;
    private boolean newCaptcha;

    private AuthenticationLayoutInterface authenticationLayout;
    private boolean openingName;
    private boolean expanded = false;

    private boolean blockSelectionChange = false;

    // Progress view (when sending request to the server)
    private View progressLayout;
    private TextView currentProgress;

    // Reply views:
    private View replyInputLayout;
    private TextView message;
    private EditText name;
    private EditText subject;
    private Button flag;
    private EditText options;
    private EditText fileName;
    private LinearLayout nameOptions;
    private ViewGroup commentButtons;
    private Button commentQuoteButton;
    private Button commentSpoilerButton;
    private Button commentCodeButton;
    private Button commentMathButton;
    private Button commentEqnButton;
    private SelectionListeningEditText comment;
    private TextView commentCounter;
    private CheckBox spoiler;
    private HorizontalScrollView previewScroll;
    private LinearLayout previewHolder;
    private View previewSpacerLeft;
    private View previewSpacerRight;
    private ImageView preview;
    private TextView previewMessage;
    private ImageView attach;
    private ImageView more;
    private ImageView submit;
    private DropdownArrowDrawable moreDropdown;

    // Captcha views:
    private FrameLayout captchaContainer;
    private ImageView captchaHardReset;
    
    // Multi-file attachment support
    private List<Reply.FileAttachment> currentAttachments = new ArrayList<>();
    private int currentAttachmentMaxCount = 1;

    private Runnable closeMessageRunnable = new Runnable() {
        @Override
        public void run() {
            message.setVisibility(View.GONE);
        }
    };

    private final Handler cooldownUpdateHandler = new Handler(Looper.getMainLooper());
    private final Runnable cooldownUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (authenticationLayout instanceof NewCaptchaLayout) {
                NewCaptchaLayout ncl = (NewCaptchaLayout) authenticationLayout;
                if (ncl != null) {
                    int seconds = ncl.getCooldownRemainingSeconds();
                    int requestSeconds = ncl.getRequestCooldownRemainingSeconds();
                    int displaySeconds = Math.max(seconds, requestSeconds);
                    
                    if (displaySeconds > 0) {
                        String label = seconds >= requestSeconds ? "Post cooldown: " : "Request limit: ";
                        openMessage(true, true, label + displaySeconds + "s", false);
                        cooldownUpdateHandler.postDelayed(this, 1000);
                        return;
                    }
                }
            }
            openMessage(false, true, "", false);
        }
    };

    public ReplyLayout(Context context) {
        this(context, null);
    }

    public ReplyLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ReplyLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        inject(this);

        final LayoutInflater inflater = LayoutInflater.from(getContext());

        // Inflate reply input
        replyInputLayout = inflater.inflate(R.layout.layout_reply_input, this, false);
        message = replyInputLayout.findViewById(R.id.message);
        name = replyInputLayout.findViewById(R.id.name);
        subject = replyInputLayout.findViewById(R.id.subject);
        flag = replyInputLayout.findViewById(R.id.flag_button);
        options = replyInputLayout.findViewById(R.id.options);
        fileName = replyInputLayout.findViewById(R.id.file_name);
        nameOptions = replyInputLayout.findViewById(R.id.name_options);
        commentButtons = replyInputLayout.findViewById(R.id.comment_buttons);
        commentQuoteButton = replyInputLayout.findViewById(R.id.comment_quote);
        commentSpoilerButton = replyInputLayout.findViewById(R.id.comment_spoiler);
        commentCodeButton = replyInputLayout.findViewById(R.id.comment_code);
        commentMathButton = replyInputLayout.findViewById(R.id.comment_math);
        commentEqnButton = replyInputLayout.findViewById(R.id.comment_eqn);
        comment = replyInputLayout.findViewById(R.id.comment);
        commentCounter = replyInputLayout.findViewById(R.id.comment_counter);
        spoiler = replyInputLayout.findViewById(R.id.spoiler);
        previewScroll = replyInputLayout.findViewById(R.id.preview_scroll);
        previewHolder = replyInputLayout.findViewById(R.id.preview_holder);
        previewSpacerLeft = replyInputLayout.findViewById(R.id.preview_spacer_left);
        previewSpacerRight = replyInputLayout.findViewById(R.id.preview_spacer_right);
        preview = replyInputLayout.findViewById(R.id.preview);
        previewMessage = replyInputLayout.findViewById(R.id.preview_message);
        attach = replyInputLayout.findViewById(R.id.attach);
        more = replyInputLayout.findViewById(R.id.more);
        submit = replyInputLayout.findViewById(R.id.submit);

        progressLayout = inflater.inflate(R.layout.layout_reply_progress, this, false);
        currentProgress = progressLayout.findViewById(R.id.current_progress);

        // Setup reply layout views
        flag.setOnClickListener(v -> {
            Map<String, String> boardFlags = presenter.getBoardFlags();
            if (boardFlags.isEmpty()) return;
            List<String> sorted;
            if (boardFlags.containsKey("TWI")) {
                String[] sortedMLP = {"AJ","FL","PI","RD","RAR","TWI",
                        "4CC","AN","ANF","APB","AU","BS","BP","BM","BB","CL","CHE","CB","CO","CG",
                        "DD","DAY","DER","DT","DIS","FAU","FLE","GI","LI","LT","LY","MA","MAU",
                        "MIN","NI","NUR","OCT","PAR","PM","PC","PCE","PLU","QC","RLU","S1L","SCO",
                        "SHI","SIL","SP","SPI","STA","STL","SUN","SWB","TS","TX","VS","ZE",
                        "HT","IZ","PP","SPT","SS","ZS",
                        "TFA","TFO","TFP","TP","TFS","TFT","TFV","ADA","AB","SON","SUS",
                        "EQA","EQF","EQP","EQR","ERA","EQS","EQT","EQI"};
                //if (boardFlags.containsKey("HT")) {
                    boardFlags.put("HT", "G5 Hitch Trailblazer");
                    boardFlags.put("IZ", "G5 Izzy Moonbow");
                    boardFlags.put("PP", "G5 Pipp Petals");
                    boardFlags.put("SPT", "G5 Sprout");
                    boardFlags.put("SS", "G5 Sunny Starscout");
                    boardFlags.put("ZS", "G5 Zipp Storm");
                //}
                sorted = new ArrayList<>(Arrays.asList(sortedMLP));
                sorted.retainAll(boardFlags.keySet());
                if (sorted.size() < boardFlags.keySet().size()) {
                    List<String> temp = new ArrayList<>(boardFlags.keySet());
                    temp.removeAll(sorted);
                    sorted.addAll(temp);
                }
            } else {
                String[] keys = boardFlags.keySet().toArray(new String[0]);
                Arrays.sort(keys);
                sorted = Arrays.asList(keys);
            }

            List<FloatingMenuItem> items = new ArrayList<>(boardFlags.size()+1);
            items.add(new FloatingMenuItem(null, "No flag"));
            FloatingMenuItem selected = null;
            for (String key : sorted) {
                FloatingMenuItem flagItem = new FloatingMenuItem(key, boardFlags.get(key));
                if (key.contentEquals(flag.getText())) {
                    selected = flagItem;
                }
                items.add(flagItem);
            }
            FloatingMenu menu = new FloatingMenu(getContext(), flag, items);
            menu.setAnchor(flag, Gravity.CENTER, 0, 0);
            menu.setAdapter(new BaseAdapter() {
                @Override
                public int getCount() {
                    return items.size();
                }

                @Override
                public String getItem(int position) {
                    return items.get(position).getText();
                }

                @Override
                public long getItemId(int position) {
                    return position;
                }

                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    TextView textView = (TextView) (convertView != null
                            ? convertView
                            : LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.toolbar_menu_item, parent, false));
                    textView.setText(getItem(position));
                    return textView;
                }
            });
            menu.setSelectedItem(selected);
            menu.setCallback(new FloatingMenu.FloatingMenuCallback() {
                @Override
                public void onFloatingMenuItemClicked(FloatingMenu menu, FloatingMenuItem item) {
                    flag.setText((String) item.getId());
                }

                @Override
                public void onFloatingMenuDismissed(FloatingMenu menu) { }
            });
            menu.setPopupHeight(dp(300));
            menu.show();
        });
        commentQuoteButton.setOnClickListener(this);
        commentSpoilerButton.setOnClickListener(this);
        commentCodeButton.setOnClickListener(this);
        commentMathButton.setOnClickListener(this);
        commentEqnButton.setOnClickListener(this);

        comment.addTextChangedListener(this);
        comment.setSelectionChangedListener(this);

        previewHolder.setOnClickListener(this);

        moreDropdown = new DropdownArrowDrawable(dp(16), dp(16), true,
                getAttrColor(getContext(), R.attr.dropdown_dark_color),
                getAttrColor(getContext(), R.attr.dropdown_dark_pressed_color));
        more.setImageDrawable(moreDropdown);
        setRoundItemBackground(more);
        more.setOnClickListener(this);

        theme().imageDrawable.apply(attach);
        setRoundItemBackground(attach);
        attach.setOnClickListener(this);
        attach.setOnLongClickListener(this);

        theme().sendDrawable.apply(submit);
        setRoundItemBackground(submit);
        submit.setOnClickListener(this);

        // Inflate captcha layout
        captchaContainer = (FrameLayout) inflater.inflate(R.layout.layout_reply_captcha, this, false);
        captchaHardReset = captchaContainer.findViewById(R.id.reset);

        // Setup captcha layout views
        captchaContainer.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        theme().refreshDrawable.apply(captchaHardReset);
        setRoundItemBackground(captchaHardReset);
        captchaHardReset.setOnClickListener(this);

        setView(replyInputLayout);

        // Presenter
        presenter.create(this);
    }

    public void setCallback(ReplyLayoutCallback callback) {
        this.callback = callback;
    }

    public ReplyPresenter getPresenter() {
        return presenter;
    }

    public void onOpen(boolean open) {
        presenter.onOpen(open);
    }

    public void bindLoadable(Loadable loadable) {
        presenter.bindLoadable(loadable);
    }

    public void cleanup() {
        presenter.unbindLoadable();
        removeCallbacks(closeMessageRunnable);
    }

    public boolean onBack() {
        return presenter.onBack();
    }

    private void setWrap(boolean wrap) {
        setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT,
                wrap ? LayoutParams.WRAP_CONTENT : LayoutParams.MATCH_PARENT
        ));
    }

    @Override
    public void onClick(View v) {
        if (v == more) {
            presenter.onMoreClicked();
        } else if (v == attach) {
            presenter.onAttachClicked();
        } else if (v == submit) {
            presenter.onSubmitClicked();
        } else if (v == previewHolder) {
            AndroidUtils.hideKeyboard(this);
            callback.showImageReencodingWindow();
        } else if (v == captchaHardReset) {
            if (authenticationLayout != null) {
                authenticationLayout.hardReset();
            }
        } else if (v == commentQuoteButton) {
            presenter.commentQuoteClicked();
        } else if (v == commentSpoilerButton) {
            presenter.commentSpoilerClicked();
        } else if (v == commentCodeButton) {
            presenter.commentCodeClicked();
        } else if (v == commentMathButton) {
            presenter.commentMathClicked();
        } else if (v == commentEqnButton) {
            presenter.commentEqnClicked();
        }
    }

    @Override
    public boolean onLongClick(View v) {
        try {
            Context context = getContext();
            ClipboardManager clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData primary = clipboardManager.getPrimaryClip();
            String clipboardContent = primary != null ? primary.getItemAt(0).coerceToText(context).toString() : "";
            final URL clipboardURL = new URL(clipboardContent);
            final File cacheFile = new File(context.getCacheDir(), "picked_file_dl");

            Request request = new Request.Builder().url(clipboardURL).build();
            final Handler handler = new Handler(Looper.getMainLooper());
            okHttpClient.newCall(request).enqueue(new Callback() {

                @Override
                public void onFailure(Call call, IOException e) {
                    handler.post(() -> Toast.makeText(getContext(), "URL download failed", Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    int total = 0;
                    if (response.isSuccessful()) {
                        InputStream is = response.body().byteStream();
                        OutputStream os = new FileOutputStream(cacheFile);
                        int read;
                        byte[] buffer = new byte[8192];
                        while ((read = is.read(buffer)) != -1) {
                            total += read;
                            if (total > 26214400) { // 25 MB
                                total = -1;
                                break;
                            } else {
                                os.write(buffer, 0, read);
                            }
                        }
                        os.close();
                    }
                    response.close();

                    if (total > 0) {
                        handler.post(() -> {
                            String[] filenameParts = clipboardURL.getFile().split("\\?")[0].split("/");
                            String filename = filenameParts[filenameParts.length - 1].trim();
                            if (filename.length() == 0) {
                                filename = "file";
                            }
                            presenter.onFilePicked(filename, cacheFile);
                            //Toast.makeText(getContext(), "URL download succeeded", Toast.LENGTH_SHORT).show();
                        });
                    } else {
                        handler.post(() -> Toast.makeText(getContext(), "URL download failed", Toast.LENGTH_SHORT).show());
                    }
                }

            });
            Toast.makeText(getContext(), "Downloading URL...", Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return true;
    }

    @Override
    public void initializeAuthentication(Loadable loadable,
                                         SiteAuthentication authentication,
                                         AuthenticationLayoutCallback callback) {
        // If authentication type changed, destroy old layout first
        if (authenticationLayout != null) {
            // Check if the current layout matches the requested type
            boolean typeMatches = false;
            switch (authentication.type) {
                case CAPTCHA2:
                    typeMatches = authenticationLayout instanceof CaptchaLayout;
                    break;
                case GENERIC_WEBVIEW:
                    typeMatches = authenticationLayout instanceof GenericWebViewAuthenticationLayout;
                    break;
                case NEW_CAPTCHA:
                    typeMatches = authenticationLayout instanceof NewCaptchaLayout;
                    break;
                case LYNXCHAN_CAPTCHA:
                    typeMatches = authenticationLayout instanceof LynxchanCaptchaLayout;
                    break;
                case LYNXCHAN_BYPASS:
                    typeMatches = authenticationLayout instanceof LynxchanBypassLayout;
                    break;
            }
            
            if (!typeMatches) {
                authenticationLayout.onDestroy();
                captchaContainer.removeView((View) authenticationLayout);
                authenticationLayout = null;
            }
        }
        
        if (authenticationLayout == null) {
            switch (authentication.type) {
                case CAPTCHA2: {
                    authenticationLayout = new CaptchaLayout(getContext());
                    break;
                }
                case GENERIC_WEBVIEW: {
                    GenericWebViewAuthenticationLayout view = new GenericWebViewAuthenticationLayout(getContext());

                    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                            LayoutParams.MATCH_PARENT,
                            LayoutParams.MATCH_PARENT
                    );
                    // params.setMargins(dp(8), dp(8), dp(8), dp(200));
                    view.setLayoutParams(params);

                    authenticationLayout = view;
                    Logger.i("ReplyLayout", "Created GenericWebViewAuthenticationLayout");
                    break;
                }
                case NEW_CAPTCHA: {
                    authenticationLayout = new NewCaptchaLayout(getContext());
                    Logger.i("ReplyLayout", "Created NewCaptchaLayout (NEW_CAPTCHA)");
                    break;
                }
                case LYNXCHAN_CAPTCHA: {
                    authenticationLayout = new LynxchanCaptchaLayout(getContext());
                    Logger.i("ReplyLayout", "Created LynxchanCaptchaLayout");
                    break;
                }
                case LYNXCHAN_BYPASS: {
                    authenticationLayout = new LynxchanBypassLayout(getContext());
                    Logger.i("ReplyLayout", "Created LynxchanBypassLayout");
                    break;
                }
                case NONE:
                default: {
                    throw new IllegalArgumentException();
                }
            }

            captchaContainer.addView((View) authenticationLayout, 0);
        }

        AndroidUtils.hideKeyboard(this);

        authenticationLayout.initialize(loadable, callback);
        authenticationLayout.reset();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void setPage(ReplyPresenter.Page page, boolean animate) {
        if (page != ReplyPresenter.Page.AUTHENTICATION && getContext() instanceof Activity) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                //noinspection deprecation
                ((Activity) getContext()).getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        }
        switch (page) {
            case LOADING:
                setWrap(true);
                View progressBar = setView(progressLayout);
                progressBar.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, dp(100)));

                //reset progress to 0 upon uploading start
                currentProgress.setVisibility(View.INVISIBLE);
                break;
            case INPUT:
                setView(replyInputLayout);
                setWrap(!presenter.isExpanded());
                
                // Show cooldown remaining persistent message while on INPUT page
                cooldownUpdateHandler.removeCallbacks(cooldownUpdateRunnable);
                if (AndroidUtils.getPreferences().getBoolean("preference_4chan_cooldown_toast", false) && authenticationLayout instanceof NewCaptchaLayout) {
                    NewCaptchaLayout ncl = (NewCaptchaLayout) authenticationLayout;
                    if (ncl.onCooldownNow()) {
                        cooldownUpdateHandler.post(cooldownUpdateRunnable);
                    }
                }
                break;
            case AUTHENTICATION:
                setWrap(false);
                cooldownUpdateHandler.removeCallbacks(cooldownUpdateRunnable);
                openMessage(false, true, "", false);
                
                // If the cooldown just ended before Opening authentication, trigger a reset to reload the fresh captcha
                if (authenticationLayout instanceof NewCaptchaLayout) {
                    NewCaptchaLayout ncl = (NewCaptchaLayout) authenticationLayout;
                    if (!ncl.onCooldownNow() && ncl.isShowingCooldownUI()) {
                        ncl.reset();
                    }
                }

                if (ChanSettings.toolbarBottom.get()) {
                    captchaContainer.setPadding(0, 0, 0,
                            getResources().getDimensionPixelSize(R.dimen.toolbar_height));
                } else {
                    captchaContainer.setPadding(0, 0, 0, 0);
                }
                setView(captchaContainer);
                View focus = getRootView() != null ? getRootView().findFocus() : null;
                if (focus != null) focus.clearFocus();
                AndroidUtils.hideKeyboard(this);
                if (getContext() instanceof Activity) {
                    ((Activity) getContext()).getWindow().setSoftInputMode(
                            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
                }
                // Something (e.g. WebView) may re-show the keyboard; hide again after delays
                final View replyView = this;
                for (final long delayMs : new long[] { 80, 200, 400 }) {
                    replyView.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (replyView.getWindowToken() != null) {
                                AndroidUtils.hideKeyboard(replyView);
                            }
                        }
                    }, delayMs);
                }
                break;
        }

        if (page != ReplyPresenter.Page.AUTHENTICATION && authenticationLayout != null) {
            if (!(authenticationLayout instanceof NewCaptchaLayout)) {
                AndroidUtils.removeFromParentView((View) authenticationLayout);
                authenticationLayout = null;
            }
        }
    }

    @Override
    public void resetAuthentication() {
        authenticationLayout.reset();
    }

    @Override
    public void destroyCurrentAuthentication() {
        if (authenticationLayout == null) {
            return;
        }

        authenticationLayout.onDestroy();
        captchaContainer.removeView((View) authenticationLayout);
        authenticationLayout = null;
    }

    @Override
    public void showAuthenticationError(String errorMessage) {
        if (authenticationLayout instanceof NewCaptchaLayout) {
            ((NewCaptchaLayout) authenticationLayout).showAuthenticationError(errorMessage);
        }
    }

    @Override
    public void loadDraftIntoViews(Reply draft) {
        name.setText(draft.name);
        subject.setText(draft.subject);
        flag.setText(draft.flag);
        options.setText(draft.options);
        blockSelectionChange = true;
        comment.setText(draft.comment);
        comment.setSelection(draft.selectionStart, draft.selectionEnd);
        blockSelectionChange = false;
        fileName.setText(draft.fileName);
        spoiler.setChecked(draft.spoilerImage);
    }

    @Override
    public void loadViewsIntoDraft(Reply draft) {
        draft.name = name.getText().toString();
        draft.subject = subject.getText().toString();
        draft.flag = flag.getText().toString();
        draft.options = options.getText().toString();
        draft.comment = comment.getText().toString();
        draft.selectionStart = comment.getSelectionStart();
        draft.selectionEnd = comment.getSelectionEnd();
        draft.fileName = fileName.getText().toString();
        draft.spoilerImage = spoiler.isChecked();
    }

    @Override
    public void openMessage(boolean open, boolean animate, String text, boolean autoHide) {
        removeCallbacks(closeMessageRunnable);
        message.setText(text);
        message.setVisibility(open ? View.VISIBLE : View.GONE);

        if (autoHide) {
            postDelayed(closeMessageRunnable, 5000);
        }
    }

    @Override
    public void onPosted() {
        if (authenticationLayout != null) {
            authenticationLayout.onDestroy();
            captchaContainer.removeView((View) authenticationLayout);
            authenticationLayout = null;
        }
        Snackbar postSuccessfulNotification = Snackbar.make(this, R.string.reply_success, 5000);
        postSuccessfulNotification.setAction(R.string.reply_success_recover, new OnClickListener() {
            @Override
            public void onClick(View v) {
                presenter.reloadLastReply();
                callback.openReply(true);
            }
        }).show();
        fixSnackbarText(getContext(), postSuccessfulNotification);
        callback.openReply(false);
        callback.requestNewPostLoad();
    }

    @Override
    public void setCommentHint(String hint) {
        comment.setHint(hint);
    }

    @Override
    public void showCommentCounter(boolean show) {
        commentCounter.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @Override
    public void showFlag(boolean show) {
        flag.setVisibility(show ? View.VISIBLE : View.GONE);
        // If the board has flags, always keep the name/options row visible so the
        // flag selector is accessible without pressing "More".
        if (show) {
            nameOptions.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
        
        setWrap(!expanded);

        comment.setMaxLines(expanded ? 500 : 6);

        // Refresh multi-file attachments display when expanding/collapsing
        if (!currentAttachments.isEmpty()) {
            openFileAttachments(currentAttachments, currentAttachments.size(), currentAttachmentMaxCount);
        }

        ValueAnimator animator = ValueAnimator.ofFloat(expanded ? 0f : 1f, expanded ? 1f : 0f);
        animator.setInterpolator(new DecelerateInterpolator(2f));
        animator.setDuration(400);
        animator.addUpdateListener(animation ->
                moreDropdown.setRotation((float) animation.getAnimatedValue()));
        animator.start();
    }

    @Override
    public void openNameOptions(boolean open) {
        openingName = open;
        // Keep the row visible if the flag button is showing (board has custom flags).
        boolean flagVisible = flag.getVisibility() == View.VISIBLE;
        nameOptions.setVisibility((open || flagVisible) ? View.VISIBLE : View.GONE);
    }

    @Override
    public void openSubject(boolean open) {
        subject.setVisibility(open ? View.VISIBLE : View.GONE);
    }

    @Override
    public void openCommentQuoteButton(boolean open) {
        commentQuoteButton.setVisibility(open ? View.VISIBLE : View.GONE);
    }

    @Override
    public void openCommentSpoilerButton(boolean open) {
        commentSpoilerButton.setVisibility(open ? View.VISIBLE : View.GONE);
    }

    @Override
    public void openCommentCodeButton(boolean open) {
        commentCodeButton.setVisibility(open ? View.VISIBLE : View.GONE);
    }

    @Override
    public void openCommentMathButton(boolean open) {
        commentMathButton.setVisibility(open ? View.VISIBLE : View.GONE);
    }

    @Override
    public void openCommentEqnButton(boolean open) {
        commentEqnButton.setVisibility(open ? View.VISIBLE : View.GONE);
    }

    @Override
    public void openFileName(boolean open) {
        fileName.setVisibility(open ? View.VISIBLE : View.GONE);
    }

    @Override
    public void setFileName(String name) {
        fileName.setText(name);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void updateCommentCount(int count, int maxCount, boolean over) {
        commentCounter.setText(count + "/" + maxCount);
        //noinspection ResourceAsColor
        commentCounter.setTextColor(over ? 0xffff0000 : getAttrColor(getContext(), R.attr.text_color_secondary));
    }

    public void focusComment() {
        comment.post(() -> AndroidUtils.requestViewAndKeyboardFocus(comment));
    }

    @Override
    public void onFallbackToV1CaptchaView() {
        // fallback to v1 captcha window
        presenter.switchPage(ReplyPresenter.Page.AUTHENTICATION, true);
    }

    @Override
    public void openPreview(boolean show, File previewFile) {
        if (show) {
            theme().clearDrawable.apply(attach);
        } else {
            theme().imageDrawable.apply(attach);
        }

        if (show) {
            if (previewHolder.getParent() != null && previewHolder.getParent() != previewScroll) {
                ((ViewGroup) previewHolder.getParent()).removeView(previewHolder);
            }
            if (previewHolder.getParent() == null) {
                previewScroll.addView(previewHolder);
            }

            previewScroll.setVisibility(View.VISIBLE);
            previewHolder.setVisibility(View.VISIBLE);
            previewHolder.removeAllViews();
            
            previewSpacerLeft.setVisibility(View.VISIBLE);
            previewSpacerRight.setVisibility(View.VISIBLE);
            
            previewHolder.addView(previewSpacerLeft);
            previewHolder.addView(preview);
            previewHolder.addView(previewSpacerRight);
            
            ImageDecoder.decodeFileOnBackgroundThread(previewFile, dp(400), dp(300), this);
        } else {
            // Restore preview view to its original container if it was moved
            if (previewHolder.getParent() != null && previewHolder.getParent() != previewScroll) {
                ((ViewGroup) previewHolder.getParent()).removeView(previewHolder);
            }
            if (previewHolder.getParent() == null) {
                previewScroll.addView(previewHolder);
            }
            
            spoiler.setVisibility(View.GONE);
            previewScroll.setVisibility(View.GONE);
            previewHolder.setVisibility(View.GONE);
            previewMessage.setVisibility(View.GONE);
        }
    }

    @Override
    public void openPreviewMessage(boolean show, String message) {
        previewMessage.setVisibility(show ? VISIBLE : GONE);
        previewMessage.setText(message);
    }

    @Override
    public void openSpoiler(boolean show, boolean checked) {
        spoiler.setVisibility(show ? View.VISIBLE : View.GONE);
        spoiler.setChecked(checked);
    }

    @Override
    public void openFileAttachments(List<Reply.FileAttachment> attachments, int currentCount, int maxCount) {
        currentAttachments = new ArrayList<>(attachments);
        currentAttachmentMaxCount = maxCount;

        if (attachments.isEmpty()) {
            theme().imageDrawable.apply(attach);
        } else {
            theme().clearDrawable.apply(attach);
        }
        
        if (!attachments.isEmpty()) {
            // Hide the global filename field for multi-file mode
            fileName.setVisibility(View.GONE);
            
            // Clear previous previews - remove all child views
            previewHolder.removeAllViews();
            
            // Get content container early
            LinearLayout contentContainer = (LinearLayout)((ScrollView)replyInputLayout).getChildAt(0);
            contentContainer = (LinearLayout)contentContainer.getChildAt(0);
            
            // AGGRESSIVE removal: remove from both possible parents to ensure clean state
            // Check previewScroll
            if (previewScroll.getChildCount() > 0) {
                for (int i = previewScroll.getChildCount() - 1; i >= 0; i--) {
                    if (previewScroll.getChildAt(i) == previewHolder) {
                        previewScroll.removeViewAt(i);
                        break;
                    }
                }
            }
            
            // Check contentContainer
            if (contentContainer.getChildCount() > 0) {
                for (int i = contentContainer.getChildCount() - 1; i >= 0; i--) {
                    if (contentContainer.getChildAt(i) == previewHolder) {
                        contentContainer.removeViewAt(i);
                        break;
                    }
                }
            }
            
            // Set orientation based on expanded state
            int orientation = expanded ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL;
            previewHolder.setOrientation(orientation);
            
            // Update previewHolder width and height based on expanded state
            ViewGroup.LayoutParams holderParams = previewHolder.getLayoutParams();
            if (holderParams != null) {
                holderParams.width = expanded ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
                holderParams.height = expanded ? ViewGroup.LayoutParams.WRAP_CONTENT : dp(100);
                previewHolder.setLayoutParams(holderParams);
            }
            
            // Adjust scroll view based on expanded state
            if (expanded) {
                previewScroll.setVisibility(View.GONE);
            } else {
                previewScroll.setVisibility(View.VISIBLE);
            }
            
            // Hide spacers in multi-file mode
            previewSpacerLeft.setVisibility(View.GONE);
            previewSpacerRight.setVisibility(View.GONE);
            
            if (expanded) {
                // Add it before the comment_counter or after spoiler
                int index = contentContainer.indexOfChild(spoiler);
                contentContainer.addView(previewHolder, index + 1);
            } else {
                // In collapsed mode, add to previewScroll
                previewScroll.addView(previewHolder);
            }

            if (expanded) {
                // Expanded mode: show images vertically with full controls
                for (int i = 0; i < attachments.size(); i++) {
                    final Reply.FileAttachment attachment = attachments.get(i);
                    final int index = i;
                    
                    // Create vertical container for each file
                    LinearLayout fileContainer = new LinearLayout(getContext());
                    fileContainer.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    containerParams.setMarginStart(dp(8));
                    containerParams.setMarginEnd(dp(8));
                    containerParams.topMargin = dp(8);
                    containerParams.bottomMargin = dp(8);
                    fileContainer.setLayoutParams(containerParams);
                    
                    // Image view
                    final ImageView imageView = new ImageView(getContext());
                    imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                    LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(200)
                    );
                    imageView.setLayoutParams(imageParams);
                    
                    // Add click listener to show reencode options
                    imageView.setOnClickListener(v -> {
                        AndroidUtils.hideKeyboard(ReplyLayout.this);
                        presenter.onImageAttachmentClicked(index);
                        callback.showImageReencodingWindow();
                    });
                    
                    // Decode and display image - create separate closure for each iteration
                    decodeImageAsync(attachment.file, dp(400), dp(300), (file, bitmap) -> {
                        if (bitmap != null) {
                            imageView.setImageBitmap(bitmap);
                        }
                    });
                    
                    fileContainer.addView(imageView);
                    
                    // Filename Edit - show under every image
                    EditText filenameEdit = new EditText(getContext());
                    String displayName = (attachment.fileName != null && !attachment.fileName.isEmpty()) ? 
                            attachment.fileName : attachment.file.getName();
                    filenameEdit.setText(displayName);
                    filenameEdit.setHint(R.string.reply_file_name);
                    filenameEdit.setTextSize(14);
                    filenameEdit.setPadding(dp(4), dp(8), dp(4), dp(8));
                    filenameEdit.addTextChangedListener(new TextWatcher() {
                        @Override
                        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                        @Override
                        public void onTextChanged(CharSequence s, int start, int before, int count) {}
                        @Override
                        public void afterTextChanged(Editable s) {
                            presenter.setFileAttachmentFileName(index, s.toString());
                        }
                    });
                    fileContainer.addView(filenameEdit);
                    
                    // Control buttons
                    LinearLayout buttonsContainer = new LinearLayout(getContext());
                    buttonsContainer.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    buttonsContainer.setLayoutParams(buttonsParams);
                    
                    CheckBox spoilerCheckbox = new CheckBox(getContext());
                    spoilerCheckbox.setText("Spoiler");
                    spoilerCheckbox.setChecked(attachment.spoiler);
                    spoilerCheckbox.setOnCheckedChangeListener((buttonView, isChecked) ->
                        presenter.setFileAttachmentSpoiler(index, isChecked)
                    );
                    LinearLayout.LayoutParams checkboxParams = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    checkboxParams.weight = 1;
                    buttonsContainer.addView(spoilerCheckbox, checkboxParams);
                    
                    Button removeButton = new Button(getContext(), null, android.R.attr.buttonBarButtonStyle);
                    removeButton.setLayoutParams(new LinearLayout.LayoutParams(dp(50), dp(50)));
                    removeButton.setText("✕");
                    removeButton.setTextSize(18);
                    removeButton.setAllCaps(false);
                    removeButton.setPadding(0, 0, 0, 0);
                    removeButton.setOnClickListener(v -> presenter.removeFileAttachment(index));
                    buttonsContainer.addView(removeButton);
                    
                    fileContainer.addView(buttonsContainer);
                    
                    previewHolder.addView(fileContainer);
                }
            } else {
                // Collapsed mode: show thumbnails horizontally
                int thumbnailSize = dp(100);
                
                for (int i = 0; i < attachments.size(); i++) {
                    final Reply.FileAttachment attachment = attachments.get(i);
                    final int index = i;
                    
                    // Image thumbnail
                    final ImageView imageView = new ImageView(getContext());
                    imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                            thumbnailSize,
                            thumbnailSize
                    );
                    imageParams.setMarginStart(dp(2));
                    imageParams.setMarginEnd(dp(2));
                    imageView.setLayoutParams(imageParams);
                    
                    // Add click listener for reencode
                    imageView.setOnClickListener(v -> {
                        AndroidUtils.hideKeyboard(ReplyLayout.this);
                        presenter.onImageAttachmentClicked(index);
                        callback.showImageReencodingWindow();
                    });
                    
                    // Decode and display thumbnail - separate closure for each iteration
                    decodeImageAsync(attachment.file, thumbnailSize, thumbnailSize, (file, bitmap) -> {
                        if (bitmap != null) {
                            imageView.setImageBitmap(bitmap);
                        }
                    });
                    
                    previewHolder.addView(imageView);
                }
            }
            
            if (!expanded) previewScroll.setVisibility(View.VISIBLE);
            previewHolder.setVisibility(View.VISIBLE);
            previewMessage.setText(currentCount + "/" + maxCount);
            previewMessage.setVisibility(View.VISIBLE);
            showReencodeImageHint();
        } else {
            // Restore preview view to its original container if it was moved
            // Get content container
            LinearLayout contentContainer = (LinearLayout)((ScrollView)replyInputLayout).getChildAt(0);
            contentContainer = (LinearLayout)contentContainer.getChildAt(0);
            
            // AGGRESSIVE removal: remove from both possible parents
            if (previewScroll.getChildCount() > 0) {
                for (int i = previewScroll.getChildCount() - 1; i >= 0; i--) {
                    if (previewScroll.getChildAt(i) == previewHolder) {
                        previewScroll.removeViewAt(i);
                        break;
                    }
                }
            }
            
            if (contentContainer.getChildCount() > 0) {
                for (int i = contentContainer.getChildCount() - 1; i >= 0; i--) {
                    if (contentContainer.getChildAt(i) == previewHolder) {
                        contentContainer.removeViewAt(i);
                        break;
                    }
                }
            }
            
            previewHolder.removeAllViews();
            previewScroll.setVisibility(View.GONE);
            previewHolder.setVisibility(View.GONE);
            previewMessage.setVisibility(View.GONE);
            
            fileName.setText("");
            fileName.setVisibility(View.GONE);
        }
    }
    
    /**
     * Asynchronously decode an image on background thread.
     * Creates a separate closure to avoid capturing loop variables.
     */
    private void decodeImageAsync(File file, int reqWidth, int reqHeight,
                                  ImageDecoder.ImageDecoderCallback callback) {
        ImageDecoder.decodeFileOnBackgroundThread(file, reqWidth, reqHeight, callback);
    }

    @Override
    public void removeFileAttachment(int index) {
        presenter.removeFileAttachment(index);
    }

    @Override
    public void onImageBitmap(File file, Bitmap bitmap) {
        if (bitmap != null) {
            preview.setImageBitmap(bitmap);
            if (!expanded) previewScroll.setVisibility(View.VISIBLE);
            previewHolder.setVisibility(View.VISIBLE);

            showReencodeImageHint();
        } else {
            openPreviewMessage(true, getString(R.string.reply_no_preview));
        }
    }

    @Override
    public void onFilePickLoading() {
        // TODO
    }

    @Override
    public void onFilePickError() {
        Toast.makeText(getContext(), R.string.reply_file_open_failed, Toast.LENGTH_LONG).show();
    }

    @Override
    public void highlightPostNo(int no) {
        callback.highlightPostNo(no);
    }

    @Override
    public void onSelectionChanged(int selStart, int selEnd) {
        if (!blockSelectionChange) {
            presenter.onSelectionChanged();
        }
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void afterTextChanged(Editable s) {
        presenter.onCommentTextChanged(comment.getText());
    }

    @Override
    public void showThread(Loadable loadable) {
        callback.showThread(loadable);
    }

    @Override
    public void onUploadingProgress(int percent) {
        if (currentProgress != null) {
            if (percent <= 0) {
                currentProgress.setVisibility(View.VISIBLE);
            }

            currentProgress.setText(String.format(Locale.getDefault(), "%d", percent));
        }
    }

    @Override
    public ImagePickDelegate getImagePickDelegate() {
        return ((StartActivity) getContext()).getImagePickDelegate();
    }

    @Override
    public ChanThread getThread() {
        return callback.getThread();
    }

    public void onImageOptionsApplied(Reply reply) {
        // Update the filename EditText. Otherwise it will change back the image name upon changing
        // the message comment (because of the textwatcher)
        fileName.setText(reply.fileName);

        presenter.onImageOptionsApplied(reply);
    }

    private void showReencodeImageHint() {
        if (!ChanSettings.reencodeHintShown.get()) {
            String message = getContext().getString(R.string.click_image_for_extra_options);
            HintPopup hintPopup = HintPopup.show(getContext(), preview, message, dp(-32), dp(16));
            hintPopup.wiggle();

            ChanSettings.reencodeHintShown.set(true);
        }
    }

    public interface ReplyLayoutCallback {
        void highlightPostNo(int no);

        void openReply(boolean open);

        void showThread(Loadable loadable);

        void requestNewPostLoad();

        ChanThread getThread();

        void showImageReencodingWindow();
    }
}
