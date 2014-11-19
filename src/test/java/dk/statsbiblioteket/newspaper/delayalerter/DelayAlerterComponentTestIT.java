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
    protected static final String WARNING_EMAIL_SENT = "Warning_Email_Sent";
    protected static final String MANUALLY_STOPPED = "Manually_stopped";
    private final String DATA_RECEIVED = "Data_Received";


    public static Logger logger = LoggerFactory.getLogger(DelayAlerterComponentTestIT.class);

    long sleep = 10000L;
    int nsleeps = 0;
    int maxSleeps = 100;

    private NewspaperDomsEventStorage domsEventClient;
    private String batchId = "321123";
    private int roundTrip = 12;
    private EventTrigger<Batch> sboi;
    private String pathToProperties;
    private Properties properties;
    private GreenMail greenMail;
    private String pid;

    @BeforeMethod(groups = "integrationTest")
    public void setUp() throws Exception {
        logger.debug("Doing setUp.");
        String genericProperties = System.getProperty("integration.test.newspaper.properties");
        File specificProperties = new File(new File(genericProperties).getParentFile(), "newspaper-delayed-batch-alerter-config/integration.test.newspaper.properties");
        logger.debug("Loading properties from " + specificProperties.getAbsolutePath());
        properties = new Properties();
        properties.load(new FileInputStream(specificProperties));
        pathToProperties = specificProperties.getAbsolutePath();
        NewspaperDomsEventStorageFactory domsEventClientFactory = new NewspaperDomsEventStorageFactory();
        domsEventClientFactory.setFedoraLocation(properties.getProperty(ConfigConstants.DOMS_URL));
        domsEventClientFactory.setUsername(properties.getProperty(ConfigConstants.DOMS_USERNAME));
        domsEventClientFactory.setPassword(properties.getProperty(ConfigConstants.DOMS_PASSWORD));
        domsEventClientFactory.setPidGeneratorLocation(properties.getProperty(ConfigConstants.DOMS_PIDGENERATOR_URL));
        logger.debug("Creating doms client");
        domsEventClient = domsEventClientFactory.createDomsEventStorage();
        String summaLocation = properties.getProperty(ConfigConstants.AUTONOMOUS_SBOI_URL);
        PremisManipulatorFactory<Batch> factory = new PremisManipulatorFactory<>( PremisManipulatorFactory.TYPE,new BatchItemFactory());
        logger.debug("Creating sboi client");
        sboi = new NewspaperSBOIEventStorage(summaLocation, factory, domsEventClient, Integer.parseInt(properties.getProperty(ConfigConstants.SBOI_PAGESIZE,"100")));
        logger.debug("Creating round-trip object (if necessary).");
        pid = domsEventClient.createBatchRoundTrip(Batch.formatFullID(batchId, roundTrip));
        logger.debug("Created doms object {}.", pid);
        logger.debug("Resetting doms round-trip object state");
        domsEventClient.triggerWorkflowRestartFromFirstFailure(new Batch(batchId, roundTrip), 3, 500, "Data_Received");
        logger.debug("Waiting for reindexing.");
        waitForEvent(batchId, roundTrip, DATA_RECEIVED, false);
        ServerSetup serverSetup = new ServerSetup(40026, ServerSetup.SMTP.getBindAddress(), ServerSetup.SMTP.getProtocol());
        this.greenMail = new GreenMail(serverSetup);
        greenMail.stop();
        greenMail.start();
    }



    @AfterMethod(groups = "integrationTest")
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
        domsEventClient.addEventToItem(new Batch(batchId, roundTrip), "me", thirtyDaysAgo, "details", DATA_RECEIVED, true);
        domsEventClient.addEventToItem(new Batch(batchId, roundTrip), "me", now, "details", IT_EVENT, true);
        logger.debug("Waiting for batch to be added to SBOI");
        waitForBatchIsInSboi(batchId,
                roundTrip,
                DATA_RECEIVED + "," + IT_EVENT, ROUNDTRIP_APPROVED + "," + WARNING_EMAIL_SENT + "," + MANUALLY_STOPPED);
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

    private void waitForBatchIsInSboi(String batchId, int roundTrip, String pastEvents, String futureEvents) throws InterruptedException {
        Batch localBatch = new Batch(batchId, roundTrip);
        nsleeps = 0;
        while (true) {
            boolean eventPresent = false;
            Iterator<Batch> batchIterator;
            try {
                EventTrigger.Query<Batch> query = new EventTrigger.Query<>();
                query.getPastSuccessfulEvents().addAll(Arrays.asList(pastEvents.trim().split(",")));
                query.getFutureEvents().addAll(Arrays.asList(futureEvents.trim().split(",")));
                query.getItems().add(localBatch);
                batchIterator = sboi.getTriggeredItems(query);
                while (batchIterator.hasNext()) {
                    final Batch batch = batchIterator.next();
                    if (batch.getBatchID().equals(batchId) && batch.getRoundTripNumber().equals(roundTrip)) {
                        eventPresent = true;
                    }
                }

                if (eventPresent) {
                    return;
                }
            } catch (CommunicationException e) {
                logger.debug(e.getMessage()); //Expected during reindexing.
            }
            Thread.sleep(sleep);
            nsleeps++;
            logger.debug("nsleeps = {}/{}.", nsleeps, maxSleeps);
            if (nsleeps > maxSleeps)
                throw new RuntimeException("SBOI not updated after " + maxSleeps * sleep / 1000L + " seconds");
        }
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
        domsEventClient.addEventToItem(new Batch(batchId, roundTrip), "me", thirtyDaysAgo, "details", DATA_RECEIVED, true);
        domsEventClient.addEventToItem(new Batch(batchId, roundTrip), "me", now, "details", IT_EVENT, true);
        domsEventClient.addEventToItem(new Batch(batchId, roundTrip), "me", new Date(), "details", ROUNDTRIP_APPROVED, true);
        waitForBatchIsInSboi(batchId,
                roundTrip,
                DATA_RECEIVED + "," + IT_EVENT + "," + ROUNDTRIP_APPROVED, WARNING_EMAIL_SENT + "," + MANUALLY_STOPPED);
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
        domsEventClient.addEventToItem(new Batch(batchId, roundTrip), "me", tenDaysAgo, "details", DATA_RECEIVED, true);
        domsEventClient.addEventToItem(new Batch(batchId, roundTrip), "me", now, "details", IT_EVENT, true);
        waitForBatchIsInSboi(batchId,
                roundTrip,
                DATA_RECEIVED + "," + IT_EVENT, ROUNDTRIP_APPROVED+","+WARNING_EMAIL_SENT + "," + MANUALLY_STOPPED);
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

        while (true) {
            boolean eventPresent = false;
            Iterator<Batch> batchIterator = null;
            try {
                EventTrigger.Query<Batch> query = new EventTrigger.Query();
                query.getPastSuccessfulEvents().add(eventId);
                query.getItems().add(localBatch);
                batchIterator = sboi.getTriggeredItems(query);
                while (batchIterator.hasNext()) {
                    final Batch batch = batchIterator.next();
                    if (batch.getBatchID().equals(batchId) && batch.getRoundTripNumber().equals(roundTrip)) {
                        eventPresent = true;
                    }
                }
                boolean conditionSatisfied = (eventPresent && isPresent) || (!eventPresent && !isPresent);
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
            batch = domsEventClient.getItemFromFullID(Batch.formatFullID(batchId,roundTrip));
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





