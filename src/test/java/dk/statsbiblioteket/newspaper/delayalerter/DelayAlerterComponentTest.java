package dk.statsbiblioteket.newspaper.delayalerter;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import dk.statsbiblioteket.medieplatform.autonomous.Batch;
import dk.statsbiblioteket.medieplatform.autonomous.Event;
import dk.statsbiblioteket.medieplatform.autonomous.ResultCollector;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import javax.mail.MessagingException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyList;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 *
 */
public class DelayAlerterComponentTest {

    GreenMail greenMail;

    /**
     * Test that no mail is sent if the roundtrip is less than the maximum allowed age.
     * @throws Exception
     */
    @Test
    public void testDoWorkOnBatchNotYetDelayed() throws Exception {
        Properties properties = new Properties();
        properties.setProperty(DelayAlerterConfigConstants.DELAY_ALERT_EMAIL_ADDRESSES, "foo@bar.com, bar@bar.com");
        properties.setProperty(DelayAlerterConfigConstants.DELAY_ALERT_DAYS, "20");
        final SimpleMailer simpleMailer = mock(SimpleMailer.class);
        DelayAlerterComponent component = new DelayAlerterComponent(properties, simpleMailer);
        Batch batch = new Batch();
        ResultCollector resultCollector = new ResultCollector("foo", "bar");
        Event event = new Event();
        Date now = new Date();
        event.setDate(new Date(now.getTime() - 10*24*3600*1000L));  //ten days ago
        event.setEventID("Data_Received");
        List<Event> events = new ArrayList<>();
        events.add(event);
        batch.setEventList(events);
        batch.setBatchID("B403485748392");
        batch.setRoundTripNumber(4);
        component.doWorkOnBatch(batch, resultCollector);
        verify(simpleMailer, never()).sendMail(anyList(), anyString(), anyString());
    }

    /**
     * Test that a mail is sent if the roundtrip is older than the maximum allowed.
     * @throws Exception
     */
    @Test
    public void testDoWorkOnBatchDelayed() throws Exception {
        Properties properties = new Properties();
        properties.setProperty(DelayAlerterConfigConstants.DELAY_ALERT_EMAIL_ADDRESSES, "foo@bar.com, bar@bar.com");
        properties.setProperty(DelayAlerterConfigConstants.DELAY_ALERT_DAYS, "20");
        final SimpleMailer simpleMailer = mock(SimpleMailer.class);
        DelayAlerterComponent component = new DelayAlerterComponent(properties, simpleMailer);
        Batch batch = new Batch();
        ResultCollector resultCollector = new ResultCollector("foo", "bar");
        Event event = new Event();
        Date now = new Date();
        event.setDate(new Date(now.getTime() - 30*24*3600*1000L));  //30 days ago
        event.setEventID("Data_Received");
        List<Event> events = new ArrayList<>();
        events.add(event);
        batch.setEventList(events);
        batch.setBatchID("B403485748392");
        batch.setRoundTripNumber(4);
        component.doWorkOnBatch(batch, resultCollector);
        verify(simpleMailer, times(1)).sendMail(anyList(), anyString(), anyString());
    }

    @BeforeTest
    public void setUp() {
        ServerSetup serverSetup = new ServerSetup(40026, ServerSetup.SMTP.getBindAddress(), ServerSetup.SMTP.getProtocol());
        this.greenMail = new GreenMail(serverSetup);
        greenMail.stop();
        greenMail.start();
    }

    @AfterTest
    public void tearDown() {
        greenMail.stop();
    }


    /**
     * Test that a mail is sent if the roundtrip is older than the maximum allowed. Uses GreenMail so
     * mail is actually "sent".
     * @throws Exception
     */
    @Test
    public void testDoWorkOnBatchDelayedRealMail() throws Exception {
        Properties properties = new Properties();
        properties.setProperty(DelayAlerterConfigConstants.DELAY_ALERT_EMAIL_ADDRESSES, "foo@bar.com, bar@bar.com");
        properties.setProperty(DelayAlerterConfigConstants.DELAY_ALERT_DAYS, "20");
        final SimpleMailer simpleMailer = new SimpleMailer("me@test.com", "localhost", "40026");
        DelayAlerterComponent component = new DelayAlerterComponent(properties, simpleMailer);
        Batch batch = new Batch();
        ResultCollector resultCollector = new ResultCollector("foo", "bar");
        Event event = new Event();
        Date now = new Date();
        event.setDate(new Date(now.getTime() - 30*24*3600*1000L));  //30 days ago
        event.setEventID("Data_Received");
        List<Event> events = new ArrayList<>();
        events.add(event);
        batch.setEventList(events);
        batch.setBatchID("B403485748392");
        batch.setRoundTripNumber(4);
        component.doWorkOnBatch(batch, resultCollector);
        String body = GreenMailUtil.getBody(greenMail.getReceivedMessages()[0]);
        assertTrue(body.contains(batch.getFullID()));
    }

    /**
     * Test that if the mail cannot be sent then the result is not preserved. This is important as otherwise
     * there will be no future mails either. But how will we know that it failed?
     * @throws Exception
     */
    @Test
    public void testDoWorkOnBatchMailFailed() throws Exception {
        Properties properties = new Properties();
        properties.setProperty(DelayAlerterConfigConstants.DELAY_ALERT_EMAIL_ADDRESSES, "foo@bar.com, bar@bar.com");
        properties.setProperty(DelayAlerterConfigConstants.DELAY_ALERT_DAYS, "20");
        final SimpleMailer simpleMailer = mock(SimpleMailer.class);
        DelayAlerterComponent component = new DelayAlerterComponent(properties, simpleMailer);
        Batch batch = new Batch();
        ResultCollector resultCollector = new ResultCollector("foo", "bar");
        Event event = new Event();
        Date now = new Date();
        event.setDate(new Date(now.getTime() - 30*24*3600*1000L));  //30 days ago
        event.setEventID("Data_Received");
        List<Event> events = new ArrayList<>();
        events.add(event);
        batch.setEventList(events);
        batch.setBatchID("B403485748392");
        batch.setRoundTripNumber(4);
        doThrow(new MessagingException("foobar")).when(simpleMailer).sendMail(anyList(), anyString(), anyString());
        try {
            component.doWorkOnBatch(batch, resultCollector);
            fail("Should have thrown an exception here.");
        } catch (Exception e) {
            //expected
        }
        verify(simpleMailer, times(1)).sendMail(anyList(), anyString(), anyString());
        assertFalse(resultCollector.isPreservable());
    }



}
