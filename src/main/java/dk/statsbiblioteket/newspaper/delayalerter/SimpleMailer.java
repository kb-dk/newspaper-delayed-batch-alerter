package dk.statsbiblioteket.newspaper.delayalerter;

import org.apache.zookeeper.server.SessionTracker;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.List;
import java.util.Properties;

/**
 * A simple mail-sending utility.
 */
public class SimpleMailer {

    private String from;
    private String host;
    private String port;

    /**
     * Constructor for this class.
     * @param from the from address field for emails.
     * @param host the smtp host to use.
     * @param port the smtp port of the host.
     */
    public SimpleMailer(String from, String host, String port) {
        this.from = from;
        this.host = host;
        this.port = port;
    }

    /**
     * Send a mail.
     * @param to A list of recipients.
     * @param subject The text of the email subject.
     * @param text The text of the email.
     * @throws MessagingException
     */
    public void sendMail(List<String> to, String subject, String text) throws MessagingException {
        Properties props = new Properties();
        props.setProperty("mail.smtp.host", host);
        props.setProperty("mail.smtp.port", port);
        javax.mail.Session session = Session.getDefaultInstance(props);
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from));
        for (String recipient: to) {
            message.addRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
        }
        message.setSubject(subject);
        message.setText(text);
        Transport.send(message);
    }
}
