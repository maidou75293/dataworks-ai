package com.lab.dataworks.flywheel;

/**
 * 低质信号枚举（REQ-FW-3 卡面三条采集规则，与 badcase_pool.signal 的 CHECK 一致）。
 */
public enum BadcaseSignal {

    /** 无答案：查询/回答返回 0 行。 */
    NO_ANSWER,

    /** 点踩：调用方显式负反馈（feedback='DOWN'）。 */
    DISLIKE,

    /** 置信度低于 sys_config 阈值。 */
    LOW_CONFIDENCE
}
