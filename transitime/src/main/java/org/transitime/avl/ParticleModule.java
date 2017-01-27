package org.transitime.avl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transitime.config.StringConfigValue;
import org.transitime.db.structs.AvlReport;
import org.transitime.feed.gtfsRt.GtfsRtVehiclePositionsReader;
import org.transitime.modules.Module;
import org.transitime.utils.Time;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Created by Fernando Campaña on 26/01/2017.
 */
public class ParticleModule extends PollUrlAvlModule {
    // Parameter that specifies URL of the Particle feed.
    private static StringConfigValue particleFeedUrl =
            new StringConfigValue("transitime.avl.particle.url",
                    "https://api.particle.io/v1/events/gps_ami/?access_token=",
                    "The URL of the NextBus feed to use.");

    private static final Logger logger=
            LoggerFactory.getLogger(AvlExecutor.class);

    /********************** Member Functions **************************/

    /**
     * @param projectId
     */
    public ParticleModule(String projectId) {
        super(projectId);

        // Particle is already binary so don't want to get compressed
        // version since that would just be a waste.
        useCompression = false;
    }

    @Override
    protected String getUrl() {
        // Determine the URL to use.
        String url = getParticleFeedUrl();
        String access_token = "";
        return url + access_token;
    }

    private static String getParticleFeedUrl() {
        return particleFeedUrl.getValue();
    }

    @Override
    /**
     * Gets AVL data from input stream. Each line has data for a vehicle.
     */
    protected Collection<AvlReport> processData(InputStream in) throws Exception {
        BufferedReader buf =
                new BufferedReader(new InputStreamReader(in,
                        StandardCharsets.UTF_8));

        // The return value for the method
        Collection<AvlReport> avlReportsReadIn = new ArrayList<AvlReport>();
        logger.info("Getting message");
        String line;
        while ((line = buf.readLine()) != null) {
            logger.info("{}", line);
            /*String components[] = line.split(",");
            String vehicleId = components[0];
            @SuppressWarnings("unused")
            String route = components[1];
            String blockId = components[3];
            Double lat = Double.parseDouble(components[9]);
            Double lon = Double.parseDouble(components[10]);
            long epochSecs = Long.parseLong(components[11]);

            AvlReport avlReport =
                    new AvlReport(vehicleId, epochSecs * Time.MS_PER_SEC, lat,
                            lon, "VTA_ACS");
            avlReport.setAssignment(blockId, AvlReport.AssignmentType.BLOCK_ID);

            avlReportsReadIn.add(avlReport);*/
        }

        return avlReportsReadIn;
    }

    /**
     * Just for debugging
     */
    public static void main(String[] args) {
        // Create a GtfsRealtimeModule for testing
        Module.start("org.transitime.avl.ParticleModule");
    }
}
