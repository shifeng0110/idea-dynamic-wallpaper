package com.dwallpaper.welibrary;

/**
 * 单个壁纸条目（design.md §3.2）。type 已归一化小写：
 * video/web（产品级可渲染，t4 面板标注「需 JCEF/t5」禁选）/gif/sequence/static（t4 可渲染）
 * /scene/application/other（仅索引）。file/preview/media 路径原样拼接
 * （t1 实测含空格/括号/非 ASCII，不做 trim/转义假设）。
 *
 * @param mediaPath      媒体文件或目录（GIF 文件/序列帧目录/静态图；video 为视频文件，t5 用）
 * @param sourceDir      来源枚举目录（defaultprojects/myprojects/downloaded/工坊 431960/manual）
 */
public record WeEntry(
        String id,
        String title,
        String type,
        String folderPath,
        String projectJsonPath,
        String mediaPath,
        String previewPath,
        boolean supported,
        String sourceDir) {
}
