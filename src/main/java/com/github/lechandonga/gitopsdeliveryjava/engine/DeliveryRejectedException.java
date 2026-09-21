package com.github.lechandonga.gitopsdeliveryjava.engine;

/**
 * 收敛被拒绝时抛出：携带可区分的原因码与是否变更状态的说明。
 * 拒绝类原因一律不改变已发布版本状态。
 */
public class DeliveryRejectedException extends RuntimeException {
    private final String reasonCode;

    public DeliveryRejectedException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
