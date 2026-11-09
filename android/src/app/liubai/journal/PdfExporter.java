package app.liubai.journal;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class PdfExporter {
    private static final int PAGE_WIDTH = 595;
    private static final int PAGE_HEIGHT = 842;
    private static final float MARGIN = 48f;
    private static final float CONTENT_WIDTH = PAGE_WIDTH - MARGIN * 2f;
    private static final float CONTENT_BOTTOM = PAGE_HEIGHT - 58f;
    private static final Locale LOCALE = Locale.TAIWAN;

    private PdfExporter() { }

    static void write(List<JournalStore.Entry> entries, String rangeLabel,
                      OutputStream output) throws IOException {
        PdfDocument document = new PdfDocument();
        try {
            Renderer renderer = new Renderer(document);
            renderer.startPage();
            renderer.drawDocumentHeading(rangeLabel, entries.size());
            for (JournalStore.Entry entry : entries) renderer.drawEntry(entry);
            renderer.finishPage();
            document.writeTo(output);
        } finally {
            document.close();
        }
    }

    private static final class Renderer {
        private final PdfDocument document;
        private final Paint brandPaint = paint(11, Color.rgb(71, 85, 105), true, "serif");
        private final Paint documentTitlePaint = paint(29, Color.rgb(30, 58, 95), true, "serif");
        private final Paint documentMetaPaint = paint(11, Color.rgb(71, 85, 105), false, "sans-serif");
        private final Paint datePaint = paint(10, Color.rgb(180, 83, 9), true, "sans-serif");
        private final Paint entryTitlePaint = paint(19, Color.rgb(15, 23, 42), true, "serif");
        private final Paint bodyPaint = paint(12, Color.rgb(31, 41, 55), false, "sans-serif");
        private final Paint continuationPaint = paint(10, Color.rgb(71, 85, 105), true, "sans-serif");
        private final Paint rulePaint = paint(1, Color.rgb(203, 213, 225), false, "sans-serif");

        private PdfDocument.Page page;
        private Canvas canvas;
        private int pageNumber;
        private float y;

        Renderer(PdfDocument document) {
            this.document = document;
            rulePaint.setStrokeWidth(0.8f);
        }

        void startPage() {
            pageNumber++;
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                    PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create();
            page = document.startPage(info);
            canvas = page.getCanvas();
            canvas.drawColor(Color.WHITE);
            drawTextLine("留白日記", MARGIN, 28f, brandPaint);
            canvas.drawLine(MARGIN, 45f, PAGE_WIDTH - MARGIN, 45f, rulePaint);
            y = 68f;
        }

        void finishPage() {
            if (page == null) return;
            canvas.drawLine(MARGIN, PAGE_HEIGHT - 42f,
                    PAGE_WIDTH - MARGIN, PAGE_HEIGHT - 42f, rulePaint);
            String footer = "留白日記  ·  " + pageNumber;
            float width = documentMetaPaint.measureText(footer);
            drawTextLine(footer, PAGE_WIDTH - MARGIN - width,
                    PAGE_HEIGHT - 23f, documentMetaPaint);
            document.finishPage(page);
            page = null;
            canvas = null;
        }

        void nextPage() {
            finishPage();
            startPage();
        }

        void drawDocumentHeading(String rangeLabel, int entryCount) {
            drawWrapped("我的日記", documentTitlePaint, 34f, null);
            y += 5f;
            drawWrapped(rangeLabel, documentMetaPaint, 17f, null);
            String exported = "共 " + entryCount + " 篇  ·  匯出於 "
                    + LocalDate.now().format(DateTimeFormatter.ofPattern(
                    "yyyy 年 M 月 d 日", LOCALE));
            drawWrapped(exported, documentMetaPaint, 17f, null);
            y += 24f;
        }

        void drawEntry(JournalStore.Entry entry) {
            ensureSpace(92f);
            String date = formatDate(entry.date) + "  ·  心情：" + safe(entry.mood, "平靜");
            drawWrapped(date, datePaint, 16f, null);
            y += 3f;

            String title = safe(entry.title, "").trim();
            if (title.isEmpty()) title = "無標題日記";
            drawWrapped(title, entryTitlePaint, 26f, null);
            y += 7f;

            String body = safe(entry.body, "").trim();
            if (body.isEmpty()) {
                drawWrapped("（無內文）", documentMetaPaint, 19f, title);
            } else {
                drawWrapped(body, bodyPaint, 20f, title);
            }
            y += 18f;
            ensureSpace(1f);
            canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, rulePaint);
            y += 26f;
        }

        private void drawWrapped(String text, Paint paint, float lineHeight,
                                 String continuationTitle) {
            List<String> lines = wrap(text, paint, CONTENT_WIDTH);
            for (String line : lines) {
                if (y + lineHeight > CONTENT_BOTTOM) {
                    nextPage();
                    if (continuationTitle != null) {
                        String marker = continuationTitle + "（續）";
                        drawWrapped(marker, continuationPaint, 17f, null);
                        y += 7f;
                    }
                }
                if (!line.isEmpty()) {
                    drawTextLine(line, MARGIN, y - paint.ascent(), paint);
                }
                y += lineHeight;
            }
        }

        private void ensureSpace(float required) {
            if (y + required > CONTENT_BOTTOM) nextPage();
        }

        private void drawTextLine(String value, float x, float baseline, Paint paint) {
            canvas.drawText(value, x, baseline, paint);
        }
    }

    private static Paint paint(float size, int color, boolean bold, String family) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setTypeface(Typeface.create(family, bold ? Typeface.BOLD : Typeface.NORMAL));
        return paint;
    }

    private static List<String> wrap(String value, Paint paint, float maxWidth) {
        ArrayList<String> lines = new ArrayList<>();
        String normalized = safe(value, "").replace("\r\n", "\n").replace('\r', '\n');
        String[] paragraphs = normalized.split("\n", -1);
        for (String paragraph : paragraphs) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            int offset = 0;
            while (offset < paragraph.length()) {
                int count = paint.breakText(paragraph, offset, paragraph.length(),
                        true, maxWidth, null);
                if (count <= 0) count = Character.charCount(paragraph.codePointAt(offset));
                int end = Math.min(paragraph.length(), offset + count);
                lines.add(paragraph.substring(offset, end));
                offset = end;
            }
        }
        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    private static String formatDate(String isoDate) {
        try {
            return LocalDate.parse(isoDate).format(
                    DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日，EEEE", LOCALE));
        } catch (Exception ignored) {
            return isoDate;
        }
    }

    private static String safe(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
