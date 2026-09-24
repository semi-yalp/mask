package io.sqlmask.riskserver.notify;

/**
 * One outbound alert notification: always recorded locally for the console's
 * notification log; optionally delivered to a configured webhook.
 *
 * @param delivery SENT (webhook accepted) | RECORDED (no webhook configured,
 *                 local log only) | FAILED (webhook rejected/timed out)
 */
public record Notification(
    String id,
    long createdAt,
    String alertId,
    String ruleId,
    String severity,
    String user,
    String title,
    String delivery,
    String detail) {
}
