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

package org.transitime.core.predAccuracy;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transitime.applications.Core;
import org.transitime.config.StringConfigValue;
import org.transitime.db.structs.StopPath;
import org.transitime.db.structs.Trip;
import org.transitime.modules.Module;
import org.transitime.utils.Time;

/**
 * Reads in external prediction data from MBTA feed and stores in memory. Then
 * when arrivals/departures occur the prediction accuracy can be determined and
 * stored.
 *
 * @author SkiBu Smith
 *
 */
public class MbtaPredictionAccuracyModule extends PredictionAccuracyModule {

	// For when requesting predictions from external MBTA API
	private static final int timeoutMsec = 20000;
	
	private static final Logger logger = LoggerFactory
			.getLogger(MbtaPredictionAccuracyModule.class);

	/********************** Config Params **************************/
	
	private static final StringConfigValue externalPredictionApiUrl = 
			new StringConfigValue("transitime.predAccuracy.externalPredictionApiUrl", 
					"http://realtime.mbta.com/developer/api/v2/predictionsbyroute?",
					"URL to access to obtain external predictions.");
	
	private static String getExternalPredictionApiUrl() {
		return externalPredictionApiUrl.getValue();
	}

	private static final StringConfigValue apiKey = 
			new StringConfigValue("transitime.predAccuracy.apiKey", 
					"wX9NwuHnZU2ToO7GmGR9uw",
					"The API key to use when accessing the external prediction "
					+ "feed. The default value is the public MBTA key, which "
							+ "could change at any time.");
	
	private static String getApiKey() {
		return apiKey.getValue();
	}

	/********************** Member Functions **************************/

	/**
	 * @param agencyId
	 */
	public MbtaPredictionAccuracyModule(String agencyId) {
		super(agencyId);
	}

	/**
	 * Determine the URL to use to get data for specified route.
	 * 
	 * @param routeId
	 * @return
	 */
	private String getUrl(String routeId) {
		return getExternalPredictionApiUrl() 
				+ "api_key=" + getApiKey() 
				+ "&format=xml" 
				+ "&route=" + routeId;
	}
	
	/**
	 * Gets XML data for route from API an returns an XML Document object
	 * containing the resulting data.
	 * 
	 * @param routeAndStops Specifies which route to read data for
	 * @return
	 */
	private Document getExternalPredictionsForRoute(RouteAndStops routeAndStops) {
		String fullUrl = getUrl(routeAndStops.routeId);
		
		logger.info("Getting predictions from API for route={} using URL={}",
				routeAndStops.routeId, fullUrl);

		try {
			// Create the connection
			URL url = new URL(fullUrl);
			URLConnection con = url.openConnection();
			
			// Set the timeout so don't wait forever
			con.setConnectTimeout(timeoutMsec);
			con.setReadTimeout(timeoutMsec);
			
			// Request compressed data to reduce bandwidth used
			con.setRequestProperty("Accept-Encoding", "gzip,deflate");
			
			// Make sure the response is proper
			int httpResponseCode = ((HttpURLConnection) con).getResponseCode();
			if (httpResponseCode != HttpURLConnection.HTTP_OK) {
				logger.error("Error when getting predictions. Response code "
						+ "was {} for URL={}", httpResponseCode, fullUrl);
				return null;
			}
				
			// Create appropriate input stream depending on whether content is 
			// compressed or not
			InputStream in = con.getInputStream();
			SAXBuilder builder = new SAXBuilder();
			Document doc = builder.build(in);
			return doc;
		} catch (IOException | JDOMException e) {
			logger.error("Problem when getting data for route for URL={}", 
					fullUrl, e);
			return null;
		}
	}

	/**
	 * Returns true if the specified stop is not a wait stop, indicating that
	 * the prediction is an arrival prediction (wait stops are terminal 
	 * departures and are therefore departure predictions.
	 * 
	 * @param tripId
	 * @param stopId
	 * @return
	 */
	private boolean isArrival(String tripId, String stopId) {
		// Get the trip. If doesn't exist return false
		Trip trip = Core.getInstance().getDbConfig().getTrip(tripId);
		if (trip == null) {
			logger.error("tripId={} doesn't exist");
			return false;
		}
		
		// Get the stop path for the trip. If doesn't exist return false
		StopPath stopPath = trip.getStopPath(stopId);
		if (stopPath == null) {
			logger.error("stopId={} doesn't exist for tripId={}", 
					stopId, tripId);
			return false;
		}
		
		// Predictions are for arrivals unless it is a wait stop
		return !stopPath.isWaitStop();
	}
	
