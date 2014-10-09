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

package org.transitime.core.travelTimes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transitime.core.TemporalDifference;
import org.transitime.core.travelTimes.DataFetcher.DbDataMapKey;
import org.transitime.db.structs.ArrivalDeparture;
import org.transitime.db.structs.Match;
import org.transitime.db.structs.Trip;
import org.transitime.statistics.MiscStatistics;
import org.transitime.utils.Geo;
import org.transitime.utils.IntervalTimer;
import org.transitime.utils.MapKey;
import org.transitime.utils.Time;

/**
 * Takes arrival/departure times plus the matches (where vehicle is matched to a
 * route between stops) that are read from db and processes the data into
 * average travel and dwell times and stores the data into maps. The travel
 * times can then be written to the database.
 * <p>
 * The data is processed on a per tripId basis. With GTFS trips are unique
 * across service IDs and days of the week so this is adequate. But at some
 * point really might want to divide up the data by day of the week in order to
 * get greater accuracy (assuming that buses might consistently travel
 * differently on Monday compared to Friday even though they have the same
 * service ID.
 *
 * @author SkiBu Smith
 *
 */
public class TravelTimesProcessor {

	// For determining stop time for first stop in trip. Need to limit it 
	// because if vehicle is really late then perhaps it is matched to
	// wrong trip or such. At the very minimum it is an anomaly. In such a 
	// case the data would only skew the stop time.
	private final static int MAX_SCHED_ADH_FOR_FIRST_STOP_TIME = 
			10*Time.MS_PER_MIN;
	
	private final static int MAX_SCHED_ADH_SECS =
			30*Time.SEC_PER_MIN;
	
	private double maxTravelTimeSegmentLength;
		
	// The aggregate data processed from the historic db data.
	// ProcessedDataMapKey combines tripId and stopPathIndex in 
	// order to combine data for a particular tripId and stopPathIndex.
	// stopTimesMap contains data for each trip on how long vehicle was stopped
	// for at a particular stop. It is obtained by comparing the arrival time
	// with the departure time for each stop for each trip.
	private static Map<ProcessedDataMapKey, List<Integer>> stopTimesMap = 
			new HashMap<ProcessedDataMapKey, List<Integer>>();	
	private static Map<ProcessedDataMapKey, List<List<Integer>>> travelTimesMap =
			new HashMap<ProcessedDataMapKey, List<List<Integer>>>();

	private static final Logger logger = 
			LoggerFactory.getLogger(TravelTimesProcessor.class);

	/********************** Member Functions **************************/

	/**
	 * Constructor
	 * 
	 * @param maxTravelTimeSegmentLength
	 *            For determining how many travel time segments there should be
	 *            in a stop path.
	 */
	public TravelTimesProcessor(double maxTravelTimeSegmentLength) {
		this.maxTravelTimeSegmentLength = maxTravelTimeSegmentLength;
	}
	
	/**
	 * Special MapKey class so that can make sure using the proper one for the
	 * associated maps in this class. The key is made up of the tripId and the
	 * stopPathIndex.
	 */
	public static class ProcessedDataMapKey extends MapKey {
		private ProcessedDataMapKey(String tripId, int stopPathIndex) {
			super(tripId, stopPathIndex);
		}
		
		private String getTripId() {
			return (String) o1;
		}
		
		private int getStopPathIndex() {
			return (int) o2;
		}

		@Override
		public String toString() {
			return "ProcessedDataMapKey [tripId=" + o1 + ", stopPathIndex=" + o2 + "]";
		}
	}

	private static ProcessedDataMapKey getKey(String tripId, int stopIndex) {
		return new ProcessedDataMapKey(tripId, stopIndex);
	}

	/**
	 * Adds stop times for a stop path for a single trip to the stopTimesMap.
	 * 
	 * @param mapKey
	 * @param stopTimeMsec
	 */
	private static void addStopTimeToMap(ProcessedDataMapKey mapKey,
			int stopTimeMsec) {
		List<Integer> stopTimesForStop = stopTimesMap.get(mapKey);
		if (stopTimesForStop == null) {
			stopTimesForStop = new ArrayList<Integer>();
			stopTimesMap.put(mapKey, stopTimesForStop);
		}
		stopTimesForStop.add(stopTimeMsec);
	}
	
