/*
 * Clover - 4chan browser https://github.com/Floens/Clover/
 * Copyright (C) 2014  Floens
 * Copyright (C) 2026  otacoo https://github.com/otacoo/Clover
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

import static org.otacoo.chan.utils.AndroidUtils.getAttrColor;

import static org.otacoo.chan.utils.AndroidUtils.dp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.otacoo.chan.R;
import org.otacoo.chan.controller.Controller;
import org.otacoo.chan.ui.toolbar.ToolbarMenuSubItem;
import org.otacoo.chan.utils.AndroidUtils;
import org.otacoo.chan.utils.IOUtils;
import org.otacoo.chan.utils.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class LogsController extends Controller {
    private static final String TAG = "LogsController";

    private static final String[] QUICK_FILTERS = {"Clover", "otacoo", "E/", "W/", "chan4", "Captcha"};

    private TextView logTextView;
    private TextView lineCountView;
    private EditText filterEdit;

    private List<String> allLines = new ArrayList<>();
    private String activeFilter = "";

    public LogsController(Context context) {
        super(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        navigation.setTitle(R.string.settings_logs_screen);
        navigation.buildMenu().withOverflow()
                .withSubItem(R.string.settings_logs_copy, this::copyLogsClicked)
                .withSubItem(R.string.settings_logs_export, this::exportLogsClicked)
                .build().build();

        int dp4  = dp(4);
        int dp8  = dp(8);
        int dp12 = dp(12);

        int backColor = getAttrColor(context, R.attr.backcolor);
        int backColor2 = getAttrColor(context, R.attr.backcolor_secondary);
        int textPrimary = getAttrColor(context, R.attr.text_color_primary);
        int textSecondary = getAttrColor(context, R.attr.text_color_secondary);
        int textHint = getAttrColor(context, R.attr.text_color_hint);

        // Root
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(backColor);

        // ── Filter bar ──────────────────────────────────────────────────
        LinearLayout filterRow = new LinearLayout(context);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setPadding(dp8, dp4, dp8, dp4);
        filterRow.setBackgroundColor(backColor2);

        filterEdit = new EditText(context);
        filterEdit.setHint("Filter…");
        filterEdit.setTextSize(13f);
        filterEdit.setSingleLine(true);
        filterEdit.setBackgroundColor(backColor);
        filterEdit.setPadding(dp8, dp4, dp8, dp4);
        filterEdit.setTextColor(textPrimary);
        filterEdit.setHintTextColor(textHint);
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        filterEdit.setLayoutParams(editLp);
        filterRow.addView(filterEdit);

        Button clearBtn = new Button(context);
        clearBtn.setText("✕");
        clearBtn.setTextSize(13f);
        clearBtn.setPadding(dp8, 0, dp8, 0);
        clearBtn.setOnClickListener(v -> filterEdit.setText(""));
        filterRow.addView(clearBtn);

        root.addView(filterRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── Quick-filter chips ──────────────────────────────────────────
        HorizontalScrollView chipScroll = new HorizontalScrollView(context);
        chipScroll.setBackgroundColor(backColor2);
        LinearLayout chipRow = new LinearLayout(context);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(dp4, dp4, dp4, dp4);

        for (String preset : QUICK_FILTERS) {
            Button chip = new Button(context);
            chip.setText(preset);
            chip.setTextSize(11f);
            chip.setPadding(dp8, 0, dp8, 0);
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
            chipLp.setMargins(dp4, 0, dp4, 0);
            chip.setLayoutParams(chipLp);
            chip.setOnClickListener(v -> filterEdit.setText(preset));
            chipRow.addView(chip);
        }

        chipScroll.addView(chipRow);
        root.addView(chipScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── Line-count bar ──────────────────────────────────────────────
        lineCountView = new TextView(context);
        lineCountView.setTextSize(10f);
        lineCountView.setTextColor(textSecondary);
        lineCountView.setPadding(dp8, dp4, dp8, dp4);
        lineCountView.setBackgroundColor(backColor);
        root.addView(lineCountView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── Log text ────────────────────────────────────────────────────
        ScrollView scrollView = new ScrollView(context);
        logTextView = new TextView(context);
        logTextView.setTypeface(Typeface.MONOSPACE);
        logTextView.setTextSize(11f);
        logTextView.setTextColor(textPrimary);
        logTextView.setPadding(dp8, dp4, dp8, dp12);
        logTextView.setTextIsSelectable(true);
        scrollView.addView(logTextView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        view = root;

        // ── Wire filter ─────────────────────────────────────────────────
        filterEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                activeFilter = s.toString().toLowerCase();
                applyFilter();
            }
        });

        new Thread(this::loadLogs, "log-load").start();
    }

    private void exportLogsClicked(ToolbarMenuSubItem item) {
        Toast.makeText(context, "Exporting filtered logs…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String logOutput = getFilteredAndAnonymizedLogs();

                java.text.SimpleDateFormat sdf =
                        new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US);
                String timestamp = sdf.format(new java.util.Date());
                String fileName = "clover_log_" + timestamp + ".txt";

                // Log exports always go to Downloads. If that fails (e.g. due to permissions), they fall back to app-private storage.
                File logFile = writeLog(fileName, logOutput);

                final String savedPath = logFile.getAbsolutePath();
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(context,
                                "Log saved to " + savedPath,
                                Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                Logger.e(TAG, "Export failed", e);
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(context, R.string.settings_logs_export_failed,
                                Toast.LENGTH_SHORT).show());
            }
        }, "log-export").start();
    }

    private String getFilteredAndAnonymizedLogs() {
        StringBuilder sb = new StringBuilder();
        for (String line : allLines) {
            if (shouldIgnoreLine(line)) {
                continue;
            }

            if (activeFilter.isEmpty() || line.toLowerCase().contains(activeFilter)) {
                sb.append(anonymizeLog(line)).append('\n');
            }
        }
        return sb.toString();
    }

    private boolean shouldIgnoreLine(String line) {
        if (line == null) return true;
        // Ignore messages about StrictMode
        if (line.contains("StrictMode")) return true;
        // Ignore framework warnings about legacy API access (greylist/blacklist noise)
        return line.contains("Accessing hidden");
    }

    private File writeLog(String fileName, String logOutput) throws IOException {
        String header = "Clover log export — PID " + android.os.Process.myPid() + "\n"
                + "Device: " + android.os.Build.MANUFACTURER
                + " " + android.os.Build.MODEL
                + ", Android " + android.os.Build.VERSION.RELEASE + "\n\n";

        // Primary: public Downloads folder
        File downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        //noinspection ResultOfMethodCallIgnored
        downloadsDir.mkdirs();
        File primary = new File(downloadsDir, fileName);
        try (FileWriter fw = new FileWriter(primary, false)) {
            fw.write(header);
            fw.write(logOutput);
            return primary;
        } catch (SecurityException | IOException e) {
            Logger.w(TAG, "Could not write to Downloads, falling back to app-private dir", e);
        }

        // Fallback: app-private external files directory (no permissions required)
        File fallbackDir = context.getExternalFilesDir(null);
        if (fallbackDir == null) {
            // Last resort: app-private internal files directory
            fallbackDir = context.getFilesDir();
        }
        //noinspection ResultOfMethodCallIgnored
        fallbackDir.mkdirs();
        File fallback = new File(fallbackDir, fileName);
        try (FileWriter fw = new FileWriter(fallback, false)) {
            fw.write(header);
            fw.write(logOutput);
        }
        return fallback;
    }

    private void copyLogsClicked(ToolbarMenuSubItem item) {
        String text = getFilteredAndAnonymizedLogs();
        ClipboardManager clipboard = (ClipboardManager) AndroidUtils.getAppContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Logs", text));
        Toast.makeText(context, R.string.settings_logs_copied_to_clipboard, Toast.LENGTH_SHORT).show();
    }

    private void loadLogs() {
        Process process;
        try {
            // Filter by our PID immediately to only see our app's logs
            process = new ProcessBuilder()
                    .command("logcat", "-d", "-v", "tag", "--pid=" + android.os.Process.myPid())
                    .start();
        } catch (IOException e) {
            Logger.e(TAG, "Error starting logcat", e);
            return;
        }

        String raw = IOUtils.readString(process.getInputStream());
        List<String> lines = new ArrayList<>();
        if (!TextUtils.isEmpty(raw)) {
            for (String line : raw.split("\n")) {
                lines.add(line);
            }
        }

        new Handler(Looper.getMainLooper()).post(() -> {
            allLines.clear();
            allLines.addAll(lines);
            applyFilter();
        });
    }

    private void applyFilter() {
        List<String> matched = new ArrayList<>();
        for (String line : allLines) {
            if (shouldIgnoreLine(line)) {
                continue;
            }

            if (activeFilter.isEmpty() || line.toLowerCase().contains(activeFilter)) {
                matched.add(line);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String line : matched) {
            sb.append(line).append('\n');
        }

        logTextView.setText(sb.toString());
        lineCountView.setText(matched.size() + " / " + allLines.size() + " lines");
    }

    private static final Pattern IP_PATTERN = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9\\+\\.\\_\\%\\-\\+]{1,256}\\@[a-zA-Z0-9][a-zA-Z0-9\\-]{0,64}(\\.[a-zA-Z0-9][a-zA-Z0-9\\-]{0,25})+");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("(auth|token|session|key|pass|password|secret)[=_:][^\\s&]{4,}", Pattern.CASE_INSENSITIVE);

    private String anonymizeLog(String line) {
        if (line == null) return "";
        String scrubbed = line;
        scrubbed = IP_PATTERN.matcher(scrubbed).replaceAll("[IP_ADDR]");
        scrubbed = EMAIL_PATTERN.matcher(scrubbed).replaceAll("[EMAIL]");
        scrubbed = TOKEN_PATTERN.matcher(scrubbed).replaceAll("$1=[REDACTED]");
        return scrubbed;
    }
}
