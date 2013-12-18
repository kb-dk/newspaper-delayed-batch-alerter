#!/bin/sh
#
# This is a sample script which could be used to start this component in a cron-job.
# The "conf" directory is placed in the classpath because it is from here that the
# logback configuration is loaded. Alternatively, it's path can be explicitly specified with
# -Dlogback.configurationFile=$SCRIPT_DIR/../conf/logback.xml
#

SCRIPT_DIR=$(dirname $(readlink -f $0))

java -classpath "$SCRIPT_DIR/../conf:$SCRIPT_DIR/../lib/*" \
 dk.statsbiblioteket.newspaper.delayalerter.DelayAlerterComponent \
 $SCRIPT_DIR/../conf/config.properties
