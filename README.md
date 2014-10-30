newspaper-delayed-batch-alerter
===============================

When data from a batch roundtrip is receieved, it is expected that it will be approved within a fixed period of time, for
example 20 days. This application is an autonomous component which sends a warning email if a longer period elapses
between data being received and the batch being approved.

## Configuration

In addition to the standard configuration parameters for autonomous components, this component requires the following
properties:

    #The number of days after receiving data at which to send an email alert
    delay.alert.days=

    #A comma-separated list of addresses to which to send emails
    delay.alert.email.addresses=

    #The smtp server to use for sending emails
    delay.alert.smtp.host=

    #The smtp port of the host
    delay.alert.smtp.port=


    #The "from" address to use in sending emails
    delay.alert.email.from.address=

In addition, the event-properties must have the following values:

    autonomous.pastSuccessfulEvents=Data_Received
    autonomous.oldEvents=
    autonomous.futureEvents=Roundtrip_Approved,Warning_Email_Sent


