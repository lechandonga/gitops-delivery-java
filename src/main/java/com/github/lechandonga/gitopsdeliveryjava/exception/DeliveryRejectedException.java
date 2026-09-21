package com.github.lechandonga.gitopsdeliveryjava.exception;

import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;

/**
 * 业务拒绝异常：携带可区分原因码，且语义上“拒绝不得改变已发布状态”。
 */
public class DeliveryRejectedException extends RuntimeException {

    private final ReasonCode reasonCode;

    public DeliveryRejectedException(ReasonCode reasonCode) {
        super(reasonCode.getDefaultMessage());
        this.reasonCode = reasonCode;
    }

    public DeliveryRejectedException(ReasonCode reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public ReasonCode getReasonCode() {
        return reasonCode;
    }
}
