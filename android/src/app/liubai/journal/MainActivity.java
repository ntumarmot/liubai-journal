package app.liubai.journal;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int EXPORT_BACKUP = 1001;
    private static final int IMPORT_BACKUP = 1002;
    private static final int EXPORT_PDF = 1003;
    private static final int MENU_CALENDAR = 2001;
    private static final int MENU_IMPORT = 2002;
    private static final int MENU_EXPORT = 2003;
    private static final int MENU_EXPORT_ENTRY_PDF = 2004;
    private static final int TAB_DIARY = 0;
    private static final int TAB_STATISTICS = 1;
    private static final int TAB_SETTINGS = 2;
    private static final int MAX_BACKUP_BYTES = 5 * 1024 * 1024;
    private static final String[] MOODS = {"不錯", "平靜", "疲憊", "低落"};
    private static final Locale LOCALE = Locale.TAIWAN;

    private JournalStore store;
    private JournalStore.Entry editing;
    private boolean editorMode;
    private boolean newEntry;
    private boolean focusBodyOnOpen;
    private boolean suppressSave;
    private boolean dark;
    private boolean calendarOpen;
    private boolean returnToCalendar;
    private YearMonth calendarMonth = YearMonth.now();
    private LocalDate selectedCalendarDate = LocalDate.now();
    private String homeSearchQuery = "";
    private int homeScrollY;
    private boolean homeSearchOpen;
    private int topLevelTab = TAB_DIARY;
    private int returnToTopLevelTab = TAB_DIARY;
    private ArrayList<String> pendingPdfEntryIds = new ArrayList<>();
    private String pendingPdfRangeLabel = "";

    private int background;
    private int surface;
    private int ink;
    private int muted;
    private int border;
    private int primary;
    private int accent;
    private int danger;

    private LinearLayout listContainer;
    private ScrollView homeScroll;
    private EditText searchField;
    private TextView countLabel;
    private TextView saveStatus;
    private LinearLayout moodRow;
    private final Handler saveHandler = new Handler(Looper.getMainLooper());
    private final Runnable pendingSave = () -> {
        if (editing == null) return;
        store.update(editing);
        if (saveStatus != null) {
            saveStatus.setText("已儲存在這台裝置");
            saveStatus.setTextColor(muted);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        applyPalette();
        configureWindow();
        store = new JournalStore(this);
        if (state != null) {
            calendarOpen = state.getBoolean("calendarOpen", false);
            returnToCalendar = state.getBoolean("returnToCalendar", false);
            homeSearchQuery = state.getString("homeSearchQuery", "");
            homeScrollY = state.getInt("homeScrollY", 0);
            homeSearchOpen = state.getBoolean(
                    "homeSearchOpen", !homeSearchQuery.isEmpty());
            topLevelTab = state.getInt("topLevelTab", TAB_DIARY);
            returnToTopLevelTab = state.getInt("returnToTopLevelTab", TAB_DIARY);
            pendingPdfEntryIds = state.getStringArrayList("pendingPdfEntryIds");
            if (pendingPdfEntryIds == null) pendingPdfEntryIds = new ArrayList<>();
            pendingPdfRangeLabel = state.getString("pendingPdfRangeLabel", "");
            try {
                calendarMonth = YearMonth.parse(state.getString("calendarMonth", YearMonth.now().toString()));
                selectedCalendarDate = LocalDate.parse(state.getString(
                        "selectedCalendarDate", LocalDate.now().toString()));
            } catch (Exception ignored) {
                calendarMonth = YearMonth.now();
                selectedCalendarDate = LocalDate.now();
            }
            if (state.containsKey("editingId")) {
                editing = store.find(state.getString("editingId"));
                editorMode = state.getBoolean("editorMode", false);
                newEntry = state.getBoolean("newEntry", false);
            }
        }
        if (editing == null) {
            if (calendarOpen) {
                renderCalendar();
            } else {
                renderCurrentTopLevel();
            }
        } else if (editorMode) {
            renderEditor();
        } else {
            renderReader();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        flushPendingSave();
        captureHomeState();
        state.putBoolean("calendarOpen", calendarOpen);
        state.putBoolean("returnToCalendar", returnToCalendar);
        state.putString("calendarMonth", calendarMonth.toString());
        state.putString("selectedCalendarDate", selectedCalendarDate.toString());
        state.putString("homeSearchQuery", homeSearchQuery);
        state.putInt("homeScrollY", homeScrollY);
        state.putBoolean("homeSearchOpen", homeSearchOpen);
        state.putInt("topLevelTab", topLevelTab);
        state.putInt("returnToTopLevelTab", returnToTopLevelTab);
        state.putStringArrayList("pendingPdfEntryIds", pendingPdfEntryIds);
        state.putString("pendingPdfRangeLabel", pendingPdfRangeLabel);
        if (editing != null) {
            state.putString("editingId", editing.id);
            state.putBoolean("editorMode", editorMode);
            state.putBoolean("newEntry", newEntry);
        }
        super.onSaveInstanceState(state);
    }

    @Override protected void onPause() {
        flushPendingSave();
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (editing != null) {
            if (editorMode) finishEditing(); else finishViewing();
            return;
        }
        if (calendarOpen) {
            renderHome();
            return;
        }
        if (topLevelTab == TAB_DIARY && homeSearchOpen) {
            closeHomeSearch();
            return;
        }
        if (topLevelTab != TAB_DIARY) {
            renderHome();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == EXPORT_PDF) clearPendingPdfExport();
            return;
        }
        Uri uri = data.getData();
        if (requestCode == EXPORT_BACKUP) {
            writeBackup(uri);
        } else if (requestCode == IMPORT_BACKUP) {
            confirmImport(uri);
        } else if (requestCode == EXPORT_PDF) {
            writePdf(uri);
        }
    }

    private void applyPalette() {
        if (dark) {
            background = Color.rgb(15, 23, 42);
            surface = Color.rgb(24, 34, 53);
            ink = Color.rgb(248, 250, 252);
            muted = Color.rgb(203, 213, 225);
            border = Color.rgb(71, 85, 105);
            primary = Color.rgb(147, 176, 216);
            accent = Color.rgb(217, 161, 91);
            danger = Color.rgb(248, 113, 113);
        } else {
            background = Color.rgb(248, 250, 252);
            surface = Color.WHITE;
            ink = Color.rgb(15, 23, 42);
            muted = Color.rgb(71, 85, 105);
            border = Color.rgb(203, 213, 225);
            primary = Color.rgb(30, 58, 95);
            accent = Color.rgb(180, 83, 9);
            danger = Color.rgb(185, 28, 28);
        }
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(background);
        window.setNavigationBarColor(background);
        int flags = 0;
        if (!dark) flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(flags);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private void renderHome() {
        editing = null;
        editorMode = false;
        newEntry = false;
        focusBodyOnOpen = false;
        saveStatus = null;
        calendarOpen = false;
        returnToCalendar = false;
        topLevelTab = TAB_DIARY;
        if (!homeSearchQuery.isEmpty()) homeSearchOpen = true;
        searchField = null;
        LinearLayout root = column();
        root.setBackgroundColor(background);
        root.setFitsSystemWindows(true);
        setContentView(root);

        LinearLayout header = column();
        header.setPadding(gutter(), dp(8), gutter(), dp(4));
        root.addView(header);

        LinearLayout titleRow = row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(titleRow, new LinearLayout.LayoutParams(-1, dp(64)));
        TextView brand = text("留白日記", 27, ink, true);
        brand.setTypeface(Typeface.create("serif", Typeface.BOLD));
        titleRow.addView(brand, new LinearLayout.LayoutParams(0, dp(56), 1));
        countLabel = text("", 13, muted, false);
        countLabel.setGravity(Gravity.CENTER);
        titleRow.addView(countLabel, new LinearLayout.LayoutParams(dp(64), dp(48)));
        ImageButton searchButton = iconButton("ic_search", "搜尋日記");
        searchButton.setOnClickListener(view -> openHomeSearch());
        titleRow.addView(searchButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        ImageButton moreButton = iconButton("ic_more_vert", "更多功能");
        moreButton.setOnClickListener(this::showHomeMenu);
        titleRow.addView(moreButton, new LinearLayout.LayoutParams(dp(48), dp(48)));

        if (homeSearchOpen) {
            LinearLayout searchRow = row();
            searchRow.setGravity(Gravity.CENTER_VERTICAL);
            header.addView(searchRow, new LinearLayout.LayoutParams(-1, dp(60)));
            searchField = new EditText(this);
            searchField.setHint("搜尋標題或內容");
            searchField.setHintTextColor(muted);
            searchField.setTextColor(ink);
            searchField.setTextSize(16);
            searchField.setSingleLine(true);
            searchField.setPadding(dp(16), 0, dp(16), 0);
            searchField.setBackground(fieldBackground());
            searchField.setContentDescription("搜尋日記");
            searchRow.addView(searchField, new LinearLayout.LayoutParams(0, dp(52), 1));
            ImageButton closeSearch = iconButton("ic_close", "關閉搜尋");
            closeSearch.setOnClickListener(view -> closeHomeSearch());
            LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(48), dp(48));
            closeParams.leftMargin = dp(8);
            searchRow.addView(closeSearch, closeParams);
        }

        LinearLayout sectionRow = row();
        sectionRow.setGravity(Gravity.CENTER_VERTICAL);
        sectionRow.setPadding(gutter(), dp(2), gutter(), dp(2));
        root.addView(sectionRow, new LinearLayout.LayoutParams(-1, dp(56)));
        TextView section = text("最近的日子", 18, ink, true);
        section.setGravity(Gravity.CENTER_VERTICAL);
        sectionRow.addView(section, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView create = actionButton("寫日記", primary,
                dark ? background : Color.WHITE, this::createEntry);
        create.setContentDescription("新增一篇日記");
        sectionRow.addView(create, new LinearLayout.LayoutParams(dp(108), dp(48)));

        homeScroll = new ScrollView(this);
        homeScroll.setFillViewport(true);
        root.addView(homeScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        listContainer = column();
        listContainer.setPadding(gutter(), dp(4), gutter(), dp(36));
        homeScroll.addView(listContainer);
        addBottomNavigation(root);

        if (searchField != null) {
            searchField.setText(homeSearchQuery);
            searchField.setSelection(homeSearchQuery.length());
            searchField.addTextChangedListener(new SimpleWatcher() {
                @Override public void afterTextChanged(Editable value) {
                    homeSearchQuery = value.toString();
                    homeScrollY = 0;
                    renderList(homeSearchQuery);
                    homeScroll.scrollTo(0, 0);
                }
            });
        }
        renderList(homeSearchQuery);
        ScrollView restoredScroll = homeScroll;
        int restoredY = homeScrollY;
        restoredScroll.post(() -> restoredScroll.scrollTo(0, restoredY));
    }

    private void renderCurrentTopLevel() {
        if (topLevelTab == TAB_STATISTICS) {
            renderStatistics();
        } else if (topLevelTab == TAB_SETTINGS) {
            renderSettings();
        } else {
            renderHome();
        }
    }

    private void renderStatistics() {
        prepareTopLevel(TAB_STATISTICS);
        LinearLayout root = topLevelRoot();
        addTopLevelHeader(root, "書寫統計", "標題與內文的字數，不包含空格與換行");

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = column();
        content.setPadding(gutter(), dp(12), gutter(), dp(32));
        scroll.addView(content);

        List<JournalStore.Entry> entries = store.all();
        long totalCharacters = 0;
        long monthCharacters = 0;
        int longestCharacters = 0;
        JournalStore.Entry longestEntry = null;
        int monthEntries = 0;
        Set<String> writtenDays = new HashSet<>();
        String currentMonth = YearMonth.now().toString();
        for (JournalStore.Entry entry : entries) {
            int characters = entryCharacterCount(entry);
            totalCharacters += characters;
            if (longestEntry == null || characters > longestCharacters) {
                longestCharacters = characters;
                longestEntry = entry;
            }
            writtenDays.add(entry.date);
            if (entry.date.startsWith(currentMonth)) {
                monthEntries++;
                monthCharacters += characters;
            }
        }
        long averageCharacters = entries.isEmpty()
                ? 0 : Math.round((double) totalCharacters / entries.size());
        JournalStore.Entry longestEntryForAction = longestEntry;

        LinearLayout totalCard = column();
        totalCard.setPadding(dp(22), dp(20), dp(22), dp(20));
        totalCard.setBackground(rippleSurface(surface, 18));
        TextView totalLabel = text("累積字數", 14, muted, true);
        totalCard.addView(totalLabel);
        TextView totalValue = text(formatNumber(totalCharacters), 36, primary, true);
        totalValue.setPadding(0, dp(4), 0, dp(3));
        totalCard.addView(totalValue);
        TextView totalHint = text("共 " + formatNumber(entries.size()) + " 篇日記", 14, muted, false);
        totalCard.addView(totalHint);
        content.addView(totalCard, new LinearLayout.LayoutParams(-1, -2));

        content.addView(sectionTitle("全部日記"));
        addMetricRow(content,
                "寫過的日子", formatNumber(writtenDays.size()) + " 天",
                "平均每篇", formatNumber(averageCharacters) + " 字");
        addMetricRow(content,
                "最長一篇", formatNumber(longestCharacters) + " 字",
                longestEntryForAction == null ? null
                        : () -> openEntry(longestEntryForAction.id, false, false),
                "日記總數", formatNumber(entries.size()) + " 篇");

        content.addView(sectionTitle("這個月"));
        addMetricRow(content,
                "本月日記", formatNumber(monthEntries) + " 篇",
                "本月字數", formatNumber(monthCharacters) + " 字");

        TextView note = text("統計會在你新增、修改或匯入日記後自動更新。", 13, muted, false);
        note.setLineSpacing(dp(2), 1.15f);
        note.setPadding(dp(2), dp(18), dp(2), 0);
        content.addView(note);
        addBottomNavigation(root);
    }

    private void renderSettings() {
        prepareTopLevel(TAB_SETTINGS);
        LinearLayout root = topLevelRoot();
        addTopLevelHeader(root, "設定", "管理備份、外觀與 App 資訊");

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = column();
        content.setPadding(gutter(), dp(4), gutter(), dp(32));
        scroll.addView(content);

        content.addView(sectionTitle("閱讀與分享"));
        LinearLayout exportPdf = settingsAction("匯出 PDF",
                "輸出全部、本月或自訂日期範圍的日記", this::showPdfExportOptions);
        content.addView(exportPdf, settingsItemParams());

        content.addView(sectionTitle("資料與備份"));
        LinearLayout export = settingsAction("匯出備份", "把全部日記存成 JSON 備份檔", this::openExportPicker);
        content.addView(export, settingsItemParams());
        LinearLayout importBackup = settingsAction("匯入備份", "與現有日記合併，不會先清空資料", this::openImportPicker);
        content.addView(importBackup, settingsItemParams());
        TextView privacy = text("PDF 與備份檔都是可直接閱讀的明文，請存放在你信任的位置。", 13, muted, false);
        privacy.setLineSpacing(dp(2), 1.15f);
        privacy.setPadding(dp(4), dp(4), dp(4), dp(6));
        content.addView(privacy);

        content.addView(sectionTitle("外觀"));
        content.addView(settingsInfo("顯示模式", dark ? "目前跟隨系統使用深色模式" : "目前跟隨系統使用淺色模式"),
                settingsItemParams());

        content.addView(sectionTitle("關於"));
        content.addView(settingsInfo("留白日記", "版本 " + appVersionName() + " · 資料只儲存在這台裝置"),
                settingsItemParams());
        addBottomNavigation(root);
    }

    private void prepareTopLevel(int tab) {
        editing = null;
        editorMode = false;
        newEntry = false;
        focusBodyOnOpen = false;
        saveStatus = null;
        calendarOpen = false;
        returnToCalendar = false;
        searchField = null;
        homeScroll = null;
        listContainer = null;
        countLabel = null;
        topLevelTab = tab;
        hideKeyboard();
    }

    private LinearLayout topLevelRoot() {
        LinearLayout root = column();
        root.setBackgroundColor(background);
        root.setFitsSystemWindows(true);
        setContentView(root);
        return root;
    }

    private void addTopLevelHeader(LinearLayout root, String titleValue, String subtitleValue) {
        LinearLayout header = column();
        header.setPadding(gutter(), dp(13), gutter(), dp(12));
        TextView title = text(titleValue, 27, ink, true);
        title.setTypeface(Typeface.create("serif", Typeface.BOLD));
        header.addView(title);
        TextView subtitle = text(subtitleValue, 14, muted, false);
        subtitle.setPadding(0, dp(5), 0, 0);
        subtitle.setLineSpacing(dp(2), 1.1f);
        header.addView(subtitle);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView sectionTitle(String value) {
        TextView title = text(value, 17, ink, true);
        title.setPadding(dp(2), dp(24), dp(2), dp(10));
        return title;
    }

    private void addMetricRow(LinearLayout parent, String firstLabel, String firstValue,
                              String secondLabel, String secondValue) {
        addMetricRow(parent, firstLabel, firstValue, null,
                secondLabel, secondValue, null);
    }

    private void addMetricRow(LinearLayout parent, String firstLabel, String firstValue,
                              Runnable firstAction, String secondLabel, String secondValue) {
        addMetricRow(parent, firstLabel, firstValue, firstAction,
                secondLabel, secondValue, null);
    }

    private void addMetricRow(LinearLayout parent, String firstLabel, String firstValue,
                              Runnable firstAction, String secondLabel, String secondValue,
                              Runnable secondAction) {
        LinearLayout row = row();
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.bottomMargin = dp(12);
        parent.addView(row, rowParams);
        LinearLayout first = metricCard(firstLabel, firstValue, firstAction);
        LinearLayout.LayoutParams firstParams = new LinearLayout.LayoutParams(0, -2, 1);
        firstParams.rightMargin = dp(6);
        row.addView(first, firstParams);
        LinearLayout second = metricCard(secondLabel, secondValue, secondAction);
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(0, -2, 1);
        secondParams.leftMargin = dp(6);
        row.addView(second, secondParams);
    }

    private LinearLayout metricCard(String labelValue, String value, Runnable action) {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(action == null ? fieldBackground() : rippleSurface(surface, 14));

        LinearLayout labelRow = row();
        labelRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(labelRow, new LinearLayout.LayoutParams(-1, -2));
        TextView label = text(labelValue, 13, muted, true);
        labelRow.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            TextView affordance = text("查看 ›", 13, primary, true);
            affordance.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            labelRow.addView(affordance, new LinearLayout.LayoutParams(-2, dp(24)));
            card.setContentDescription(labelValue + "，" + value + "，查看日記");
            card.setOnClickListener(view -> action.run());
            card.setFocusable(true);
            card.setClickable(true);
        }
        TextView metric = text(value, 22, ink, true);
        metric.setPadding(0, dp(5), 0, 0);
        card.addView(metric);
        return card;
    }

    private LinearLayout settingsAction(String title, String description, Runnable action) {
        LinearLayout item = settingsCard(title, description);
        item.setBackground(rippleSurface(surface, 15));
        item.setOnClickListener(view -> action.run());
        item.setFocusable(true);
        item.setClickable(true);
        item.setContentDescription(title + "，" + description);
        return item;
    }

    private LinearLayout settingsInfo(String title, String description) {
        LinearLayout item = settingsCard(title, description);
        item.setBackground(fieldBackground());
        return item;
    }

    private LinearLayout settingsCard(String titleValue, String descriptionValue) {
        LinearLayout card = column();
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        TextView title = text(titleValue, 16, ink, true);
        card.addView(title);
        TextView description = text(descriptionValue, 14, muted, false);
        description.setPadding(0, dp(5), 0, 0);
        description.setLineSpacing(dp(2), 1.08f);
        card.addView(description);
        return card;
    }

    private LinearLayout.LayoutParams settingsItemParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        return params;
    }

    private void addBottomNavigation(LinearLayout root) {
        View divider = new View(this);
        divider.setBackgroundColor(border);
        root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        LinearLayout navigation = row();
        navigation.setGravity(Gravity.CENTER);
        navigation.setPadding(dp(8), dp(4), dp(8), dp(4));
        navigation.setBackgroundColor(surface);
        root.addView(navigation, new LinearLayout.LayoutParams(-1, dp(68)));
        addNavigationItem(navigation, TAB_DIARY, "ic_diary", "日記");
        addNavigationItem(navigation, TAB_STATISTICS, "ic_statistics", "統計");
        addNavigationItem(navigation, TAB_SETTINGS, "ic_settings", "設定");
    }

    private void addNavigationItem(LinearLayout navigation, int tab, String iconName, String labelValue) {
        boolean selected = topLevelTab == tab;
        LinearLayout item = column();
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(6), dp(4), dp(6), dp(3));
        item.setBackground(ripple(selected ? withAlpha(primary, dark ? 42 : 22) : Color.TRANSPARENT, 14));
        item.setFocusable(true);
        item.setClickable(true);
        item.setContentDescription(labelValue + (selected ? "，目前頁面" : ""));
        item.setOnClickListener(view -> switchTopLevel(tab));

        ImageView icon = new ImageView(this);
        int drawable = getResources().getIdentifier(iconName, "drawable", getPackageName());
        icon.setImageResource(drawable);
        icon.setImageTintList(ColorStateList.valueOf(selected ? primary : muted));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        item.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView label = text(labelValue, 12, selected ? primary : muted, selected);
        label.setGravity(Gravity.CENTER);
        item.addView(label, new LinearLayout.LayoutParams(-1, dp(24)));
        navigation.addView(item, new LinearLayout.LayoutParams(0, dp(60), 1));
    }

    private void switchTopLevel(int tab) {
        if (tab == topLevelTab) return;
        if (topLevelTab == TAB_DIARY) captureHomeState();
        topLevelTab = tab;
        renderCurrentTopLevel();
    }

    private int entryCharacterCount(JournalStore.Entry entry) {
        return countCharacters(entry.title) + countCharacters(entry.body);
    }

    private int countCharacters(String value) {
        if (value == null) return 0;
        int count = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (!Character.isWhitespace(codePoint)) count++;
        }
        return count;
    }

    private String formatNumber(long value) {
        return String.format(LOCALE, "%,d", value);
    }

    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "未知";
        }
    }

    private void openHomeSearch() {
        captureHomeState();
        if (!homeSearchOpen) {
            homeSearchOpen = true;
            homeScrollY = 0;
            renderHome();
        }
        if (searchField != null) {
            searchField.requestFocus();
            searchField.postDelayed(() -> {
                InputMethodManager keyboard = (InputMethodManager)
                        getSystemService(Context.INPUT_METHOD_SERVICE);
                keyboard.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
            }, 120);
        }
    }

    private void closeHomeSearch() {
        hideKeyboard();
        homeSearchOpen = false;
        homeSearchQuery = "";
        homeScrollY = 0;
        renderHome();
    }

    private void showHomeMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, MENU_CALENDAR, 0, "日曆檢視");
        menu.getMenu().add(0, MENU_IMPORT, 1, "匯入備份");
        menu.getMenu().add(0, MENU_EXPORT, 2, "匯出備份");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_CALENDAR) {
                openCalendar();
            } else if (item.getItemId() == MENU_IMPORT) {
                openImportPicker();
            } else if (item.getItemId() == MENU_EXPORT) {
                openExportPicker();
            } else {
                return false;
            }
            return true;
        });
        menu.show();
    }

    private void renderList(String query) {
        listContainer.removeAllViews();
        List<JournalStore.Entry> all = store.all();
        countLabel.setText(all.size() + " 篇");
        String needle = query.trim().toLowerCase(LOCALE);
        ArrayList<JournalStore.Entry> shown = new ArrayList<>();
        for (JournalStore.Entry entry : all) {
            String haystack = (entry.title + "\n" + entry.body + "\n" + entry.mood).toLowerCase(LOCALE);
            if (needle.isEmpty() || haystack.contains(needle)) shown.add(entry);
        }

        if (shown.isEmpty()) {
            LinearLayout empty = column();
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(56), dp(24), dp(40));
            TextView title = text(needle.isEmpty() ? "這裡還是空白" : "沒有找到日記", 20, ink, true);
            title.setGravity(Gravity.CENTER);
            empty.addView(title);
            TextView hint = text(needle.isEmpty() ? "寫下今天的第一個句子吧。" : "試試其他關鍵字。", 15, muted, false);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, dp(8), 0, 0);
            empty.addView(hint);
            listContainer.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }

        for (JournalStore.Entry entry : shown) addEntryCard(entry);
    }

    private void addEntryCard(JournalStore.Entry entry) {
        LinearLayout card = buildEntryCard(entry, () -> openEntry(entry.id, false, false));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        listContainer.addView(card, params);
    }

    private LinearLayout buildEntryCard(JournalStore.Entry entry, Runnable action) {
        LinearLayout card = column();
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(rippleSurface(surface, 16));
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnClickListener(view -> action.run());

        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(top);
        TextView date = text(formatDate(entry.date), 13, muted, true);
        top.addView(date, new LinearLayout.LayoutParams(0, dp(32), 1));
        TextView mood = text(entry.mood, 12, accent, true);
        mood.setGravity(Gravity.CENTER);
        mood.setBackground(chipBackground(false));
        top.addView(mood, new LinearLayout.LayoutParams(dp(62), dp(32)));

        String titleValue = entry.title.trim().isEmpty() ? firstMeaningfulLine(entry.body) : entry.title.trim();
        if (titleValue.isEmpty()) titleValue = "無標題日記";
        TextView title = text(titleValue, 19, ink, true);
        title.setMaxLines(2);
        title.setPadding(0, dp(7), 0, dp(5));
        card.addView(title);

        String preview = entry.body.trim().replaceAll("\\s+", " ");
        if (preview.isEmpty()) preview = "點一下繼續寫……";
        TextView excerpt = text(preview, 14, muted, false);
        excerpt.setMaxLines(2);
        excerpt.setLineSpacing(0, 1.25f);
        card.addView(excerpt);
        card.setContentDescription(formatDate(entry.date) + "，" + titleValue + "，心情" + entry.mood);
        return card;
    }

    private void createEntry() {
        JournalStore.Entry entry = store.create();
        openEntry(entry.id, true, false);
    }

    private void openCalendar() {
        calendarMonth = YearMonth.now();
        selectedCalendarDate = bestDateForMonth(calendarMonth);
        renderCalendar();
    }

    private void renderCalendar() {
        editing = null;
        editorMode = false;
        newEntry = false;
        focusBodyOnOpen = false;
        saveStatus = null;
        calendarOpen = true;
        returnToCalendar = false;

        LinearLayout root = column();
        root.setBackgroundColor(background);
        root.setFitsSystemWindows(true);
        setContentView(root);

        LinearLayout topBar = row();
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(gutter(), dp(8), gutter(), dp(8));
        root.addView(topBar, new LinearLayout.LayoutParams(-1, dp(64)));

        TextView back = quietButton("返回", ink, this::renderHome);
        back.setContentDescription("返回日記首頁");
        topBar.addView(back, new LinearLayout.LayoutParams(dp(72), dp(48)));
        TextView title = text("日曆", 18, ink, true);
        title.setGravity(Gravity.CENTER);
        topBar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView today = quietButton("今天", primary, () -> {
            calendarMonth = YearMonth.now();
            selectedCalendarDate = LocalDate.now();
            renderCalendar();
        });
        today.setContentDescription("回到今天");
        topBar.addView(today, new LinearLayout.LayoutParams(dp(72), dp(48)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout content = column();
        content.setPadding(0, dp(4), 0, dp(40));
        scroll.addView(content);

        LinearLayout monthRow = row();
        monthRow.setGravity(Gravity.CENTER_VERTICAL);
        monthRow.setPadding(gutter(), 0, gutter(), 0);
        content.addView(monthRow, new LinearLayout.LayoutParams(-1, dp(56)));
        TextView previous = secondaryButton("上月", () -> changeCalendarMonth(-1));
        previous.setContentDescription("查看上一個月");
        monthRow.addView(previous, new LinearLayout.LayoutParams(dp(72), dp(48)));
        TextView monthTitle = text(calendarMonth.format(
                DateTimeFormatter.ofPattern("yyyy 年 M 月", LOCALE)), 21, ink, true);
        monthTitle.setGravity(Gravity.CENTER);
        monthRow.addView(monthTitle, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView next = secondaryButton("下月", () -> changeCalendarMonth(1));
        next.setContentDescription("查看下一個月");
        monthRow.addView(next, new LinearLayout.LayoutParams(dp(72), dp(48)));

        GridLayout weekGrid = new GridLayout(this);
        weekGrid.setColumnCount(7);
        String[] weekdays = {"日", "一", "二", "三", "四", "五", "六"};
        for (int column = 0; column < weekdays.length; column++) {
            TextView weekday = text(weekdays[column], 13,
                    column == 0 || column == 6 ? accent : muted, true);
            weekday.setGravity(Gravity.CENTER);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(0), GridLayout.spec(column, 1f));
            params.width = 0;
            params.height = dp(36);
            weekGrid.addView(weekday, params);
        }
        content.addView(weekGrid, new LinearLayout.LayoutParams(-1, dp(36)));

        GridLayout dayGrid = new GridLayout(this);
        dayGrid.setColumnCount(7);
        dayGrid.setRowCount(6);
        int firstColumn = calendarMonth.atDay(1).getDayOfWeek().getValue() % 7;
        int daysInMonth = calendarMonth.lengthOfMonth();
        for (int cell = 0; cell < 42; cell++) {
            int day = cell - firstColumn + 1;
            int row = cell / 7;
            int column = cell % 7;
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(row), GridLayout.spec(column, 1f));
            params.width = 0;
            params.height = dp(54);
            params.setMargins(dp(1), dp(2), dp(1), dp(2));
            if (day < 1 || day > daysInMonth) {
                dayGrid.addView(new View(this), params);
                continue;
            }
            LocalDate date = calendarMonth.atDay(day);
            List<JournalStore.Entry> entries = entriesForDate(date);
            boolean selected = date.equals(selectedCalendarDate);
            boolean isToday = date.equals(LocalDate.now());
            TextView dayButton = text(String.valueOf(day), 15,
                    selected ? (dark ? background : Color.WHITE) : ink, true);
            dayButton.setGravity(Gravity.CENTER);
            dayButton.setBackground(calendarDayBackground(!entries.isEmpty(), selected, isToday));
            dayButton.setFocusable(true);
            dayButton.setClickable(true);
            dayButton.setContentDescription(date.format(
                    DateTimeFormatter.ofPattern("M 月 d 日", LOCALE)) + "，"
                    + entries.size() + " 篇日記" + (isToday ? "，今天" : "")
                    + (selected ? "，已選取" : ""));
            dayButton.setOnClickListener(view -> {
                selectedCalendarDate = date;
                renderCalendar();
            });
            dayGrid.addView(dayButton, params);
        }
        content.addView(dayGrid, new LinearLayout.LayoutParams(-1, dp(348)));

        TextView legend = text("橘色框線：有日記　藍色框線：今天", 13, muted, false);
        legend.setGravity(Gravity.CENTER);
        legend.setPadding(0, dp(8), 0, dp(20));
        content.addView(legend);

        LinearLayout selectedContainer = column();
        selectedContainer.setPadding(gutter(), 0, gutter(), 0);
        content.addView(selectedContainer);
        List<JournalStore.Entry> selectedEntries = entriesForDate(selectedCalendarDate);
        TextView selectedTitle = text(selectedCalendarDate.format(
                DateTimeFormatter.ofPattern("M 月 d 日", LOCALE)) + "的日記（"
                + selectedEntries.size() + "）", 19, ink, true);
        selectedTitle.setPadding(0, 0, 0, dp(12));
        selectedContainer.addView(selectedTitle);

        if (selectedEntries.isEmpty()) {
            TextView empty = text("這天沒有日記。", 15, muted, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(28), dp(16), dp(28));
            empty.setBackground(fieldBackground());
            selectedContainer.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        } else {
            for (JournalStore.Entry entry : selectedEntries) {
                LinearLayout card = buildEntryCard(entry,
                        () -> openEntry(entry.id, false, true));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                params.bottomMargin = dp(12);
                selectedContainer.addView(card, params);
            }
        }
    }

    private void changeCalendarMonth(int offset) {
        calendarMonth = calendarMonth.plusMonths(offset);
        selectedCalendarDate = bestDateForMonth(calendarMonth);
        renderCalendar();
    }

    private LocalDate bestDateForMonth(YearMonth month) {
        for (JournalStore.Entry entry : store.all()) {
            LocalDate date = LocalDate.parse(entry.date);
            if (YearMonth.from(date).equals(month)) return date;
        }
        LocalDate today = LocalDate.now();
        return YearMonth.from(today).equals(month) ? today : month.atDay(1);
    }

    private List<JournalStore.Entry> entriesForDate(LocalDate date) {
        ArrayList<JournalStore.Entry> matches = new ArrayList<>();
        String target = date.toString();
        for (JournalStore.Entry entry : store.all()) {
            if (target.equals(entry.date)) matches.add(entry);
        }
        return matches;
    }

    private void showPdfExportOptions() {
        List<JournalStore.Entry> all = store.all();
        YearMonth currentMonth = YearMonth.now();
        int monthCount = 0;
        for (JournalStore.Entry entry : all) {
            if (entry.date.startsWith(currentMonth.toString())) monthCount++;
        }
        String[] options = {
                "全部日記（" + all.size() + " 篇）",
                "本月日記（" + monthCount + " 篇）",
                "自訂日期範圍"
        };
        new AlertDialog.Builder(this)
                .setTitle("要輸出哪些日記？")
                .setItems(options, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) {
                        preparePdfExport(all, "全部日記",
                                "liubai-journal-all-" + LocalDate.now() + ".pdf");
                    } else if (which == 1) {
                        exportPdfForMonth(currentMonth);
                    } else {
                        choosePdfDateRange();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void exportPdfForMonth(YearMonth month) {
        ArrayList<JournalStore.Entry> matches = new ArrayList<>();
        for (JournalStore.Entry entry : store.all()) {
            if (entry.date.startsWith(month.toString())) matches.add(entry);
        }
        String label = month.format(DateTimeFormatter.ofPattern("yyyy 年 M 月", LOCALE));
        preparePdfExport(matches, label, "liubai-journal-" + month + ".pdf");
    }

    private void choosePdfDateRange() {
        List<JournalStore.Entry> all = store.all();
        if (all.isEmpty()) {
            showNoPdfEntries("目前還沒有日記，寫下第一篇後就能輸出 PDF。");
            return;
        }
        LocalDate earliest = LocalDate.parse(all.get(0).date);
        LocalDate latest = earliest;
        for (JournalStore.Entry entry : all) {
            LocalDate date = LocalDate.parse(entry.date);
            if (date.isBefore(earliest)) earliest = date;
            if (date.isAfter(latest)) latest = date;
        }
        LocalDate firstDate = earliest;
        LocalDate lastDate = latest;
        DatePickerDialog startDialog = new DatePickerDialog(this,
                (picker, year, month, day) -> {
                    LocalDate start = LocalDate.of(year, month + 1, day);
                    choosePdfEndDate(start, lastDate);
                }, earliest.getYear(), earliest.getMonthValue() - 1, earliest.getDayOfMonth());
        startDialog.setTitle("選擇開始日期");
        startDialog.getDatePicker().setMinDate(datePickerMillis(firstDate));
        startDialog.getDatePicker().setMaxDate(datePickerMillis(lastDate));
        Toast.makeText(this, "步驟 1/2：選擇開始日期", Toast.LENGTH_SHORT).show();
        startDialog.show();
    }

    private void choosePdfEndDate(LocalDate start, LocalDate latest) {
        LocalDate initial = latest.isBefore(start) ? start : latest;
        DatePickerDialog endDialog = new DatePickerDialog(this,
                (picker, year, month, day) -> {
                    LocalDate end = LocalDate.of(year, month + 1, day);
                    exportPdfForRange(start, end);
                }, initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth());
        endDialog.setTitle("選擇結束日期");
        endDialog.getDatePicker().setMinDate(datePickerMillis(start));
        endDialog.getDatePicker().setMaxDate(datePickerMillis(latest));
        Toast.makeText(this, "步驟 2/2：選擇結束日期", Toast.LENGTH_SHORT).show();
        endDialog.show();
    }

    private void exportPdfForRange(LocalDate start, LocalDate end) {
        ArrayList<JournalStore.Entry> matches = new ArrayList<>();
        for (JournalStore.Entry entry : store.all()) {
            LocalDate date = LocalDate.parse(entry.date);
            if (!date.isBefore(start) && !date.isAfter(end)) matches.add(entry);
        }
        String label = start.equals(end)
                ? start.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日", LOCALE))
                : start.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日", LOCALE))
                + " 至 " + end.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日", LOCALE));
        preparePdfExport(matches, label,
                "liubai-journal-" + start + "-to-" + end + ".pdf");
    }

    private long datePickerMillis(LocalDate date) {
        return date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private void preparePdfExport(List<JournalStore.Entry> entries,
                                  String rangeLabel, String fileName) {
        if (entries.isEmpty()) {
            showNoPdfEntries("這個日期範圍沒有日記，請選擇其他範圍。");
            return;
        }
        ArrayList<JournalStore.Entry> ordered = new ArrayList<>(entries);
        Collections.sort(ordered, Comparator
                .comparing((JournalStore.Entry entry) -> entry.date)
                .thenComparing(entry -> entry.createdAt));
        pendingPdfEntryIds.clear();
        for (JournalStore.Entry entry : ordered) pendingPdfEntryIds.add(entry.id);
        pendingPdfRangeLabel = rangeLabel;
        openPdfPicker(fileName);
    }

    private void showNoPdfEntries(String message) {
        new AlertDialog.Builder(this)
                .setTitle("沒有可輸出的日記")
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void openPdfPicker(String fileName) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_TITLE, fileName);
        try {
            startActivityForResult(intent, EXPORT_PDF);
        } catch (Exception error) {
            clearPendingPdfExport();
            Toast.makeText(this, "無法開啟檔案選擇器", Toast.LENGTH_LONG).show();
        }
    }

    private void writePdf(Uri uri) {
        ArrayList<JournalStore.Entry> entries = new ArrayList<>();
        for (String id : pendingPdfEntryIds) {
            JournalStore.Entry entry = store.find(id);
            if (entry != null) entries.add(entry);
        }
        String rangeLabel = pendingPdfRangeLabel;
        clearPendingPdfExport();
        if (entries.isEmpty()) {
            showNoPdfEntries("找不到原本選取的日記，請重新選擇輸出範圍。");
            return;
        }

        AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("正在建立 PDF")
                .setMessage("正在排版 " + entries.size() + " 篇日記，請稍候。")
                .setCancelable(false)
                .create();
        progress.show();
        new Thread(() -> {
            Exception failure = null;
            try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                if (output == null) throw new IOException("無法寫入 PDF");
                PdfExporter.write(entries, rangeLabel, output);
                output.flush();
            } catch (Exception error) {
                failure = error;
                try {
                    getContentResolver().delete(uri, null, null);
                } catch (Exception ignored) { }
            }
            Exception result = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                progress.dismiss();
                if (result == null) {
                    Toast.makeText(this, "PDF 已匯出", Toast.LENGTH_LONG).show();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle("PDF 匯出失敗")
                            .setMessage("無法寫入選擇的位置。請確認儲存空間後，改選其他資料夾再試一次。")
                            .setPositiveButton("知道了", null)
                            .show();
                }
            });
        }, "journal-pdf-export").start();
    }

    private void clearPendingPdfExport() {
        pendingPdfEntryIds.clear();
        pendingPdfRangeLabel = "";
    }

    private void openExportPicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE,
                "liubai-journal-backup-" + LocalDate.now() + ".json");
        try {
            startActivityForResult(intent, EXPORT_BACKUP);
        } catch (Exception error) {
            Toast.makeText(this, "無法開啟檔案選擇器", Toast.LENGTH_LONG).show();
        }
    }

    private void openImportPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/json", "text/json", "text/plain"});
        try {
            startActivityForResult(intent, IMPORT_BACKUP);
        } catch (Exception error) {
            Toast.makeText(this, "無法開啟檔案選擇器", Toast.LENGTH_LONG).show();
        }
    }

    private void writeBackup(Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IOException("無法寫入目標檔案");
            output.write(store.exportJson().getBytes(StandardCharsets.UTF_8));
            output.flush();
            Toast.makeText(this, "日記備份已匯出", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            new AlertDialog.Builder(this)
                    .setTitle("匯出失敗")
                    .setMessage("無法寫入選擇的位置，請改選其他資料夾再試一次。")
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }

    private void confirmImport(Uri uri) {
        new AlertDialog.Builder(this)
                .setTitle("匯入這份備份？")
                .setMessage("備份內容會與目前日記合併，不會先清空現有資料。同一篇日記會保留更新時間較新的版本。")
                .setNegativeButton("取消", null)
                .setPositiveButton("匯入", (dialog, which) -> readBackup(uri))
                .show();
    }

    private void readBackup(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("無法讀取備份檔");
            String json = readLimitedUtf8(input);
            JournalStore.ImportResult result = store.importJson(json);
            renderCurrentTopLevel();
            String message;
            if (result.changed() == 0 && result.rejected == 0) {
                message = "沒有需要匯入的新內容。\n已略過 " + result.skipped + " 篇重複日記。";
            } else {
                message = "新增 " + result.added + " 篇\n更新 " + result.updated
                        + " 篇\n略過 " + result.skipped + " 篇";
                if (result.rejected > 0) message += "\n無效資料 " + result.rejected + " 筆";
            }
            new AlertDialog.Builder(this)
                    .setTitle("匯入完成")
                    .setMessage(message)
                    .setPositiveButton("完成", null)
                    .show();
        } catch (Exception error) {
            new AlertDialog.Builder(this)
                    .setTitle("匯入失敗")
                    .setMessage("這個檔案不是可用的留白日記備份，或檔案已損壞。請選擇由本 App 匯出的 JSON 檔。")
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }

    private String readLimitedUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BACKUP_BYTES) throw new IOException("備份檔超過 5 MB");
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private void openEntry(String id, boolean created, boolean fromCalendar) {
        if (!fromCalendar) captureHomeState();
        editing = store.find(id);
        if (editing == null) return;
        returnToTopLevelTab = fromCalendar ? TAB_DIARY : topLevelTab;
        calendarOpen = false;
        returnToCalendar = fromCalendar;
        newEntry = created;
        editorMode = created;
        if (created) renderEditor(); else renderReader();
    }

    private void captureHomeState() {
        if (searchField != null) homeSearchQuery = searchField.getText().toString();
        if (homeScroll != null) homeScrollY = homeScroll.getScrollY();
    }

    private void renderReader() {
        editorMode = false;
        newEntry = false;
        focusBodyOnOpen = false;
        saveStatus = null;

        LinearLayout root = column();
        root.setBackgroundColor(background);
        root.setFitsSystemWindows(true);
        setContentView(root);

        LinearLayout topBar = row();
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(gutter(), dp(8), gutter(), dp(8));
        root.addView(topBar, new LinearLayout.LayoutParams(-1, dp(64)));

        TextView back = quietButton("返回", ink, this::finishViewing);
        back.setContentDescription("返回日記列表");
        topBar.addView(back, new LinearLayout.LayoutParams(dp(72), dp(48)));
        topBar.addView(new View(this), new LinearLayout.LayoutParams(dp(40), dp(48)));
        TextView readerTitle = text("閱讀日記", 17, ink, true);
        readerTitle.setGravity(Gravity.CENTER);
        topBar.addView(readerTitle, new LinearLayout.LayoutParams(0, dp(48), 1));
        ImageButton more = iconButton("ic_more_vert", "更多日記功能");
        more.setOnClickListener(this::showReaderMenu);
        topBar.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView edit = quietButton("編輯", primary, this::enterEditMode);
        edit.setContentDescription("編輯這篇日記");
        topBar.addView(edit, new LinearLayout.LayoutParams(dp(64), dp(48)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout content = column();
        content.setPadding(gutter(), dp(16), gutter(), dp(56));
        scroll.addView(content);

        LinearLayout metadata = row();
        metadata.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(metadata);
        TextView date = text(formatLongDate(editing.date), 14, accent, true);
        metadata.addView(date, new LinearLayout.LayoutParams(0, dp(40), 1));
        TextView mood = text(editing.mood, 12, accent, true);
        mood.setGravity(Gravity.CENTER);
        mood.setBackground(chipBackground(false));
        metadata.addView(mood, new LinearLayout.LayoutParams(dp(68), dp(36)));

        LinearLayout readerInfo = row();
        readerInfo.setGravity(Gravity.CENTER_VERTICAL);
        readerInfo.setPadding(0, dp(8), 0, dp(12));
        content.addView(readerInfo, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = text("雙擊日記內容即可編輯", 13, muted, false);
        readerInfo.addView(hint, new LinearLayout.LayoutParams(0, dp(32), 1));
        TextView readerCharacterCount = text("", 13, primary, true);
        readerCharacterCount.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        updateCharacterCount(readerCharacterCount);
        readerInfo.addView(readerCharacterCount, new LinearLayout.LayoutParams(-2, dp(32)));

        LinearLayout entryCard = column();
        entryCard.setPadding(dp(20), dp(20), dp(20), dp(24));
        entryCard.setBackground(fieldBackground());
        entryCard.setContentDescription("日記內容。雙擊即可編輯，也可以使用上方的編輯按鈕");

        String titleValue = editing.title.trim();
        if (!titleValue.isEmpty()) {
            TextView title = text(titleValue, 24, ink, true);
            title.setLineSpacing(dp(2), 1.1f);
            title.setPadding(0, 0, 0, dp(16));
            entryCard.addView(title);
        }

        String bodyValue = editing.body.trim();
        if (bodyValue.isEmpty()) bodyValue = "這篇日記還沒有內容。";
        TextView body = text(bodyValue, 17, ink, false);
        body.setLineSpacing(dp(5), 1.2f);
        entryCard.addView(body, new LinearLayout.LayoutParams(-1, -2));

        GestureDetector doubleTap = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent event) { return true; }
                    @Override public boolean onDoubleTap(MotionEvent event) {
                        enterEditMode();
                        return true;
                    }
                });
        entryCard.setOnTouchListener((view, event) -> doubleTap.onTouchEvent(event));
        content.addView(entryCard, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showReaderMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, MENU_EXPORT_ENTRY_PDF, 0, "匯出這篇 PDF");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() != MENU_EXPORT_ENTRY_PDF || editing == null) return false;
            preparePdfExport(Collections.singletonList(editing),
                    formatLongDate(editing.date),
                    "liubai-journal-" + editing.date + ".pdf");
            return true;
        });
        menu.show();
    }

    private void enterEditMode() {
        editorMode = true;
        newEntry = false;
        focusBodyOnOpen = true;
        renderEditor();
    }

    private void finishViewing() {
        renderEntryReturnDestination();
    }

    private void renderEntryReturnDestination() {
        if (returnToCalendar) {
            renderCalendar();
            return;
        }
        topLevelTab = returnToTopLevelTab;
        renderCurrentTopLevel();
    }

    private void renderEditor() {
        editorMode = true;
        suppressSave = true;
        LinearLayout root = column();
        root.setBackgroundColor(background);
        root.setFitsSystemWindows(true);
        setContentView(root);

        LinearLayout topBar = row();
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(gutter(), dp(8), gutter(), dp(8));
        root.addView(topBar, new LinearLayout.LayoutParams(-1, dp(64)));

        TextView back = quietButton("完成", ink, this::finishEditing);
        back.setContentDescription("儲存並返回只讀模式");
        topBar.addView(back, new LinearLayout.LayoutParams(dp(72), dp(48)));
        TextView editorTitle = text("", 17, ink, true);
        editorTitle.setGravity(Gravity.CENTER);
        updateEditorTitleCharacterCount(editorTitle);
        topBar.addView(editorTitle, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView delete = quietButton("刪除", danger, this::confirmDelete);
        delete.setContentDescription("刪除這篇日記");
        topBar.addView(delete, new LinearLayout.LayoutParams(dp(72), dp(48)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = column();
        content.setPadding(gutter(), dp(16), gutter(), dp(48));
        scroll.addView(content);

        TextView dateLabel = fieldLabel("日期");
        content.addView(dateLabel);
        TextView dateButton = actionButton(formatLongDate(editing.date), surface, ink, this::pickDate);
        dateButton.setGravity(Gravity.CENTER_VERTICAL);
        dateButton.setPadding(dp(16), 0, dp(16), 0);
        dateButton.setContentDescription("目前日期" + formatLongDate(editing.date) + "，點一下修改");
        content.addView(dateButton, new LinearLayout.LayoutParams(-1, dp(52)));

        TextView moodLabel = fieldLabel("今天的心情");
        moodLabel.setPadding(0, dp(24), 0, dp(8));
        content.addView(moodLabel);
        HorizontalScrollView moodScroll = new HorizontalScrollView(this);
        moodScroll.setHorizontalScrollBarEnabled(false);
        content.addView(moodScroll, new LinearLayout.LayoutParams(-1, dp(48)));
        moodRow = row();
        moodScroll.addView(moodRow);
        renderMoodButtons();

        TextView titleLabel = fieldLabel("標題（可以留白）");
        titleLabel.setPadding(0, dp(24), 0, dp(8));
        content.addView(titleLabel);
        EditText title = editorField(editing.title, "今天最想記住什麼？", false);
        title.setSingleLine(true);
        content.addView(title, new LinearLayout.LayoutParams(-1, dp(56)));

        TextView bodyLabel = fieldLabel("內容");
        bodyLabel.setPadding(0, dp(24), 0, dp(8));
        content.addView(bodyLabel);
        EditText body = editorField(editing.body, "從今天發生的一件小事開始……", true);
        body.setGravity(Gravity.TOP | Gravity.START);
        body.setMinLines(12);
        body.setMinHeight(dp(360));
        body.setHorizontallyScrolling(false);
        body.setVerticalScrollBarEnabled(false);
        body.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        content.addView(body, new LinearLayout.LayoutParams(-1, -2));

        saveStatus = text("已儲存在這台裝置", 13, muted, false);
        saveStatus.setGravity(Gravity.CENTER);
        saveStatus.setPadding(0, dp(16), 0, 0);
        content.addView(saveStatus);

        title.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable value) {
                if (suppressSave) return;
                editing.title = value.toString();
                updateEditorTitleCharacterCount(editorTitle);
                persistEdit();
            }
        });
        body.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable value) {
                if (suppressSave) return;
                editing.body = value.toString();
                updateEditorTitleCharacterCount(editorTitle);
                persistEdit();
            }
        });
        suppressSave = false;
        if (newEntry || focusBodyOnOpen) {
            focusBodyOnOpen = false;
            body.requestFocus();
            body.postDelayed(() -> {
                InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                keyboard.showSoftInput(body, InputMethodManager.SHOW_IMPLICIT);
            }, 180);
        }
    }

    private void renderMoodButtons() {
        moodRow.removeAllViews();
        for (String mood : MOODS) {
            boolean selected = mood.equals(editing.mood);
            TextView chip = text(mood, 14, selected ? (dark ? background : Color.WHITE) : ink, true);
            chip.setGravity(Gravity.CENTER);
            chip.setMinWidth(dp(72));
            chip.setBackground(rippleChip(selected));
            chip.setContentDescription("心情" + mood + (selected ? "，已選取" : ""));
            chip.setOnClickListener(view -> {
                editing.mood = mood;
                persistEdit();
                renderMoodButtons();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(44));
            params.rightMargin = dp(8);
            moodRow.addView(chip, params);
        }
    }

    private void persistEdit() {
        saveHandler.removeCallbacks(pendingSave);
        if (saveStatus != null) {
            saveStatus.setText("正在儲存……");
            saveStatus.setTextColor(accent);
        }
        saveHandler.postDelayed(pendingSave, 350);
    }

    private void updateCharacterCount(TextView countView) {
        int characters = editing == null ? 0 : entryCharacterCount(editing);
        String countText = formatNumber(characters) + " 字";
        countView.setText(countText);
        countView.setContentDescription("目前共 " + countText + "，不包含空格與換行");
    }

    private void updateEditorTitleCharacterCount(TextView titleView) {
        int characters = editing == null ? 0 : entryCharacterCount(editing);
        String countText = formatNumber(characters) + " 字";
        titleView.setText("編輯日記 · " + countText);
        titleView.setContentDescription("編輯日記，目前共 " + countText + "，不包含空格與換行");
    }

    private void flushPendingSave() {
        if (editing == null || !editorMode) return;
        saveHandler.removeCallbacks(pendingSave);
        pendingSave.run();
    }

    private void pickDate() {
        LocalDate current = LocalDate.parse(editing.date);
        new DatePickerDialog(this, (picker, year, month, day) -> {
            editing.date = LocalDate.of(year, month + 1, day).toString();
            persistEdit();
            renderEditor();
        }, current.getYear(), current.getMonthValue() - 1, current.getDayOfMonth()).show();
    }

    private void finishEditing() {
        hideKeyboard();
        flushPendingSave();
        if (editing != null && editing.title.trim().isEmpty() && editing.body.trim().isEmpty()) {
            store.delete(editing.id);
            renderEntryReturnDestination();
            return;
        }
        renderReader();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("刪除這篇日記？")
                .setMessage("刪除後無法復原。")
                .setNegativeButton("取消", null)
                .setPositiveButton("刪除", (dialog, which) -> {
                    saveHandler.removeCallbacks(pendingSave);
                    store.delete(editing.id);
                    Toast.makeText(this, "已刪除日記", Toast.LENGTH_SHORT).show();
                    hideKeyboard();
                    renderEntryReturnDestination();
                }).show();
    }

    private EditText editorField(String value, String hint, boolean multiline) {
        EditText field = new EditText(this);
        field.setText(value);
        field.setHint(hint);
        field.setHintTextColor(muted);
        field.setTextColor(ink);
        field.setTextSize(multiline ? 17 : 18);
        field.setLineSpacing(dp(4), 1.15f);
        field.setPadding(dp(16), dp(14), dp(16), dp(14));
        field.setBackground(fieldBackground());
        field.setSelectAllOnFocus(false);
        return field;
    }

    private TextView fieldLabel(String value) {
        TextView label = text(value, 13, muted, true);
        label.setPadding(0, 0, 0, dp(8));
        return label;
    }

    private TextView actionButton(String label, int fill, int foreground, Runnable action) {
        TextView button = text(label, 16, foreground, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(ripple(fill, 14));
        button.setOnClickListener(view -> action.run());
        button.setFocusable(true);
        button.setClickable(true);
        return button;
    }

    private TextView secondaryButton(String label, Runnable action) {
        TextView button = text(label, 15, primary, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(rippleSurface(surface, 14));
        button.setOnClickListener(view -> action.run());
        button.setFocusable(true);
        button.setClickable(true);
        return button;
    }

    private TextView quietButton(String label, int foreground, Runnable action) {
        TextView button = text(label, 15, foreground, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(ripple(Color.TRANSPARENT, 12));
        button.setOnClickListener(view -> action.run());
        button.setFocusable(true);
        return button;
    }

    private ImageButton iconButton(String drawableName, String description) {
        ImageButton button = new ImageButton(this);
        int drawable = getResources().getIdentifier(
                drawableName, "drawable", getPackageName());
        button.setImageResource(drawable);
        button.setImageTintList(ColorStateList.valueOf(primary));
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setBackground(ripple(Color.TRANSPARENT, 24));
        button.setContentDescription(description);
        button.setFocusable(true);
        button.setClickable(true);
        return button;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private GradientDrawable fieldBackground() {
        GradientDrawable drawable = rounded(surface, 14);
        drawable.setStroke(dp(1), border);
        return drawable;
    }

    private GradientDrawable chipBackground(boolean selected) {
        GradientDrawable drawable = rounded(selected ? primary : (dark ? Color.rgb(37, 50, 70) : Color.rgb(255, 247, 237)), 18);
        if (!selected) drawable.setStroke(dp(1), dark ? border : Color.rgb(253, 186, 116));
        return drawable;
    }

    private RippleDrawable ripple(int fill, float radius) {
        GradientDrawable content = rounded(fill, radius);
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(ink, 28)), content, null);
    }

    private RippleDrawable rippleSurface(int fill, float radius) {
        GradientDrawable content = rounded(fill, radius);
        content.setStroke(dp(1), border);
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(primary, 34)), content, null);
    }

    private RippleDrawable rippleChip(boolean selected) {
        GradientDrawable content = chipBackground(selected);
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(primary, 42)), content, null);
    }

    private RippleDrawable calendarDayBackground(boolean hasEntries, boolean selected, boolean today) {
        GradientDrawable content = rounded(selected ? primary : surface, 14);
        if (selected) {
            content.setStroke(dp(2), primary);
        } else if (hasEntries) {
            content.setStroke(dp(2), accent);
        } else if (today) {
            content.setStroke(dp(1), primary);
        }
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(primary, 42)), content, null);
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) return;
        InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        focus.clearFocus();
    }

    private int dp(float amount) {
        return (int) (amount * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int gutter() {
        int width = getResources().getConfiguration().screenWidthDp;
        if (width >= 840) return dp(96);
        if (width >= 600) return dp(56);
        return dp(20);
    }

    private String weekdayGreeting() {
        int hour = java.time.LocalTime.now().getHour();
        if (hour < 11) return "早安，今天也慢慢來";
        if (hour < 18) return "午安，留一點時間給自己";
        return "晚安，收好今天的心情";
    }

    private String formatDate(String isoDate) {
        LocalDate date = LocalDate.parse(isoDate);
        return date.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日，E", LOCALE));
    }

    private String formatLongDate(String isoDate) {
        LocalDate date = LocalDate.parse(isoDate);
        return date.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日  EEEE", LOCALE));
    }

    private String firstMeaningfulLine(String value) {
        String[] lines = value.split("\\R");
        for (String line : lines) if (!line.trim().isEmpty()) return line.trim();
        return "";
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
        @Override public void onTextChanged(CharSequence value, int start, int before, int count) { }
    }
}