	/**
	 * Adds travel times for stop path for a single trip to the travelTimesMap.
	 * 
	 * @param mapKey
	 * @param travelTimesForStopPath
	 */
	private static void addTravelTimesToMap(ProcessedDataMapKey mapKey, 
			List<Integer> travelTimesForStopPath) {
		// If there is no data then simply return
		if (travelTimesForStopPath == null || travelTimesForStopPath.isEmpty())
			return;
		
		List<List<Integer>> travelTimesForStop = travelTimesMap.get(mapKey);
		if (travelTimesForStop == null) {
			travelTimesForStop = new ArrayList<List<Integer>>();
			travelTimesMap.put(mapKey, travelTimesForStop);
		}
		travelTimesForStop.add(travelTimesForStopPath);
	}
	
	/**
	 * Just for debugging. Logs raw data for trip.
	 * 
	 * @param arrDepList
	 */
	private static void debugLogTrip(List<ArrivalDeparture> arrDepList) {
		logger.trace("====================");
		for (ArrivalDeparture arrivalDeparture : arrDepList) {
			logger.trace(arrivalDeparture.toString());
		}
	}
	
	/**
	 * For when the arrival/departure is for first stop of trip. If the schedule
	 * adherence isn't too bad adds the stop time to the stop wait map.
	 * 
	 * @param arrDep
	 */
	private static void processFirstStopOfTrip(ArrivalDeparture arrDep) {
		// Only need to handle departure for first stop in trip
		if (arrDep.getStopPathIndex() != 0) 
			return;
		
		// Should only process departures for the first stop, so make sure.
		// If not a departure then continue to the next stop
		if (arrDep.isArrival())
			return;

		// First stop in trip so just deal with departure time.
		// Don't need to deal with travel time.
		int lateTimeMsec = 
				(int) (arrDep.getScheduledTime() - arrDep.getTime());

		// If schedule adherence is really far off then ignore the data
		// point because it would skew the results.
		if (Math.abs(lateTimeMsec) > MAX_SCHED_ADH_FOR_FIRST_STOP_TIME) 
			return;
		
		// Get the MapKey so can put stop time into map
		ProcessedDataMapKey mapKeyForTravelTimes = 
				getKey(arrDep.getTripId(), arrDep.getStopPathIndex());

		// Add this stop time to map so it can be averaged
		addStopTimeToMap(mapKeyForTravelTimes, lateTimeMsec);		
	}
	
	/**
	 * Returns the matches for the particular stopPath for the service ID and
	 * trip.
	 * <P>
	 * Note: This is pretty inefficient. Seems that should be able to get
	 * list of matches for the stopPath directly from the map instead of getting
	 * all the matches for the trip and then filtering them.
	 * 
	 * @param dataFetcher
	 * @param arrDep
	 * @return List of Match objects. Never returns null.
	 */
	private static List<Match> getMatchesForStopPath(DataFetcher dataFetcher,
			ArrivalDeparture arrDep) {
		// For returning the results
		List<Match> matchesForStopPath = new ArrayList<Match>();

		// Get the matches for the entire trip since that is
		// how the data is available
		DbDataMapKey mapKey = dataFetcher.getKey(arrDep.getServiceId(),
				arrDep.getDate(), arrDep.getTripId(), arrDep.getVehicleId());
		List<Match> matchesForTrip = dataFetcher.getMatchesMap().get(mapKey);
		
		// If no matches were found for this trip then return empty
		// array (don't continue since would get NPE).
		if (matchesForTrip == null)
			return matchesForStopPath;
		
		for (Match match : matchesForTrip) {
			if (match.getStopPathIndex() == arrDep.getStopPathIndex())
				matchesForStopPath.add(match);
			else {
				// If looking at matches past the stop then done. Breaking
				// out of the for loop makes the code more efficient.
				if (match.getStopPathIndex() > arrDep.getStopPathIndex())
					break;
			}
		}
		
		return matchesForStopPath;
	}
	
