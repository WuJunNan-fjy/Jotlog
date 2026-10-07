package com.jotlog.core;

/**
 * 条目类型。
 *
 * 决定后续增强策略，尤其是 AI 该做什么、不该做什么。
 * 特别地NOTE 类型默认跳过 AI 润色——用户的原话不该被改写。
 */
public enum EntryType {

    /** 普通网址。抓正文，AI 写一句话摘要。 */
    LINK,

    /** GitHub / Gitee 等代码仓库。抓 README，AI 说明"是干什么的"。 */
    REPO,

    /** 视频链接。多数平台抓不到正文，只取标题，不做内容总结。 */
    VIDEO,

    /** 用户随手写下的话、触动、灵感。默认不做 AI 润色。 */
    NOTE,

    /** 纯文件附件。 */
    FILE,

    /** 图片。 */
    IMAGE,

    /** 还没判定。 */
    UNKNOWN
}
