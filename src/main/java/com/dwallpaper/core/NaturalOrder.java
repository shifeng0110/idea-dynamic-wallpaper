package com.dwallpaper.core;

import java.util.Comparator;

/**
 * 自然序比较器（F17 单一实现，收敛 WeScanner/SequenceFrameSource 两份重复）：
 * 数字段比较【先比长度、再逐位字典序】——永不溢出（不使用 Long.parseLong），
 * 任意长度数字段安全；同名段含前导零时按字典序（非数值序）为已知取舍，已文档化。
 */
public final class NaturalOrder {

    private static final Comparator<String> INSTANCE = (a, b) -> {
        int ia = 0;
        int ib = 0;
        while (ia < a.length() && ib < b.length()) {
            char ca = a.charAt(ia);
            char cb = b.charAt(ib);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int ja = ia;
                int jb = ib;
                while (ja < a.length() && Character.isDigit(a.charAt(ja))) ja++;
                while (jb < b.length() && Character.isDigit(b.charAt(jb))) jb++;
                int la = ja - ia;
                int lb = jb - ib;
                if (la != lb) {
                    // 长度不同：位数多者数值大（前导零情形按字典序回退，见类注释）
                    boolean aAllZero = isAllZero(a, ia, ja);
                    boolean bAllZero = isAllZero(b, ib, jb);
                    if (aAllZero && bAllZero) {
                        ia = ja;
                        ib = jb;
                        continue;
                    }
                    if (aAllZero) {
                        return -1;
                    }
                    if (bAllZero) {
                        return 1;
                    }
                    return Integer.compare(la, lb);
                }
                // 等长数字段逐位比较（无溢出）
                for (int k = ia; k < ja; k++) {
                    if (a.charAt(k) != b.charAt(k)) {
                        return Character.compare(a.charAt(k), b.charAt(k));
                    }
                }
                ia = ja;
                ib = jb;
            } else {
                if (ca != cb) {
                    return Character.compare(ca, cb);
                }
                ia++;
                ib++;
            }
        }
        return Integer.compare(a.length() - ia, b.length() - ib);
    };

    private NaturalOrder() {
    }

    private static boolean isAllZero(String s, int from, int to) {
        for (int k = from; k < to; k++) {
            if (s.charAt(k) != '0') {
                return false;
            }
        }
        return true;
    }

    /** 单例比较器（不可变、线程安全）。 */
    public static Comparator<String> comparator() {
        return INSTANCE;
    }
}
