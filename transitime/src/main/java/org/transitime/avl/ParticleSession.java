package org.transitime.avl;

/**
 * Created by Mariana on 03/02/2017.
 */

import com.github.grantwest.sparkj.SparkCloudJsonObjects.SparkEvent;
import com.github.grantwest.sparkj.SparkEventStream;
import com.github.grantwest.sparkj.SparkSession;
import org.glassfish.jersey.media.sse.SseFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transitime.db.structs.AvlReport;
import org.transitime.utils.Time;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.function.Consumer;

/**
 * Created by Fernando Campaña on 26/01/2017.
 */

public class ParticleSession extends SparkSession {
    Collection<AvlReport> avlReportsReadIn;
    private static final Logger logger = LoggerFactory
            .getLogger(ParticleSession.class);

    public ParticleSession (String username, String password) {
        super(username, password);
    }

    public static void main(String[] args) throws ParseException {
        // Change username and password for your username and password
        String username = "";
        String password = "";
        //ParticleSession session = new ParticleSession(username, password);
        //Date dateTime1 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS").parse("2017-02-03T04:55:10.031Z");
        //System.out.print(dateTime1.getTime());
        /*(event) -> {
            Collection<AvlReport> avlReportsReadIn = new ArrayList<AvlReport>();
            String[] posi
        }*/
    }

    public void connectAndProcessData(ParticleModule module){
        this.connectIfNotConnected();
        this.eventStream((event) -> {
            avlReportsReadIn = new ArrayList<AvlReport>();
            String[] position = new String[2];
            //System.out.println(event.data);
            int i = 0;
            for (String retval: event.data.split(",")) {
                position[i] = retval;
                i++;
            }
            double lat = Double.parseDouble(position[0]);
            double lon = Double.parseDouble(position[1]);
            try {
                Date dateTime1 = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS").parse(event.getPublishedAt());
                AvlReport avlReport =
                        new AvlReport("1", dateTime1.getTime(), lat, lon, "transespol");
                //avlReport.setAssignment(blockId, AvlReport.AssignmentType.BLOCK_ID);
                logger.debug("AVL report from transespol feed: {}", avlReport);
                avlReportsReadIn.add(avlReport);
                module.processAvlReports(avlReportsReadIn);
            } catch (ParseException e) {
                e.printStackTrace();
            }

        });
    }
    private SparkEventStream eventStream(Consumer<SparkEvent> eventHandler) {
        Client client = ClientBuilder.newBuilder().register(SseFeature.class).build();
        WebTarget target = client.target(this.baseUrl).path("/v1/events/gps_ami").queryParam("access_token", this.getTokenKey());
        return new ParticleEventStream(target, eventHandler);
    }

    public ArrayList<AvlReport> returnAvlReports(){
        return (ArrayList) avlReportsReadIn;
    }

    public class ParticleEventStream extends SparkEventStream {

        protected ParticleEventStream(WebTarget target, Consumer<SparkEvent> eventHandler) {
            super(target, eventHandler);
        }
    }
}