	/**
	 * Internal structure for keeping track of matches that are between an
	 * departure and an arrival. Needed for when there are multiple travel
	 * time segments within a stop path. 
	 */
	private static class MatchPoint {
		private final Date time;
		private final float distanceAlongStopPath;
		private final MatchPointReason reason;
		
		public enum MatchPointReason {DEPARTURE, MATCH, ARRIVAL};
		
		private MatchPoint(Date time, float distance, MatchPointReason reason) {
			this.time = time;
			this.distanceAlongStopPath = distance;
			this.reason = reason;
		}
		
		private long getTime() {
			return time.getTime();
		}

		@Override
		public String toString() {
			return "MatchPoint [" 
					+ "time=" + time
					+ ", distanceAlongStopPath=" 
						+ Geo.distanceFormat(distanceAlongStopPath) 
					+ ", reason=" + reason
					+ "]";
		}	
		
	}
	
	/**
	 * Number of travel time segments for the stop path with the specified
	 * length.
	 * 
	 * @param pathLength
	 * @return number of travel time segments
	 */
	private int getNumTravelTimeSegments(double pathLength) {
		int numberTravelTimeSegments = 
				(int) (pathLength / maxTravelTimeSegmentLength + 1.0);		
		return numberTravelTimeSegments;
	}

	/**
	 * The travel time length is the length of the stop path divided into equal
	 * segments such that the segments are no longer than
	 * maxTravelTimeSegmentLength.
	 * 
	 * @param trip
	 *            For determining the StopPath
	 * @param stopPathIndex
	 *            For determining the StopPath
	 * @return travel time length for the specified StopPath
	 */
	private double getTravelTimeSegmentLength(Trip trip, int stopPathIndex) {
		double pathLength = trip.getStopPath(stopPathIndex).getLength(); 
		double segLength = pathLength / getNumTravelTimeSegments(pathLength);
		return segLength;
	}
	
	/**
	 * The travel time length is the length of the stop path divided into equal
	 * segments such that the segments are no longer than
	 * maxTravelTimeSegmentLength.
	 * 
	 * @param arrDep
	 *            The arrival stop for which to determine the travel time length
	 * @return travel time length for the specified arrival
	 */
	private double getTravelTimeSegmentLength(ArrivalDeparture arrDep) {
		double pathLength = arrDep.getStopPathLength();
		double segLength = pathLength / getNumTravelTimeSegments(pathLength);
		return segLength;
	}
	
	/**
	 * Gets the Matches that are associated with the stop path specified by
	 * arrDep2 (the path leading up to that stop) for the same trip (makes sure
	 * it is for the same tripId for the same day of the year). The returned
	 * matches will include the departure time from the first stop (arrDep1), in
	 * between matches, and the arrival time as the second stop (arrDep2).
	 * 
	 * @param dataFetcher
	 * @param arrDep1
	 *            The departure stop
	 * @param arrDep2
	 *            The arrival stop. Also defines which stop path working with.
	 * @return List of MatchPoints, which contain the basic Match info needed
	 *         for determining travel times.
	 */
	private static List<MatchPoint> getMatchPoints(DataFetcher dataFetcher,
			ArrivalDeparture arrDep1, ArrivalDeparture arrDep2) {
		// The array to be returned
		List<MatchPoint> matchPoints = new ArrayList<MatchPoint>();
		
		// Add the departure time to the list of data points
		matchPoints.add(new MatchPoint(arrDep1.getDate(), 0.0f,
				MatchPoint.MatchPointReason.DEPARTURE));
		
		// Stop path is long enough such that have more than one travel
		// time segment. Get the corresponding matches
		List<Match> matchesForStopPath = 
				getMatchesForStopPath(dataFetcher, arrDep2);

		// Add the matches that are in between the arrival and the departure.
		for (Match match : matchesForStopPath) {
			matchPoints.add(new MatchPoint(match.getDate(), 
					match.getDistanceAlongStopPath(), 
					MatchPoint.MatchPointReason.MATCH));
		}
		
		// Add the arrival time to the list of data points
		matchPoints.add(new MatchPoint(arrDep2.getDate(), 
				arrDep2.getStopPathLength(), 
				MatchPoint.MatchPointReason.ARRIVAL));

		// Return the list of time/distanceAlongStopPath points
		return matchPoints;
	}

