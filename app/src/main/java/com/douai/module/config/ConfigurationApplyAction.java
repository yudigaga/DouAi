package com.douai.module.config;

import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/** A retained, configuration-only action whose successful stages survive a safe retry. */
@FunctionalInterface
public interface ConfigurationApplyAction {
    /** Complete only after this attempt's local reconciliation finishes; check current before mutation. */
    CompletionStage<Void> apply(BooleanSupplier current);

    /** An uncertain one-time effect must remain failed instead of being replayed. */
    final class PermanentFailure extends RuntimeException {
        public PermanentFailure(Throwable cause) { super(cause); }
        public PermanentFailure(String message, Throwable cause) { super(message, cause); }
    }
}
