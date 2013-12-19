package dk.statsbiblioteket.newspaper.delayalerter;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import dk.statsbibliokeket.newspaper.batcheventFramework.SBOIClientImpl;
import dk.statsbibliokeket.newspaper.batcheventFramework.SBOIInterface;
import dk.statsbiblioteket.doms.central.connectors.EnhancedFedora;
import dk.statsbiblioteket.doms.central.connectors.EnhancedFedoraImpl;
import dk.statsbiblioteket.doms.webservices.authentication.Credentials;
import dk.statsbiblioteket.medieplatform.autonomous.*;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import javax.mail.internet.MimeMessage;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import static org.testng.Assert.*;

/**
 *
 */
public class DelayAlerterComponentTestIT {

    private DomsEventClient domsEventClient;
    private EnhancedFedora fedora;
    private String batchId = "123321";
    private int roundTrip = 12;
    private SBOIInterface sboi;
    private Properties properties;
    private GreenMail greenMail;

    @BeforeTest(groups = "integrationTest")
    public void setUp() throws Exception {
        String pathToProperties = System.getProperty("integration.test.newspaper.properties");
        System.out.println("Loading properties from " + pathToProperties);
        properties = new Properties();
        properties.load(new FileInputStream(pathToProperties));
        DomsEventClientFactory domsEventClientFactory = new DomsEventClientFactory();
        domsEventClientFactory.setFedoraLocation(properties.getProperty(ConfigConstants.DOMS_URL));
        domsEventClientFactory.setUsername(properties.getProperty(ConfigConstants.DOMS_USERNAME));
        domsEventClientFactory.setPassword(properties.getProperty(ConfigConstants.DOMS_PASSWORD));
        domsEventClientFactory.setPidGeneratorLocation(properties.getProperty(ConfigConstants.DOMS_PIDGENERATOR_URL));
        domsEventClient = domsEventClientFactory.createDomsEventClient();
        Credentials creds = new Credentials(properties.getProperty(ConfigConstants.DOMS_USERNAME), properties.getProperty(ConfigConstants.DOMS_PASSWORD));
        fedora =
                new EnhancedFedoraImpl(creds,
                        properties.getProperty(ConfigConstants.DOMS_URL).replaceFirst("/(objects)?/?$", ""),
                        properties.getProperty(ConfigConstants.DOMS_PIDGENERATOR_URL),
                        null);
        String summaLocation = properties.getProperty(ConfigConstants.AUTONOMOUS_SBOI_URL);
        PremisManipulatorFactory factory = new PremisManipulatorFactory(new NewspaperIDFormatter(), PremisManipulatorFactory.TYPE);
        sboi = new SBOIClientImpl(summaLocation, factory, domsEventClient);
        deleteBatch();
        ServerSetup serverSetup = new ServerSetup(40026, ServerSetup.SMTP.getBindAddress(), ServerSetup.SMTP.getProtocol());
        this.greenMail = new GreenMail(serverSetup);
        greenMail.stop();
        greenMail.start();
    }

    @AfterTest(groups = "integrationTest")
    public void tearDown() throws Exception {
        deleteBatch();
        greenMail.stop();
    }