	/**
	 * For when the path between stops is long enough such that there are
	 * multiple travel time segments. For the particular stop path, as specified
	 * by the arrival stop, looks up the associated Matches in order to
	 * determine how the vehicle travels along the stop path. Determines when
	 * the travel time segment vertices are crossed and uses the vertices, along
	 * with the departure time and the arrival times at the ends of the stop
	 * path, to determine the travel time for each travel time segment for this
	 * particular trip.
	 * 
	 * @param dataFetcher
	 * @param arrDep1
	 *            The departure stop
	 * @param arrDep2
	 *            The arrival stop. Also defines which stop path working with.
	 * @return List of travel times in msec. There is a separate travel time for
	 *         each travel time segment. If the match points are garbled and go
	 *         backwards in time then null is returned.
	 */
	private List<Integer> addTravelTimesForMultiSegments(
			DataFetcher dataFetcher, ArrivalDeparture arrDep1,
			ArrivalDeparture arrDep2) {
		double travelTimeSegmentLength = getTravelTimeSegmentLength(arrDep2);

		List<MatchPoint> matchPoints = 
				getMatchPoints(dataFetcher, arrDep1, arrDep2);
		
		// The times when a travel time segment vertex is crossed.
		// Will include the departure time, the middle vertices, and
		// the arrival time so that all the travel times can be determined.
		List<Long> vertexTimes = new ArrayList<Long>();
		
		// Add departure time from first stop
		vertexTimes.add(arrDep1.getTime());
		
		for (int i=0; i<matchPoints.size()-1; ++i) {
			MatchPoint pt1 = matchPoints.get(i);
			MatchPoint pt2 = matchPoints.get(i+1);

			// If the match points decrease in time then it means that data
			// is messed up and can't determine travel times. This can happen
			// if the predictor is restarted and vehicles are first matched
			// incorrectly due to them being off schedule.			
			if (pt2.getTime() < pt1.getTime()) {
				logger.error("Encountered two match points that go backwards " +
						"in time and are therefore incorrect. They are {} " +
						"and {} between {} and {}", pt1, pt2, arrDep1, arrDep2);
				return null;
			}
			
			// Determine which travel time segment the match points are on.
			// Need to subtract 0.0000001 for segIndex2 because the distance
			// can be the distance to the end of the stop path which of
			// course is an exact multiple of the travelTimeSegmentLength.
			// To get the right segment index need to subtract a bit.
			int segIndex1 = (int) (pt1.distanceAlongStopPath / 
					travelTimeSegmentLength);
			int segIndex2 = (int) ((pt2.distanceAlongStopPath-0.0000001) / 
					travelTimeSegmentLength);
			
			// If the two matches span a travel time segment vertex...
			if (segIndex1 != segIndex2) {
				// Determine speed traveled between the two matches.
				// Note that the speed is in meters per msec.
				long timeBtwnMatches = pt2.getTime() - pt1.getTime();
				float distanceBtwnMatches = 
						pt2.distanceAlongStopPath - pt1.distanceAlongStopPath;
				double speed = distanceBtwnMatches / timeBtwnMatches;
				
				// Determine when crossed the first vertex between the match points
				// and add the time to the vertex times
				double distanceOfFirstVertex = 
						(segIndex1+1) * travelTimeSegmentLength;
				double distanceToFirstVertex = 
						distanceOfFirstVertex - pt1.distanceAlongStopPath;
				long crossingTimeForVertex = 
						pt1.getTime() + (long) (distanceToFirstVertex/speed);
				vertexTimes.add(crossingTimeForVertex);
				
				// Add any subsequent vertices crossed between the match points
				for (int segIndex=segIndex1+1; segIndex<segIndex2; ++segIndex) {
					crossingTimeForVertex += travelTimeSegmentLength / speed;
					vertexTimes.add(crossingTimeForVertex);
				}
			}			
		}
		// Deal with the final travel time segment that goes to the arrival stop
		vertexTimes.add(arrDep2.getTime());
		
		// Now that we have all the vertex times for the stop path determine the
		// travel times and add them to the list of times to be returned.
		List<Integer> travelTimesForStopPath = new ArrayList<Integer>();
		for (int i=0; i<vertexTimes.size()-1; ++i) {
			long vertexTime1 = vertexTimes.get(i);
			long vertexTime2 = vertexTimes.get(i+1);
			travelTimesForStopPath.add((int) (vertexTime2 - vertexTime1));
		}
		return travelTimesForStopPath;
	}
	
