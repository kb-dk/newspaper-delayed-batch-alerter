package dk.statsbiblioteket.newspaper.delayalerter;

import dk.statsbiblioteket.medieplatform.autonomous.AbstractRunnableComponent;
import dk.statsbiblioteket.medieplatform.autonomous.Batch;
import dk.statsbiblioteket.medieplatform.autonomous.ResultCollector;
import dk.statsbiblioteket.medieplatform.autonomous.RunnableComponent;

import java.util.Properties;

/**
 * This is an autonomous component which checks if a batch has been active for too long without reaching
 * a completed status. Specifically it looks for batches with a "Data_Received" event but without an
 * "Approved" event and for which the "Data_Received" is more than XX days old. If this is the case
 * then it sends a warning email to a list of addresses. As it is a runnable component, this will only
 * occur once per batch.
 */
public class DelayAlerterComponent extends AbstractRunnableComponent {


    protected DelayAlerterComponent(Properties properties) {
        super(properties);
    }

    @Override
    public String getEventID() {
        return "Warning_Email_Sent";
    }

    @Override
    public void doWorkOnBatch(Batch batch, ResultCollector resultCollector) throws Exception {
         throw new RuntimeException("Not yet implemented");
    }
}
