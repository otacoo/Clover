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
package org.otacoo.chan.ui.captcha.v2;

import static org.otacoo.chan.Chan.injector;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.otacoo.chan.R;
import org.otacoo.chan.core.model.orm.Loadable;
import org.otacoo.chan.core.site.SiteAuthentication;
import org.otacoo.chan.ui.captcha.AuthenticationLayoutCallback;
import org.otacoo.chan.ui.captcha.AuthenticationLayoutInterface;
import org.otacoo.chan.ui.view.WrappingGridView;
import org.otacoo.chan.utils.Logger;

import okhttp3.OkHttpClient;

public class CaptchaNoJsLayoutV2 extends LinearLayout implements
        AuthenticationLayoutInterface,
        CaptchaNoJsPresenterV2.AuthenticationCallbacks {
    private static final String TAG = "CaptchaNoJsLayoutV2";

    private AuthenticationLayoutCallback callback;
    private CaptchaNoJsPresenterV2 presenter;

    private TextView statusText;
    private WrappingGridView imagesGrid;
    private Button verifyButton;
    private Button reloadButton;

    private CaptchaNoJsV2Adapter adapter;

    public CaptchaNoJsLayoutV2(Context context) {
        super(context);
        init(context);
    }

    public CaptchaNoJsLayoutV2(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public CaptchaNoJsLayoutV2(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);

        LayoutInflater.from(context).inflate(R.layout.layout_captcha_nojs_v2, this, true);

        statusText = findViewById(R.id.captcha_layout_v2_title);
        imagesGrid = findViewById(R.id.captcha_layout_v2_images_grid);
        verifyButton = findViewById(R.id.captcha_layout_v2_verify_button);
        reloadButton = findViewById(R.id.captcha_layout_v2_reload_button);

        adapter = new CaptchaNoJsV2Adapter(context);
        imagesGrid.setAdapter(adapter);

        verifyButton.setOnClickListener(v -> {
            try {
                presenter.verify(adapter.getCheckedImageIds());
            } catch (CaptchaNoJsPresenterV2.CaptchaNoJsV2Error e) {
                Logger.e(TAG, "Error while verifying captcha", e);
            }
        });

        reloadButton.setOnClickListener(v -> presenter.requestCaptchaInfo());

        OkHttpClient okHttpClient = injector().instance(OkHttpClient.class);
        this.presenter = new CaptchaNoJsPresenterV2(this, context, okHttpClient);
    }

    @Override
    public void initialize(Loadable loadable, AuthenticationLayoutCallback callback) {
        this.callback = callback;

        SiteAuthentication auth = loadable.site.actions().postAuthenticate();
        presenter.init(auth.siteKey, auth.baseUrl);

        presenter.requestCaptchaInfo();
    }

    @Override
    public void reset() {
        presenter.requestCaptchaInfo();
    }

    @Override
    public void hardReset() {
        reset();
    }

    @Override
    public boolean requireResetAfterComplete() {
        return true;
    }

    @Override
    public void onDestroy() {
        presenter.onDestroy();
        adapter.onDestroy();
    }

    @Override
    public void onCaptchaInfoParsed(CaptchaInfo captchaInfo) {
        post(() -> {
            if (captchaInfo.getCaptchaTitle() != null) {
                statusText.setText(captchaInfo.getCaptchaTitle().getTitle());
            }
            adapter.setImages(captchaInfo.getChallengeImages());
            imagesGrid.setVisibility(VISIBLE);
            verifyButton.setEnabled(true);
        });
    }

    @Override
    public void onCaptchaInfoParseError(Throwable error) {
        post(() -> {
            statusText.setText(R.string.thread_load_failed_network);
        });
    }

    @Override
    public void onVerificationDone(String verificationToken) {
        if (callback != null) {
            callback.onAuthenticationComplete(this, null, verificationToken);
        }
    }
}