	/**
	 * For looking at travel time between two arrival/departures. It can be an
	 * arrival and then a departure for the same stop or a departure for one
	 * stop and then an arrival for the subsequent stop. If the schedule
	 * adherence is off too much (by MAX_SCHED_ADH_SECS) then the data is
	 * ignored. If schedule adherence is acceptable then the resulting travel
	 * and stop/dwell times are put into the stopTimesMap and travelTimesMap
	 * members for further processing.
	 * 
	 * @param dataFetcher
	 *            Contains the AVL based historic data in maps
	 * @param arrDep1
	 *            The first arrival/departure
	 * @param arrDep2
	 *            The second arrival/departure
	 */
	private void processDataBetweenTwoArrivalDepartures(
			DataFetcher dataFetcher, ArrivalDeparture arrDep1,
			ArrivalDeparture arrDep2) {
		// If schedule adherence is really far off then ignore the data
		// point because it would skew the results.
		TemporalDifference schedAdh = arrDep1.getScheduleAdherence();
		if (schedAdh == null)
			schedAdh = arrDep2.getScheduleAdherence();
		if (schedAdh != null
				&& !schedAdh.isWithinBounds(MAX_SCHED_ADH_SECS,
						MAX_SCHED_ADH_SECS)) {
			// Schedule adherence is off so don't use this data
			return;
		}
		
		// Determine the key for storing the data into appropriate map
		ProcessedDataMapKey mapKeyForTravelTimes = 
				getKey(arrDep2.getTripId(), arrDep2.getStopPathIndex());
			
		// If looking at arrival and departure for same stop then determine
		// the stop time.
		if (arrDep1.getStopPathIndex() == arrDep2.getStopPathIndex() 
				&& arrDep1.isArrival() 
				&& arrDep2.isDeparture()) {
			// Determine time at stop
			int dwellTimeMsec = (int) (arrDep2.getTime() - arrDep1.getTime());

			// Add this stop time to map so it can be averaged
			addStopTimeToMap(mapKeyForTravelTimes, dwellTimeMsec);		

			return;
		}
		
		// If looking at departure from one stop to the arrival time at the
		// very next stop then can determine the travel times between the stops.
		if (arrDep1.getStopPathIndex() - arrDep2.getStopPathIndex() != 1
				&& arrDep1.isDeparture()
				&& arrDep2.isArrival()) {
			// If the stopPath is short enough such that there will be
			// only a single travel segment...
			if (arrDep2.getStopPathLength() < maxTravelTimeSegmentLength) {
				// Determine the travel time between the stops
				int travelTimeBetweenStopsMsec = 
						(int) (arrDep2.getTime() - arrDep1.getTime());
				List<Integer> travelTimesForStopPath = new ArrayList<Integer>();
				travelTimesForStopPath.add(travelTimeBetweenStopsMsec);
				addTravelTimesToMap(mapKeyForTravelTimes, travelTimesForStopPath);
			} else {
				// The stop path is long enough such that there will be more
				// than a single travel time segment.
				//
				// Go through the matches for this stop path and use them to
				// determine the travel times for each travel time segment
				List<Integer> travelTimesForStopPath = addTravelTimesForMultiSegments(
						dataFetcher, arrDep1, arrDep2);
				addTravelTimesToMap(mapKeyForTravelTimes, travelTimesForStopPath);
			}
				
			return;
		}
	}
	
