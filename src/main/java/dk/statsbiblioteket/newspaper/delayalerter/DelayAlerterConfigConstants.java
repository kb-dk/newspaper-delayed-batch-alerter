package dk.statsbiblioteket.newspaper.delayalerter;

/**
 * Class containing constants specifying the Property keys for this component.
 */
public final class DelayAlerterConfigConstants {

    /**
     * The number of days from when a batch is received until it is expected to be approved.
     */
    public static final String DELAY_ALERT_DAYS = "delay.alert.days";

    /**
     * A comma-separated list of email addresses to which alert messages will be sent.
     */
    public static final String DELAY_ALERT_EMAIL_ADDRESSES = "delay.alert.email.addresses";

    /**
     * The smtp host.
     */
    public static final String SMTP_HOST = "delay.alert.smtp.host";

    /**
     * The smtp port.
     */
    public static final String SMTP_PORT = "delay.alert.smtp.port";

    /**
     * The "from" email address.
     */
    public static final String EMAIL_FROM_ADDRESS = "delay.alert.email.from.address";

    /**
     * Private constructor as this class should not be instantiated.
     */
    private DelayAlerterConfigConstants() {
    }
}
