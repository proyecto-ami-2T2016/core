/*
 * This file is part of Transitime.org
 * 
 * Transitime.org is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL) as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * Transitime.org is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Transitime.org .  If not, see <http://www.gnu.org/licenses/>.
 */
package org.transitime.gui;

import java.io.File;
import java.util.List;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transitime.applications.Core;
import org.transitime.applications.GtfsFileProcessor;
import org.transitime.config.ConfigFileReader;
import org.transitime.configData.CoreConfig;
import org.transitime.db.webstructs.ApiKey;
import org.transitime.db.webstructs.ApiKeyManager;
import org.transitime.db.webstructs.WebAgency;
import org.transitime.modules.Module;
import org.transitime.quickstart.resource.ExtractResource;
import org.transitime.quickstart.resource.QuickStartException;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.ContextInitializer;

/**
 * <h1>transitimeQuickStart
 * <h1>
 * 
 * This is the main repository of code used by the transitimeQuickStart. methods
 * here are called mainly via the input panel when the next button is selected
 * 
 * 
 * @author Brendan Egan
 * 
 *
 */
public class TransitimeQuickStart implements Runnable {
	/**
	 * @param ApiKey
	 *            this hold the apikey value generated by the createapikey
	 *            method. it used in the outputpanel to show the correct url.
	 * @parem webserver this is the server used to host the webapp and api on.
	 * @parem apiapp used when loading the api.war onto the webserver
	 * @parem webapp used when loading the web.war onto the webserver
	 */
	private static final Logger logger = LoggerFactory.getLogger(TransitimeQuickStart.class);
	private ApiKey apiKey = null;
	static Server webserver = new Server(8000);
	WebAppContext apiapp = null;
	WebAppContext webapp = null;

	/**
	 * creates and starts the input panel of the gui
	 */
	public static void main(String args[]) {

		InputPanel windowinput = new InputPanel();
		windowinput.InputPanelstart();

	}

	public void extractResources() throws QuickStartException {
		try {
			ExtractResource.extractResourceNIO(TransitimeQuickStart.class.getClassLoader(), "hibernate.cfg.xml");
			ExtractResource.extractResourceNIO(TransitimeQuickStart.class.getClassLoader(), "transitime.properties");
			ExtractResource.extractResourceNIO(TransitimeQuickStart.class.getClassLoader(), "api.war");
			ExtractResource.extractResourceNIO(TransitimeQuickStart.class.getClassLoader(), "web.war");
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			throw new QuickStartException("extractResources failed", e);
		}
	}

	public void startGtfsFileProcessor(String gtfsZipFileName) throws QuickStartException {
		/**
		 * called from input panel Start GtfsFileProcessor using the gtfs file
		 * selected from the input panel
		 */
		try {
			String configFilePath = null;

			// String
			// configFilePath="..//transitimeQuickStart//src//main//resources//transiTimeconfig.xml";
			String gtfsFilePath;
			if (gtfsZipFileName == null) {
				File gtfsFile = ExtractResource.extractResourceFile(this.getClass().getClassLoader(), "Intercity.zip");
				gtfsFilePath = gtfsFile.getPath();
				gtfsZipFileName = gtfsFilePath;
			} else {
				gtfsFilePath = gtfsZipFileName;
			}
			String notes = null;
			String gtfsUrl = null;
			// String gtfsZipFileName = gtfsFilePath;
			String unzipSubdirectory = null;
			String gtfsDirectoryName = null;
			String supplementDir = null;
			String regexReplaceListFileName = null;
			double pathOffsetDistance = 0.0;
			double maxStopToPathDistance = 60.0;
			double maxDistanceForEliminatingVertices = 3.0;
			int defaultWaitTimeAtStopMsec = 10000;
			double maxSpeedKph = 97;
			double maxTravelTimeSegmentLength = 1000.0;
			int configRev = -1;
			boolean shouldStoreNewRevs = true;
			boolean trimPathBeforeFirstStopOfTrip = false;

			GtfsFileProcessor processor = new GtfsFileProcessor(configFilePath, notes, gtfsUrl, gtfsZipFileName,
					unzipSubdirectory, gtfsDirectoryName, supplementDir, regexReplaceListFileName, pathOffsetDistance,
					maxStopToPathDistance, maxDistanceForEliminatingVertices, defaultWaitTimeAtStopMsec, maxSpeedKph,
					maxTravelTimeSegmentLength, configRev, shouldStoreNewRevs, trimPathBeforeFirstStopOfTrip);
			processor.process();
			logger.info("startGtfsFileProcessor successful");
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			// TODO send strack trace in all quickStart exceptions
			throw new QuickStartException("GtfsFileProcessor failed to start", e);
		}
	}

