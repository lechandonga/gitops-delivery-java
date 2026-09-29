package com.github.lechandonga.gitopsdeliveryjava.model;

/**
 * 目标曾经稳定发布过的一个历史版本条目（版本历史据此重建与持久化）。
 *
 * @param version      曾经成为稳定版本的版本号
 * @param firstStableAt 首次成为稳定版本的时间戳
 * @param actor        使其稳定发布的触发者（事件缺失时可能为 unknown）
 * @param flowId       使其稳定发布的流程（事件缺失时可能为 null）
 */
public record VersionHistoryEntry(String version, long firstStableAt, String actor, String flowId) {
}