	/**
	 * Takes data from XML Document object and processes it
	 * 
	 * @param routeId
	 * @param doc
	 * @param predictionsReadTime
	 */
	private void processExternalPredictionsForRoute(
			RouteAndStops routeAndStops, Document doc,
			Date predictionsReadTime) {
		// If couldn't read data from feed then can't process it
		if (doc == null)
			return;
		
		String routeId = routeAndStops.routeId;

		// Get root of doc
		Element rootNode = doc.getRootElement();

		List<Element> directions = rootNode.getChildren("direction");
		if (directions.isEmpty()) {
			logger.error("No direction element returned.");
			return;
		}
		for (Element direction : directions) {
			String directionId = direction.getAttributeValue("direction_id");
			
			// If this direction is not one that should be getting preds for
			// then continue to next direction
			if (!routeAndStops.stopIds.keySet().contains(directionId))
				continue;
			
			List<Element> trips = direction.getChildren("trip");
			for (Element trip : trips) {
				String tripId = trip.getAttributeValue("trip_id");
				String vehicleId = trip.getChild("vehicle")
						.getAttributeValue("vehicle_id");
				List<Element> stops = trip.getChildren("stop");
				for (Element stop : stops) {
					String stopId = stop.getAttributeValue("stop_id");
					
					Collection<String> stopIds = 
							routeAndStops.stopIds.get(directionId);
					// If stop is not one of the ones that supposed to be
					// collecting predictions for then continue to next stop.
					if (!stopIds.contains(stopId))
						continue;
					
					String predictionEpochTimeStr = stop
							.getAttributeValue("pre_dt");
					Date predictedTime = new Date(
							Long.parseLong(predictionEpochTimeStr + "000"));
					
					String predictionsInSecondsStr = 
							stop.getAttributeValue("pre_away");
					Date predictedTimeUsingSecs = new Date(
							System.currentTimeMillis()
									+ Integer.parseInt(predictionsInSecondsStr)
									* Time.MS_PER_SEC);
					
					// Need to differentiate between arrival and departure 
					// predictions
					boolean isArrival = isArrival(tripId, stopId);
					
					logger.debug(
							"Storing external prediction routeId={}, "
							+ "directionId={}, tripId={}, vehicleId={}, "
							+ "stopId={}, prediction={}, "
							+ "predictedTimeUsingSecs={}, isArrival={}",
							routeId, directionId, tripId, 
							vehicleId, stopId, predictedTime, 
							predictedTimeUsingSecs, isArrival);
					
					// Store in memory the prediction based on absolute time
					PredAccuracyPrediction pred = new PredAccuracyPrediction(
							routeId, directionId, stopId, tripId, vehicleId,
							predictedTime, predictionsReadTime, isArrival, 
							"MBTA_epoch");
					storePrediction(pred);
					
					// Store in memory the prediction based on number of seconds
					PredAccuracyPrediction predUsingSecs = new PredAccuracyPrediction(
							routeId, directionId, stopId, tripId, vehicleId,
							predictedTimeUsingSecs, predictionsReadTime, 
							isArrival, "MBTA_seconds");
					storePrediction(predUsingSecs);
				}
			}
		}
	}
	
	/**
	 * Processes both the internal and external predictions
	 * 
	 * @param routesAndStops
	 * @param predictionsReadTime
	 *            For keeping track of when the predictions read in. Used for
	 *            determining length of predictions. Should be the same for all
	 *            predictions read in during a polling cycle even if the
	 *            predictions are read at slightly different times. By using the
	 *            same time can easily see from data in db which internal and
	 *            external predictions are associated with each other.
	 */
	@Override
	protected void getAndProcessData(List<RouteAndStops> routesAndStops, 
			Date predictionsReadTime) {
		// Process internal predictions
		super.getAndProcessData(routesAndStops, predictionsReadTime);
		
		logger.debug("Calling MbtaPredictionReaderModule.getAndProcessData()");
		
		// Get data for each route
		for (RouteAndStops routeAndStops : routesAndStops) {
			Document doc = getExternalPredictionsForRoute(routeAndStops);
			processExternalPredictionsForRoute(routeAndStops, doc, 
					predictionsReadTime);
		}
	}

	/**
	 * Just for debugging
	 */
	public static void main(String[] args) {
		// Create a NextBusAvlModue for testing
		Module.start("org.transitime.core.predAccuracy.MbtaPredictionReaderModule");
	}

}