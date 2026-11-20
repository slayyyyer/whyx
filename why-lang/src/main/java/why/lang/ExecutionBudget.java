package why.lang;

import why.RequestTimeout;
import why.WhyError;

/** Per-request limits for interpreter work and rendered output. */
public final class ExecutionBudget {
    private final long maxSteps;
    private final long maxOutputChars;
    private final long deadlineNanos;
    private long steps;
    private long outputChars;

    public ExecutionBudget(long maxSteps, long maxOutputChars, long deadlineNanos) {
        this.maxSteps = maxSteps;
        this.maxOutputChars = maxOutputChars;
        this.deadlineNanos = deadlineNanos;
    }

    /** For direct interpreter/template use outside the server. */
    public static ExecutionBudget unlimited() {
        return new ExecutionBudget(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    }

    public void step() {
        if (Thread.currentThread().isInterrupted()
                || (deadlineNanos != Long.MAX_VALUE && System.nanoTime() > deadlineNanos)) {
            throw new RequestTimeout();
        }
        if (++steps > maxSteps) {
            throw new WhyError("interpreter step budget exceeded");
        }
    }

    public void output(int chars) {
        if (chars <= 0) return;
        outputChars += chars;
        if (outputChars > maxOutputChars) {
            throw new WhyError("output budget exceeded");
        }
    }
}