    /**
     * Test that we send an email on a delayed batch.
     * @throws IOException
     * @throws CommunicationException
     */
    @Test(groups = "integrationTest")
    public void testDoMainSendAlert() throws IOException, CommunicationException {
        String pathToProperties = System.getProperty("integration.test.newspaper.properties");
        long sleep = 5000L;
        int nsleeps = 0;
        int maxSleeps = 30;
        Date now = new Date();
        Date thirtyDaysAgo = new Date(now.getTime() - 30 * 24 * 3600 * 1000L);
        domsEventClient.createBatchRoundTrip(batchId, roundTrip);
        String data_received = "Data_Received";
        try {
            domsEventClient.triggerWorkflowRestartFromFirstFailure(batchId, roundTrip, 10, 100L, data_received);
        } catch (NotFoundException e) {
            e.printStackTrace();
        }
        System.out.println("Waiting for SBOI to detect reset batch object");
        while(eventFoundInSBOI(batchId, roundTrip, data_received)){
            try {
                Thread.sleep(sleep);
                nsleeps++;
                if (nsleeps > maxSleeps) throw new RuntimeException("SBOI not updated after " + maxSleeps*sleep/1000L + " seconds");
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
        }
        domsEventClient.addEventToBatch(batchId, roundTrip, "me", thirtyDaysAgo, "details", data_received, true);
        System.out.println("Waiting for batch to be added to SBOI");
        while(!eventFoundInSBOI(batchId, roundTrip, data_received)){
            try {
                Thread.sleep(sleep);
                nsleeps++;
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
        }
        System.out.println("Found batch in SBOI after " + nsleeps*sleep/1000L + " seconds.");
        DelayAlerterComponent.doMain(new String[]{"-c", pathToProperties});
        MimeMessage[] receivedMessages = greenMail.getReceivedMessages();
        //There could be other batches that trigger emails so check that there is one from us
        boolean found = false;
        for (MimeMessage message: receivedMessages) {
            if (GreenMailUtil.getBody(message).contains(batchId)) {
                found = true;
            }
        }
        assertTrue(found);
        assertTrue(batchContainsEvent(batchId, roundTrip, DelayAlerterComponent.EMAIL_SENT_EVENT));
    }

    private boolean batchContainsEvent(String batchId, Integer roundTrip, String eventId) {
        Batch batch = null;
        try {
            batch = domsEventClient.getBatch(batchId,roundTrip);
        } catch (NotFoundException e) {
            return false;
        } catch (CommunicationException e) {
            return false;
        }
        for(Event event: batch.getEventList()) {
            if (event.getEventID().equals(eventId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Test that we don't send an email on an approved batch.
     * @throws IOException
     * @throws CommunicationException
     */
    @Test(groups = "integrationTest")
    public void testDoMainApproved() throws IOException, CommunicationException {
        String pathToProperties = System.getProperty("integration.test.newspaper.properties");
        long sleep = 5000L;
        int nsleeps = 0;
        int maxSleeps = 30;
        Date now = new Date();
        Date thirtyDaysAgo = new Date(now.getTime() - 30 * 24 * 3600 * 1000L);
        domsEventClient.createBatchRoundTrip(batchId, roundTrip);
        String data_received = "Data_Received";
        try {
            domsEventClient.triggerWorkflowRestartFromFirstFailure(batchId, roundTrip, 10, 100L, data_received);
        } catch (NotFoundException e) {
            e.printStackTrace();
        }
        System.out.println("Waiting for SBOI to detect reset batch object");
        while(eventFoundInSBOI(batchId, roundTrip, data_received)){
            try {
                Thread.sleep(sleep);
                nsleeps++;
                if (nsleeps > maxSleeps) throw new RuntimeException("SBOI not updated after " + maxSleeps*sleep/1000L + " seconds");
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
        }
        domsEventClient.addEventToBatch(batchId, roundTrip, "me", thirtyDaysAgo, "details", data_received, true);
        domsEventClient.addEventToBatch(batchId, roundTrip, "me", new Date(), "details", "Approved", true);
        System.out.println("Waiting for batch to be added to SBOI");
        while(!eventFoundInSBOI(batchId, roundTrip, data_received)){
            try {
                Thread.sleep(sleep);
                nsleeps++;
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
        }
        System.out.println("Found batch in SBOI after " + nsleeps*sleep/1000L + " seconds.");
        DelayAlerterComponent.doMain(new String[]{"-c", pathToProperties});
        MimeMessage[] receivedMessages = greenMail.getReceivedMessages();
        //There could be other batches that trigger emails so check that there isn't one from us
        boolean found = false;
        for (MimeMessage message: receivedMessages) {
            if (GreenMailUtil.getBody(message).contains(batchId)) {
                found = true;
            }
        }
        assertFalse(found);
        assertFalse(batchContainsEvent(batchId, roundTrip, DelayAlerterComponent.EMAIL_SENT_EVENT));
    }

    /**
     * Test that we don't send an email when a batch isn't old enough to be delayed.
     * @throws IOException
     * @throws CommunicationException
     */
    @Test(groups = "integrationTest")
    public void testDoMainNotTooOld() throws IOException, CommunicationException {
        String pathToProperties = System.getProperty("integration.test.newspaper.properties");
        long sleep = 5000L;
        int nsleeps = 0;
        int maxSleeps = 30;
        Date now = new Date();
        Date tenDaysAgo = new Date(now.getTime() - 10 * 24 * 3600 * 1000L);
        domsEventClient.createBatchRoundTrip(batchId, roundTrip);
        String data_received = "Data_Received";
        try {
            domsEventClient.triggerWorkflowRestartFromFirstFailure(batchId, roundTrip, 10, 100L, data_received);
        } catch (NotFoundException e) {
            e.printStackTrace();
        }
        System.out.println("Waiting for SBOI to detect reset batch object");
        while(eventFoundInSBOI(batchId, roundTrip, data_received)){
            try {
                Thread.sleep(sleep);
                nsleeps++;
                if (nsleeps > maxSleeps) throw new RuntimeException("SBOI not updated after " + maxSleeps*sleep/1000L + " seconds");
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
        }
        domsEventClient.addEventToBatch(batchId, roundTrip, "me", tenDaysAgo, "details", data_received, true);
        System.out.println("Waiting for batch to be added to SBOI");
        while(!eventFoundInSBOI(batchId, roundTrip, data_received)){
            try {
                Thread.sleep(sleep);
                nsleeps++;
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
        }
        System.out.println("Found batch in SBOI after " + nsleeps*sleep/1000L + " seconds.");
        DelayAlerterComponent.doMain(new String[]{"-c", pathToProperties});
        MimeMessage[] receivedMessages = greenMail.getReceivedMessages();
        //There could be other batches that trigger emails so check that there isn't one from us
        boolean found = false;
        for (MimeMessage message: receivedMessages) {
            if (GreenMailUtil.getBody(message).contains(batchId)) {
                found = true;
            }
        }
        assertFalse(found);
        assertFalse(batchContainsEvent(batchId, roundTrip, DelayAlerterComponent.EMAIL_SENT_EVENT));
    }

    private boolean eventFoundInSBOI(String batchId, Integer roundTrip, String eventId) throws CommunicationException {
        Batch batch;
        try {
            batch = sboi.getBatch(batchId, roundTrip);
        } catch (NotFoundException e) {
            return false;
        }
        for (Event event: batch.getEventList() ) {
            if (event.getEventID().equals(eventId)) {
                return true;
            }
        }
        return false;
    }

    private boolean batchExistsInSBOI(String batchId, Integer roundTrip) throws CommunicationException {
        try {
            sboi.getBatch(batchId, roundTrip);
        }  catch (NotFoundException e) {
            return false;
        }
        return true;
    }

    private void deleteBatch() throws Exception {
        Batch batch = null;
        try {
            batch = domsEventClient.getBatch(batchId, roundTrip);
        } catch (Exception e) {
            return;
        }
        List<String> pids = fedora.findObjectFromDCIdentifier(batch.getFullID());
        if (!pids.isEmpty()) {
            fedora.deleteObject(pids.get(0), "Deleted in test.");
        }
    }
}
