package org.transitime;

import junit.framework.TestCase;
import org.transitime.avl.ParticleSession;
import org.transitime.config.ConfigFileReader;
import org.transitime.config.ConfigValue;
import org.transitime.configData.CoreConfig;
import org.transitime.db.structs.AvlReport;
import org.transitime.modules.Module;

import java.math.BigInteger;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * Created by Fernando on 09/02/2017.
 */
public class TestParticle extends TestCase {
    protected void setUp() throws Exception {
        super.setUp();
    }
    String username = "fernando.campana@cti.espol.edu.ec";
    String password = ""; // Colocar clave del documento aqui

    protected void tearDown() throws Exception {
        super.tearDown();
    }
    public void testDateParseFromParticle() {
        Date dateTime1 = null;
        BigInteger expected = new BigInteger("1486115710031");
        try {
            dateTime1 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS").parse("2017-02-03T04:55:10.031Z");
        } catch (ParseException e) {
            e.printStackTrace();
            fail(e.toString());
        }
        assertEquals(expected.longValue(), dateTime1.getTime());
    }

    public void testSessionAuthentication() {
        try {
            ParticleSession session = new ParticleSession(username, password);
            session.myConnectIfNotConnected();
            assertTrue(true == session.connected());
        } catch (Exception e) {
            fail(e.toString());
        }

    }

    public void testParticleModule(){
        Module.start("org.transitime.avl.ParticleModule");
        //particle publish gps_ami 100,232
    }
}
