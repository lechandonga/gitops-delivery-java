package com.github.lechandonga.gitopsdeliveryjava.infra;

/** 本地模拟部署动作的结果。 */
public record DeployResult(boolean success, String endpointVersion, String message) {
}
