package com.dwallpaper.fog;

/**
 * 六区雾镜（design.md §6.1，D7：左右工具窗共用一组参数；区模型按 region 扩展，可平移为七区）。
 */
public enum FogZone {
    /** Z1 编辑器组件区。 */
    EDITOR,
    /** Z2 左右工具窗条带与内容区（共参）。 */
    TOOLWINDOW_SIDE,
    /** Z3 底部工具窗区。 */
    TOOLWINDOW_BOTTOM,
    /** Z4 主工具栏。 */
    TOOLBAR,
    /** Z5 状态栏。 */
    STATUSBAR,
    /** Z6 弹窗浮层（可选，默认关）。 */
    POPUP
}
