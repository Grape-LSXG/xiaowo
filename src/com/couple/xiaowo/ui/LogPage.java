package com.couple.xiaowo.ui;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.couple.xiaowo.core.Logs;

import java.util.List;

/**
 * 「设置 → 日志」页（只在专业模式里出现）。
 *
 * 目的很朴素：出问题时能说清"刚才发生了什么"。用户看不懂也没关系，
 * 他只要点一下「复制」把这段发过来，我就能顺着时间线找问题，
 * 而不是靠猜。
 */
public final class LogPage {

    private LogPage() {
    }

    public static void show(final Activity act) {
        DialogKit.sheet(act, "🧾 运行日志", build(act));
    }

    private static View build(final Activity act) {
        LinearLayout box = Ui.vertical(act);

        List<Logs.Line> lines = Logs.recent(300);
        if (lines.isEmpty()) {
            TextView empty = Ui.tv(act, "还没有日志。\n连一次、发一条消息，这里就会有内容。",
                    11.5f, Ui.TEXT_MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dpi(act, 40), 0, Ui.dpi(act, 40));
            box.addView(empty);
        } else {
            TextView body = Ui.tv(act, join(lines), 10.5f, Ui.TEXT_SUB, false);
            body.setTextIsSelectable(true);
            body.setTypeface(android.graphics.Typeface.MONOSPACE);
            body.setLineSpacing(0f, 1.25f);
            body.setPadding(Ui.dpi(act, 10), Ui.dpi(act, 10), Ui.dpi(act, 10), Ui.dpi(act, 10));
            body.setBackground(Ui.round(0xFFF7F5FA, Ui.dp(act, 10)));
            // 面板本身就能滚（DialogKit.sheet 里是 MaxHeightScroll），
            // 这里再套一层 ScrollView 会出现"两层都要滚"的手感灾难
            box.addView(body);
        }

        // ---- 底部操作 ----
        LinearLayout row = Ui.horizontal(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dpi(act, 12), 0, 0);

        TextView copy = Ui.pill(act, "复制全部", Ui.PRIMARY, 0xFFFFFFFF, 12, 18, 8, true);
        copy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) act.getSystemService(
                                Activity.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("小窝日志", Logs.dump()));
                    Toast2.ok(act, "已复制，粘给我就行");
                }
            }
        });
        row.addView(copy);

        TextView clear = Ui.pill(act, "清空", Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP, 12, 18, 8, true);
        LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clLp.leftMargin = Ui.dpi(act, 8);
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Logs.clear();
                Toast2.ok(act, "日志已清空");
            }
        });
        row.addView(clear, clLp);
        box.addView(row);

        TextView hint = Ui.tv(act, "日志只留在本机（缓存目录 + 内存），不会上传。", 10,
                Ui.TEXT_MUTED, false);
        hint.setPadding(0, Ui.dpi(act, 10), 0, 0);
        box.addView(hint);

        return box;
    }

    private static String join(List<Logs.Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (Logs.Line l : lines) {
            sb.append(l.stamp()).append("  ").append(l.tag).append("  ").append(l.text)
                    .append('\n');
        }
        return sb.toString();
    }
}