	/**
	 * Process historic data from database for single trip. Puts resulting data
	 * into stopTimesMap and travelTimesMap.
	 * 
	 * @param dataFetcher
	 *            Contains arrival/departures and matches fetched from database
	 * @param arrDepList
	 *            List of ArrivalDepartures for vehicle for a trip
	 */
	private void aggregateTripDataIntoMaps(DataFetcher dataFetcher,
			List<ArrivalDeparture> arrDepList) {
		
		for (int i=0; i<arrDepList.size()-1; ++i) {
			ArrivalDeparture arrDep1 = arrDepList.get(i);
			
			// Handle first stop in trip specially
			if (arrDep1.getStopPathIndex() == 0) {
				// Don't need to deal with first arrival stop for trip since
				// don't care when vehicle arrives at layover
				if (arrDep1.isArrival())
					continue;

				// Handle first stop
				processFirstStopOfTrip(arrDep1);
			} 
			
			// Deal with normal travel times
			ArrivalDeparture arrDep2 = arrDepList.get(i+1);				
			processDataBetweenTwoArrivalDepartures(dataFetcher, arrDep1, arrDep2);							
		}		
	}
		
	/**
	 * Converts the list of travel times such that times are grouped by segment
	 * instead of by single trips. This allows the times to be more easily
	 * processed when determining outliers, averages, etc.
	 * 
	 * @param travelTimes
	 *            List of List of times where outer List is by single trip and
	 *            inner List is travel time segment.
	 * @return List of List of times but the outer List is by travel time
	 *         segment and there is an inner List with a value per single trip
	 */
	private static List<List<Integer>> bySegment(List<List<Integer>> travelTimes) {
		int numTrips = travelTimes.size();
		int numTravelTimeSegments = travelTimes.get(0).size();
		
		// Create results object. Make array size only as big as the number of
		// travel segments, instead of the default value of 10, to reduce 
		// memory use.
		List<List<Integer>> timesBySegment = 
				new ArrayList<List<Integer>>(numTravelTimeSegments);
		for (int i=0; i<numTravelTimeSegments; ++i) {
			timesBySegment.add(new ArrayList<Integer>());
		}
		
		// Put the per trip data into the per segment array
		for (int tripIdx=0; tripIdx<numTrips; ++tripIdx) {
			for (int segIdx=0; segIdx<numTravelTimeSegments; ++segIdx) {
				Integer value = travelTimes.get(tripIdx).get(segIdx);
				timesBySegment.get(segIdx).add(value);				
			}
		}
		
		// Return the times grouped by segment
		return timesBySegment;
	}
	
