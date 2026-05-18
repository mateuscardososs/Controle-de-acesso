package br.com.sport.accesscontrol.mail;

public record MailDeliveryResult(
        boolean attempted,
        boolean sent,
        String status,
        String message
) {
    public static MailDeliveryResult skipped(String message) {
        return new MailDeliveryResult(false, false, "SKIPPED", message);
    }

    public static MailDeliveryResult delivered() {
        return new MailDeliveryResult(true, true, "SENT", "Email sent.");
    }

    public static MailDeliveryResult failed(String message) {
        return new MailDeliveryResult(true, false, "FAILED", message);
    }
}
