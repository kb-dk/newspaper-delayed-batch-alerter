package dk.statsbiblioteket.newspaper.delayalerter;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import dk.statsbibliokeket.newspaper.batcheventFramework.SBOIClientImpl;
import dk.statsbibliokeket.newspaper.batcheventFramework.SBOIInterface;
import dk.statsbiblioteket.doms.central.connectors.BackendInvalidCredsException;
import dk.statsbiblioteket.doms.central.connectors.BackendInvalidResourceException;
import dk.statsbiblioteket.doms.central.connectors.BackendMethodFailedException;
import dk.statsbiblioteket.doms.central.connectors.EnhancedFedora;
import dk.statsbiblioteket.doms.central.connectors.EnhancedFedoraImpl;
import dk.statsbiblioteket.doms.webservices.authentication.Credentials;
import dk.statsbiblioteket.medieplatform.autonomous.*;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import javax.mail.internet.MimeMessage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

import static org.testng.Assert.*;

/**
 *
 */
public class DelayAlerterComponentTestIT {

    long sleep = 10000L;
    int nsleeps = 0;
    int maxSleeps = 100;

    private DomsEventClient domsEventClient;
    private EnhancedFedora fedora;
    private String batchId = "321123";
    private int roundTrip = 12;
    private SBOIInterface sboi;
    private String pathToProperties;
    private Properties properties;
    private GreenMail greenMail;
    private final String data_received = "Data_Received";


    @BeforeMethod(alwaysRun = true)
    public void setUp() throws Exception {
        System.out.println("Doing setUp.");
        String genericProperties = System.getProperty("integration.test.newspaper.properties");
        File specificProperties = new File(new File(genericProperties).getParentFile(), "newspaper-delayed-batch-alerter-config/integration.test.newspaper.properties");
        System.out.println("Loading properties from " + specificProperties.getAbsolutePath());
        properties = new Properties();
        properties.load(new FileInputStream(specificProperties));
        pathToProperties = specificProperties.getAbsolutePath();
        DomsEventClientFactory domsEventClientFactory = new DomsEventClientFactory();
        domsEventClientFactory.setFedoraLocation(properties.getProperty(ConfigConstants.DOMS_URL));
        domsEventClientFactory.setUsername(properties.getProperty(ConfigConstants.DOMS_USERNAME));
        domsEventClientFactory.setPassword(properties.getProperty(ConfigConstants.DOMS_PASSWORD));
        domsEventClientFactory.setPidGeneratorLocation(properties.getProperty(ConfigConstants.DOMS_PIDGENERATOR_URL));
        System.out.println("Creating doms client");
        domsEventClient = domsEventClientFactory.createDomsEventClient();
        Credentials creds = new Credentials(properties.getProperty(ConfigConstants.DOMS_USERNAME), properties.getProperty(ConfigConstants.DOMS_PASSWORD));
        fedora =
                new EnhancedFedoraImpl(creds,
                        properties.getProperty(ConfigConstants.DOMS_URL).replaceFirst("/(objects)?/?$", ""),
                        properties.getProperty(ConfigConstants.DOMS_PIDGENERATOR_URL),
                        null);
        String summaLocation = properties.getProperty(ConfigConstants.AUTONOMOUS_SBOI_URL);
        PremisManipulatorFactory factory = new PremisManipulatorFactory(new NewspaperIDFormatter(), PremisManipulatorFactory.TYPE);
        System.out.println("Creating sboi client");
        sboi = new SBOIClientImpl(summaLocation, factory, domsEventClient);
        System.out.println("Creating round-trip object (if necessary).");
        domsEventClient.createBatchRoundTrip(batchId, roundTrip);
        System.out.println("Resetting doms round-trip object state");
        domsEventClient.triggerWorkflowRestartFromFirstFailure(batchId, roundTrip, 3, 500, "Data_Received");
        System.out.println("Waiting for reindexing.");
        waitForReindex();
        ServerSetup serverSetup = new ServerSetup(40026, ServerSetup.SMTP.getBindAddress(), ServerSetup.SMTP.getProtocol());
        this.greenMail = new GreenMail(serverSetup);
        greenMail.stop();
        greenMail.start();
    }

    private void waitForReindex() throws InterruptedException {
        nsleeps = 0;
        List<String> past = new ArrayList<>();
        past.add("Data_Received");
        List<String> pastFailed = new ArrayList<>();
        List<String> future = new ArrayList<>();
        boolean b = true;
        while (b) {
            Iterator<Batch> batchIterator = null;
            try {
                batchIterator = sboi.getBatches(false, past, pastFailed, future);
                while (batchIterator.hasNext()) {
                    if (batchIterator.next().getBatchID().equals(batchId)) {
                        b = false;
                    }
                }
            } catch (CommunicationException e) {
                e.printStackTrace();
            }
            Thread.sleep(sleep);
            nsleeps++;
            System.out.println("nsleeps = " + nsleeps + "/" + maxSleeps);
            if (nsleeps > maxSleeps) throw new RuntimeException("SBOI not updated after " + maxSleeps*sleep/1000L + " seconds");
        }
    }


    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        System.out.println("Doing tearDown.");
        greenMail.stop();
    }


    /**
     * Test that we send an email on a delayed batch.
     * @throws IOException
     * @throws CommunicationException
     */
    @Test(groups = "integrationTest")
    public void testDoMainSendAlert() throws IOException, CommunicationException {
        System.out.println("Starting testDoMainSendAlert()");
        Date now = new Date();
        Date thirtyDaysAgo = new Date(now.getTime() - 30 * 24 * 3600 * 1000L);
        domsEventClient.addEventToBatch(batchId, roundTrip, "me", thirtyDaysAgo, "details", data_received, true);
        System.out.println("Waiting for batch to be added to SBOI");
        nsleeps = 0;
        while(!eventFoundInSBOI(batchId, roundTrip, data_received)){
            try {
                Thread.sleep(sleep);
                nsleeps++;
                if (nsleeps > maxSleeps) throw new RuntimeException("SBOI not updated after " + maxSleeps*sleep/1000L + " seconds");
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
        }
        System.out.println("Found event in SBOI after " + nsleeps*sleep/1000L + " seconds.");
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
    //@Test(groups = "integrationTest")
    public void testDoMainApproved() throws IOException, CommunicationException {
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
        nsleeps = 0;
        while(!eventFoundInSBOI(batchId, roundTrip, data_received)){
            try {
                Thread.sleep(sleep);
                nsleeps++;
                if (nsleeps > maxSleeps) throw new RuntimeException("SBOI not updated after " + maxSleeps*sleep/1000L + " seconds");
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
    //@Test(groups = "integrationTest")
    public void testDoMainNotTooOld() throws IOException, CommunicationException {
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
        nsleeps = 0;
        while(!eventFoundInSBOI(batchId, roundTrip, data_received)){
            try {
                Thread.sleep(sleep);
                nsleeps++;
                if (nsleeps > maxSleeps) throw new RuntimeException("SBOI not updated after " + maxSleeps*sleep/1000L + " seconds");
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
            batch = sboi.getBatch(batchId, roundTrip, false);
        } catch (NotFoundException | CommunicationException e) {
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
            sboi.getBatch(batchId, roundTrip, false);
        }  catch (NotFoundException e) {
            return false;
        }
        return true;
    }


}
