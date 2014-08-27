package dk.statsbiblioteket.newspaper.delayalerter;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;

import dk.statsbiblioteket.medieplatform.autonomous.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
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

    public static final String IT_EVENT = "IT_Event";
    public static final String ROUNDTRIP_APPROVED = "Roundtrip_Approved";
    public static Logger logger = LoggerFactory.getLogger(DelayAlerterComponentTestIT.class);

    long sleep = 10000L;
    int nsleeps = 0;
    int maxSleeps = 100;

    private DomsEventStorage domsEventClient;
    private String batchId = "321123";
    private int roundTrip = 12;
    private EventTrigger sboi;
    private String pathToProperties;
    private Properties properties;
    private GreenMail greenMail;
    private final String data_received = "Data_Received";


    @BeforeMethod()
    public void setUp() throws Exception {
        logger.debug("Doing setUp.");
        String genericProperties = System.getProperty("integration.test.newspaper.properties");
        File specificProperties = new File(new File(genericProperties).getParentFile(), "newspaper-delayed-batch-alerter-config/integration.test.newspaper.properties");
        logger.debug("Loading properties from " + specificProperties.getAbsolutePath());
        properties = new Properties();
        properties.load(new FileInputStream(specificProperties));
        pathToProperties = specificProperties.getAbsolutePath();
        DomsEventStorageFactory domsEventClientFactory = new DomsEventStorageFactory();
        domsEventClientFactory.setFedoraLocation(properties.getProperty(ConfigConstants.DOMS_URL));
        domsEventClientFactory.setUsername(properties.getProperty(ConfigConstants.DOMS_USERNAME));
        domsEventClientFactory.setPassword(properties.getProperty(ConfigConstants.DOMS_PASSWORD));
        domsEventClientFactory.setPidGeneratorLocation(properties.getProperty(ConfigConstants.DOMS_PIDGENERATOR_URL));
        logger.debug("Creating doms client");
        domsEventClient = domsEventClientFactory.createDomsEventStorage();
        String summaLocation = properties.getProperty(ConfigConstants.AUTONOMOUS_SBOI_URL);
        PremisManipulatorFactory factory = new PremisManipulatorFactory(new NewspaperIDFormatter(), PremisManipulatorFactory.TYPE);
        logger.debug("Creating sboi client");
        sboi = new SBOIEventIndex(summaLocation, factory, domsEventClient);
        logger.debug("Creating round-trip object (if necessary).");
        String pid = domsEventClient.createBatchRoundTrip(batchId, roundTrip);
        logger.debug("Created doms object {}.", pid);
        logger.debug("Resetting doms round-trip object state");
        domsEventClient.triggerWorkflowRestartFromFirstFailure(batchId, roundTrip, 3, 500, "Data_Received");
        logger.debug("Waiting for reindexing.");
        waitForEvent(batchId, roundTrip, data_received, false);
        ServerSetup serverSetup = new ServerSetup(40026, ServerSetup.SMTP.getBindAddress(), ServerSetup.SMTP.getProtocol());
        this.greenMail = new GreenMail(serverSetup);
        greenMail.stop();
        greenMail.start();
    }



    @AfterMethod()
    public void tearDown() throws Exception {
        logger.debug("Doing tearDown.");
        greenMail.stop();
    }


    /**
     * Test that we send an email on a delayed batch.
     * @throws IOException
     * @throws CommunicationException
     */
    @Test(groups = "integrationTest")
    public void testDoMainSendAlert() throws Exception {
        logger.debug("Entering testDoMainSendAlert");
        Date now = new Date();
        Date thirtyDaysAgo = new Date(now.getTime() - 30 * 24 * 3600 * 1000L);
        domsEventClient.addEventToBatch(batchId, roundTrip, "me", thirtyDaysAgo, "details", data_received, true);
        domsEventClient.addEventToBatch(batchId, roundTrip, "me", now, "details", IT_EVENT, true);
        logger.debug("Waiting for batch to be added to SBOI");
        waitForEvent(batchId, roundTrip, data_received, true);
        waitForEvent(batchId, roundTrip, IT_EVENT, true);
        waitForEvent(batchId, roundTrip, ROUNDTRIP_APPROVED, false);
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

    /**
     * Test that we don't send an email on an approved batch.
     * @throws IOException
     * @throws CommunicationException
     */
    @Test(groups = "integrationTest")
    public void testDoMainApproved() throws Exception {
        logger.debug("Entering testDoMainApproved.");
        Date now = new Date();
        Date thirtyDaysAgo = new Date(now.getTime() - 30 * 24 * 3600 * 1000L);
        domsEventClient.addEventToBatch(batchId, roundTrip, "me", thirtyDaysAgo, "details", data_received, true);
        domsEventClient.addEventToBatch(batchId, roundTrip, "me", now, "details", IT_EVENT, true);
        domsEventClient.addEventToBatch(batchId, roundTrip, "me", new Date(), "details", ROUNDTRIP_APPROVED, true);
        waitForEvent(batchId, roundTrip, data_received, true);
        waitForEvent(batchId, roundTrip, ROUNDTRIP_APPROVED, true);
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
    public void testDoMainNotTooOld() throws Exception {
        logger.debug("Entering testDoMainNotTooOld.");
        Date now = new Date();
        Date tenDaysAgo = new Date(now.getTime() - 10 * 24 * 3600 * 1000L);
        domsEventClient.addEventToBatch(batchId, roundTrip, "me", tenDaysAgo, "details", data_received, true);
        domsEventClient.addEventToBatch(batchId, roundTrip, "me", now, "details", IT_EVENT, true);
        waitForEvent(batchId, roundTrip, data_received, true);
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
     * Waits for a given event to be either present or absent in SBOI.
     * @param batchId
     * @param roundTrip
     * @param eventId
     * @param isPresent
     */
    private void waitForEvent(String batchId, Integer roundTrip, String eventId, boolean isPresent) throws InterruptedException {
        Batch localBatch = new Batch(batchId, roundTrip);
        String type;
        if (isPresent) {
            type = "created";
        } else {
            type = "deleted";
        }
        logger.debug("Waiting for event {} {} to be {}.", localBatch.getFullID(), eventId, type);
        nsleeps = 0;
        List<String> past = new ArrayList<>();
        past.add(eventId);
        List<String> pastFailed = new ArrayList<>();
        List<String> future = new ArrayList<>();
        List<Batch> batches = new ArrayList<>();
        batches.add(localBatch);
        boolean conditionSatisfied = false;
        while (!conditionSatisfied) {
            boolean eventPresent = false;
            Iterator<Batch> batchIterator = null;
            try {
                batchIterator = sboi.getTriggeredBatches(past, pastFailed, future,batches);
                while (batchIterator.hasNext()) {
                    final Batch batch = batchIterator.next();
                    if (batch.getBatchID().equals(batchId) && batch.getRoundTripNumber().equals(roundTrip)) {
                        eventPresent = true;
                    }
                }
                conditionSatisfied = (eventPresent && isPresent) || (!eventPresent && !isPresent);
                if (conditionSatisfied) {
                    logger.debug("Event {} {} {}.", localBatch.getFullID(), eventId,  type);
                    return;
                }
            } catch (CommunicationException e) {
                logger.debug(e.getMessage()); //Expected during reindexing.
            }
            Thread.sleep(sleep);
            nsleeps++;
            logger.debug("nsleeps = {}/{}.", nsleeps, maxSleeps);
            if (nsleeps > maxSleeps) throw new RuntimeException("SBOI not updated after " + maxSleeps*sleep/1000L + " seconds");
        }

    }

    private boolean batchContainsEvent(String batchId, Integer roundTrip, String eventId) throws Exception {
        Batch batch = null;
        try {
            batch = domsEventClient.getBatch(batchId,roundTrip);
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

}





