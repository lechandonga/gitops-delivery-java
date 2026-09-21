package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.compat.ManifestSerde;
import com.github.lechandonga.gitopsdeliveryjava.model.Manifest;

/**
 * 测试用清单构造辅助。
 */
final class TestSupport {

    private TestSupport() {
    }

    static Manifest single(String target, String version) {
        return new ManifestSerde().parse("""
                {
                  "apiVersion": "gitops.local/v1",
                  "kind": "DeliveryManifest",
                  "targets": {
                    "%s": { "desiredVersion": "%s" }
                  }
                }
                """.formatted(target, version));
    }
}