	/**
	 * Takes the data from the stopTimesMap and travelTimesMap and creates
	 * corresponding travel times. Puts those travel times into the
	 * TravelTimeInfoMap that is returned.
	 * 
	 * @param tripMap
	 *            contains all the trips that are configured and that need
	 *            travel times for.
	 * @return TravelTimeInfoMap The generated travel times
	 */
	public TravelTimeInfoMap createTravelTimesFromMaps(
			Map<String, Trip> tripMap) {
		logger.info("Processing data into a TravelTimeInfoMap...");
		IntervalTimer intervalTimer = new IntervalTimer();

		TravelTimeInfoMap travelTimeInfoMap = new TravelTimeInfoMap();
		
		// For each trip that had historical arrivals/departures and or 
		// matches in the database...
		for (ProcessedDataMapKey mapKey : travelTimesMap.keySet()) {
			// Determine the associated Trip object for the data
			Trip trip = tripMap.get(mapKey.getTripId());
			if (trip == null) {
				logger.error("No trip exists for trip ID={} in " +
						"configuration data even though historic data was " +
						"found for it.", 
						mapKey.getTripId());
				continue;
			}
			
			// Determine average travel times for this trip/stop path
			List<List<Integer>> travelTimesForStopPathForTrip =
					travelTimesMap.get(mapKey);
			List<List<Integer>> travelTimesBySegment = 
				bySegment(travelTimesForStopPathForTrip);
			List<Integer> averageTravelTimes = new ArrayList<Integer>();
			for (List<Integer> travelTimesByTripForSegment : 
					travelTimesBySegment) {
				int averageTravelTimeForSegment = 
						MiscStatistics.filteredAverage(travelTimesByTripForSegment, 0.7);
				averageTravelTimes.add(averageTravelTimeForSegment);
			}
			
			// Determine average stop time for this trip/stop
			int averageStopTime;
			List<Integer> stopTimesForStopPathForTrip = 
					stopTimesMap.get(mapKey);
			if (stopTimesForStopPathForTrip != null) { 
				averageStopTime = 
						MiscStatistics.filteredAverage(stopTimesForStopPathForTrip, 0.7);				
			} else {
				// No arrival and corresponding departure time for the stop. 
				averageStopTime = TravelTimeInfo.STOP_TIME_NOT_VALID;

				// Not having stop time indicates possible problem unless it 
				// is the last stop path for the trip. So if not the last stop  
				// path for trip then log the problem.
				if (mapKey.getStopPathIndex() != trip.getNumberStopPaths()-1) {
					logger.debug("No stop times for {} even though there are " +
						"travel times for that map key", mapKey);
				}
			}
			
			// Determine the travel time segment length actually used
			double travelTimeSegLength = 
					getTravelTimeSegmentLength(trip, mapKey.getStopPathIndex());
			
			// Put the results into TravelTimeInfo object and put into 
			// TravelTimeInfo map so can be used to find best travel times 
			// when there is no data for particular trip.
			TravelTimeInfo travelTimeInfo = new TravelTimeInfo(trip,
					mapKey.getStopPathIndex(), averageStopTime,
					averageTravelTimes, travelTimeSegLength);
			travelTimeInfoMap.add(travelTimeInfo);
		}
		
		// Nice to log how long things took so can see progress and bottle necks
		logger.info("Processing data into a TravelTimeInfoMap took {} msec.", 
				intervalTimer.elapsedMsec());

		// Return the map with all the processed travel time data in it
		return travelTimeInfoMap;	
	}
	
	/**
	 * Reads in the Matches and the ArrivalDepartures from the database for the
	 * time specified. Puts the data into the stopTimesMap and the travelTimesMap 
	 * for further processing.
	 * 
	 * @param projectId
	 * @param specialDaysOfWeek
	 * @param beginTime
	 * @param endTime
	 */
	public void readAndProcessHistoricData(String projectId, 
			List<Integer> specialDaysOfWeek, Date beginTime, Date endTime) {
		// Read the arrivals/departures and matches into a DataFetcher
		DataFetcher dataFetcher = new DataFetcher(projectId, specialDaysOfWeek);
		dataFetcher.readData(projectId, beginTime, endTime);
		
		// Process all the historic data read from the database. Puts 
		// resulting data into stopTimesMap and travelTimesMap.
		logger.info("Processing data into travel time maps...");
		IntervalTimer intervalTimer = new IntervalTimer();
		Collection<List<ArrivalDeparture>> arrivalDepartures =
				dataFetcher.getArrivalDepartureMap().values();
		for (List<ArrivalDeparture> arrDepList : arrivalDepartures) {
			debugLogTrip(arrDepList);
			aggregateTripDataIntoMaps(dataFetcher, arrDepList);
		}
		
		// Nice to log how long things took so can see progress and bottle necks
		logger.info("Processing data from db into the travel times and stop " +
				"times map took {} msec.", 
				intervalTimer.elapsedMsec());
	}	


	/*
	 * Just for debugging
	 */
	public static void main(String[] args) {
		List<Integer> t1 = new ArrayList<Integer>();
		t1.add(1);
		t1.add(2);
		t1.add(3);
		
		List<Integer> t2 = new ArrayList<Integer>();
		t2.add(4);
		t2.add(5);
		t2.add(6);
		
		List<List<Integer>> travelTimesByTrip = new ArrayList<List<Integer>>();
		travelTimesByTrip.add(t1);
		travelTimesByTrip.add(t2);
		List<List<Integer>> travelTimesBySegment = bySegment(travelTimesByTrip);
		System.err.println(travelTimesByTrip);
		System.err.println(travelTimesBySegment);
	}
}