	public void createApiKey() throws QuickStartException {
		/**
		 * Creates api key need for session, is saved in apikey class variable
		 * 
		 */
		try {

			ConfigFileReader.processConfig();

			String name = "Joe bloggs";
			String url = "http://www.transitime.org";
			String email = "foo@default.com";
			String phone = "123456789";
			String description = "Foo";
			ApiKeyManager manager = ApiKeyManager.getInstance();
			apiKey = manager.generateApiKey(name, url, email, phone, description);

			List<ApiKey> keys = manager.getApiKeys();
			for (ApiKey key : keys) {
				logger.info(key.getKey());
			}

			logger.info("createApiKey successful");
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			throw new QuickStartException("create ApiKey failed to start", e);
		}

	}

	public void startCore(String realtimefeedURL, String loglocation) throws QuickStartException {
		/**
		 * Starts the main core of transitime using the entered realtime feed or
		 * default if nothing is entered, feed must be entered correctly as it
		 * will only use default if nothing has been passed through for the
		 * realtimefeedUrl, if nothing is entered for the loglocation will use
		 * where the transitimeQuickStart was called
		 */
		try {
			ConfigFileReader.processConfig();
			// TODO set the agency id by getting it from the gtfs file.
			String agencyid = System.getProperties().getProperty("transitime.core.agencyId");
			System.getProperties().setProperty("transitime.core.configRevStr", "0");
			// uses default if nothing entered

			// only set the paramater for realtimeURLfeed if specified by user
			if (!realtimefeedURL.equals("")) {
				System.getProperties().setProperty("transitime.avl.url", realtimefeedURL);
			}

			// Initialize the core now
			Core.createCore();
			List<String> optionalModuleNames = CoreConfig.getOptionalModules();
			if (optionalModuleNames.size() > 0)
				logger.info("Starting up optional modules specified via "
						+ "transitime.modules.optionalModulesList param:");
			else
				logger.info("No optional modules to start up.");
			for (String moduleName : optionalModuleNames) {
				logger.info("Starting up optional module " + moduleName);
				Module.start(moduleName);
			}
			// start servers
			Core.startRmiServers(agencyid);
			logger.info("startCore successful");
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			throw new QuickStartException("Core failed to start", e);
		}
	}

	public void startDatabase() throws QuickStartException {
		/**
		 * Starts database for hsql. this was needed for the way the hsql was
		 * configured in the hsql_hibernate file
		 */
		try {
			// TODO deleted unneeded objects
			String dbPath = "mem:test;sql.enforce_strict_size=true";

			String serverProps;
			String url;
			String user = "sa";
			String password = "";
			org.hsqldb.server.Server serverdb;
			boolean isNetwork = true;
			boolean isHTTP = false; // Set false to test HSQL protocol, true to
									// test
									// HTTP, in which case you can use
									// isUseTestServlet to target either HSQL's
									// webserver, or the Servlet server-mode
			boolean isServlet = false;

			serverdb = new org.hsqldb.server.Server();
			serverdb.setDatabaseName(0, "test");
			serverdb.setDatabasePath(0, dbPath);
			serverdb.setLogWriter(null);
			serverdb.setErrWriter(null);
			serverdb.start();
			logger.info("startDatabase successful");
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			throw new QuickStartException("Start Database failed to start", e);
		}
	}

