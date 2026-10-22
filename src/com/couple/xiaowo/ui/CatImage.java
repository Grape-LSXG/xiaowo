package com.couple.xiaowo.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.SparseArray;

import java.io.InputStream;

/**
 * 菜单里的猫猫图：从 assets/cats/*.png 读取。
 *
 * 这些图是从网上找的「呆猫/猫八条」表情包（白底/浅色底），
 * 直接贴到白色卡片上，再用圆角裁切，看起来就是贴纸。
 * 如果哪天 assets 没了，界面会优雅地退化成纯色块，不会崩。
 */
public final class CatImage {

    public static final int COUNT = 8;

    private static final SparseArray<Bitmap> CACHE = new SparseArray<Bitmap>();

    private CatImage() {
    }

    public static Bitmap bitmap(Context c, int index) {
        int i = Math.abs(index) % COUNT;
        Bitmap cached = CACHE.get(i);
        if (cached != null) {
            return cached;
        }
        InputStream in = null;
        try {
            in = c.getAssets().open("cats/" + i + ".png");
            Bitmap bm = BitmapFactory.decodeStream(in);
            if (bm != null) {
                CACHE.put(i, bm);
            }
            return bm;
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignore) {
                }
            }
        }
    }
}
