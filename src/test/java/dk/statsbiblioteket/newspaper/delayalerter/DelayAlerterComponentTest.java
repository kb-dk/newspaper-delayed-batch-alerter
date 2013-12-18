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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyList;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.assertTrue;

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
        final DelayAlertMailer delayAlertMailer = mock(DelayAlertMailer.class);
        DelayAlerterComponent component = new DelayAlerterComponent(properties, delayAlertMailer);
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
        verify(delayAlertMailer, never()).sendMail(anyList(), anyString(), anyString());
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
        final DelayAlertMailer delayAlertMailer = mock(DelayAlertMailer.class);
        DelayAlerterComponent component = new DelayAlerterComponent(properties, delayAlertMailer);
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
        verify(delayAlertMailer, times(1)).sendMail(anyList(), anyString(), anyString());
    }

    @BeforeTest
    public void setUp() {
        ServerSetup serverSetup = new ServerSetup(40026, ServerSetup.SMTP.getBindAddress(), ServerSetup.SMTP.getProtocol());
        this.greenMail = new GreenMail(serverSetup);
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
        final DelayAlertMailer delayAlertMailer = new DelayAlertMailer("me@test.com", "localhost", "40026");
        DelayAlerterComponent component = new DelayAlerterComponent(properties, delayAlertMailer);
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




}