	public void addApi() throws QuickStartException {
		/**
		 * adds api to server using the WebAppContext
		 */
		try {

			apiapp = new WebAppContext();
			apiapp.setContextPath("/api");
			File warFile = new File("api.war");
			apiapp.setWar(warFile.getPath());

			// location to go to=
			// http://127.0.0.1:8080/api/v1/key/1727f2a/agency/02/command/routes?format=json
			Configuration.ClassList classlist = Configuration.ClassList.setServerDefault(webserver);
			classlist.addBefore("org.eclipse.jetty.webapp.JettyWebXmlConfiguration",
					"org.eclipse.jetty.annotations.AnnotationConfiguration");
			apiapp.setAttribute("org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern",
					".*/[^/]*servlet-api-[^/]*\\.jar$|.*/javax.servlet.jsp.jstl-.*\\.jar$|.*/[^/]*taglibs.*\\.jar$");
			// Set the path to the override descriptor, based on your
			// $(jetty.home)
			// directory
			apiapp.setOverrideDescriptor("override-web.xml");

			// server.start();
			// server.join();
			logger.info("add api successful");
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			throw new QuickStartException("add api failed to start", e);
		}
	}

	public void addWebapp() throws QuickStartException {
		try {
			// Server server = new Server(8081);

			webapp = new WebAppContext();
			webapp.setContextPath("/web");
			File warFile = new File("web.war");

			webapp.setWar(warFile.getPath());

			// location to go to=
			// http://127.0.0.1:8080/api/v1/key/1727f2a/agency/02/command/routes?format=json

			webapp.setAttribute("org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern",
					".*/[^/]*servlet-api-[^/]*\\.jar$|.*/javax.servlet.jsp.jstl-.*\\.jar$|.*/[^/]*taglibs.*\\.jar$");
			webapp.setOverrideDescriptor("override-web.xml");

			System.setProperty("transitime.apikey", apiKey.getKey());
			// server.join();
			logger.info("add Webapp successful");
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			throw new QuickStartException("Webapp failed to start", e);
		}
	}

	public void startJetty(boolean startwebapp) throws QuickStartException {
		/**
		 * Starts the server for webapp and/or api
		 */
		try {
			// handler Collection is used to allow 2 different war files(api.war
			// and
			// web.war) to be used
			HandlerCollection handlerCollection = new HandlerCollection();
			if (startwebapp == true) {
				handlerCollection.setHandlers(new Handler[] { apiapp, webapp });
			} else {
				handlerCollection.setHandlers(new Handler[] { apiapp });
			}
			webserver.setHandler(handlerCollection);
			webserver.start();
			/*
			 * apiapp.start(); webapp.start();
			 */
			logger.info("started Jetty successful");
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			throw new QuickStartException("Jetty server failed to start", e);
		}
	}

	public void webAgency() throws QuickStartException {
		/**
		 * used for creating webapp
		 */
		try {
			// TODO have this set up right,right values for variables
			String agencyId = System.getProperties().getProperty("transitime.core.agencyId");
			String hostName = "127.0.0.1";
			boolean active = true;
			String dbName = "02";
			String dbType = "hsql";
			String dbHost = "http://127.0.0.1:8080/";
			String dbUserName = "sa";
			String dbPassword = "";
			// Name of database where to store the WebAgency object
			String webAgencyDbName = "test";

			// Create the WebAgency object
			WebAgency webAgency = new WebAgency(agencyId, hostName, active, dbName, dbType, dbHost, dbUserName,
					dbPassword);
			System.out.println("Storing " + webAgency);

			// Store the WebAgency
			webAgency.store(webAgencyDbName);

		} catch (Exception e) {
			e.printStackTrace();
			throw new QuickStartException("web agency failed to start", e);
		}

	}

	public void resetLogback() throws QuickStartException {
		try {
			LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
			ContextInitializer ci = new ContextInitializer(lc);
			lc.reset();
			ci.autoConfig();
		} catch (Exception e) {
			throw new QuickStartException("resetLogback failed to start", e);
		}

	}

	public ApiKey getApiKey() {
		return apiKey;
	}

	public void setApiKey(ApiKey apiKey) {
		this.apiKey = apiKey;
	}

	@Override
	public void run() {
		// TODO Auto-generated method stub

	}
}
