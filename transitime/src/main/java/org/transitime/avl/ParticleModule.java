package org.transitime.avl;

import com.github.grantwest.sparkj.SparkCloudJsonObjects.SparkEvent;
import com.github.grantwest.sparkj.SparkEventStream;
import com.github.grantwest.sparkj.SparkSession;
import org.glassfish.jersey.media.sse.SseFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transitime.config.StringConfigValue;
import org.transitime.configData.AgencyConfig;
import org.transitime.configData.AvlConfig;
import org.transitime.custom.vta.VtaAcsAvlModule;
import org.transitime.db.structs.AvlReport;
import org.transitime.logging.Markers;
import org.transitime.modules.Module;
import org.transitime.utils.IntervalTimer;
import org.transitime.utils.Time;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Consumer;

/**
 * Created by Fernando Campaña on 26/01/2017.
 */

public class ParticleModule extends AvlModule{
    ParticleSession session;

    private static final Logger logger = LoggerFactory
            .getLogger(ParticleModule.class);

    /********************** Member Functions **************************/

    public ParticleModule(String agencyId) {
        super(agencyId);
    }

    /**
     * Does all of the work for the class. Runs forever and reads in
     * AVL data from feed and processes it.
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run() {
        // Log that module successfully started
        logger.info("Started module {} for agencyId={}",
                getClass().getName(), getAgencyId());

        String username = "";
        String password = "";
        ParticleSession session = new ParticleSession(username, password);
        // Run forever
            try {
                // Process data
                session.connectAndProcessData(this);
                //Collection<AvlReport> avlReportsReadIn = processData();
                // Process all the reports read in
                //if (shouldProcessAvl.getValue())
                //    processAvlReports(avlReportsReadIn);
            } catch (Exception e) {
                logger.error("Error accessing AVL feed" );
            }
    }

    public static void main(String[] args) {
        // Change username and password for your username and password
        Module.start("org.transitime.avl.ParticleModule");
    }

}
