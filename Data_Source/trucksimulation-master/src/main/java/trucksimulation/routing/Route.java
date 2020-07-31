package trucksimulation.routing;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.PathWrapper;
import com.graphhopper.reader.dem.SRTMProvider;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.util.Instruction;

import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;


public class Route {
	
	private String id;
	private Position start;	
	private Position goal;
	private RouteSegment[] segments;
	private double timeMs;
	private double distanceMeters;
	private double ascend;
	private double descend;
	private transient PathWrapper pathWrapper;
	private transient String osmPath;
	private transient String ghCacheLocation;
	private transient GraphHopper hopper;
	
	private static final Logger LOGGER = LoggerFactory.getLogger(Route.class);

	// All routes in current simulation
	private static ArrayList<Route> routes = new ArrayList<>();
		
	public static Route getRoute(GraphHopper hopper, Position start, Position destination) {
		Route r = new Route(start, destination, hopper);
		r.init();
		return r;
	}

	public static List<Route> getAllRoutes() {
		return routes;
	}
	
	public Route() {
		String userHome = System.getProperty("user.home");
		ghCacheLocation = new File(userHome, ".graphhopper").getAbsolutePath();
		this.routes.add(this);
	}

	public Route(String id) {
		this();
		this.id = id;
	}

	private Route(Position start, Position dest, GraphHopper hopper) {
		this();
		this.start = start;
		this.goal = dest;
		this.hopper = hopper;
		this.routes.add(this);
	}

	private Route(Position start, Position dest, GraphHopper hopper, String id) {
		this(id);
		this.start = start;
		this.goal = dest;
		this.hopper = hopper;
		this.routes.add(this);
	}
	
	private void init() {
		calcRoute();
		loadRouteFromWrapper();
	}
	
	private void loadGraphHopper() {
		// create one GraphHopper instance
		Map<String, String> env = System.getenv();
		if(Boolean.valueOf(env.get("LOW_MEMORY"))) {
			LOGGER.info("Using Graphhopper for mobile due to LOW_MEMORY env.");
			hopper = new GraphHopper().forMobile();
			hopper.setCHPrepareThreads(1);
		} else {
			hopper = new GraphHopper().forServer();
		}
		hopper.setOSMFile(osmPath);
		hopper.setGraphHopperLocation(ghCacheLocation);
		hopper.setEncodingManager(new EncodingManager("car"));
		hopper.importOrLoad();
		hopper.setElevation(true);
		hopper.setElevationProvider(new SRTMProvider());
	}
	
	private void calcRoute() {
		if(hopper == null) {
			loadGraphHopper();
		}
		// simple configuration of the request object, see the GraphHopperServlet classs for more possibilities.
		GHRequest req = new GHRequest(start.getLat(), start.getLon(), goal.getLat(), goal.getLon()).
		    setWeighting("fastest").
		    setVehicle("car").
		    setLocale(Locale.US);
		GHResponse rsp = hopper.route(req);

		// first check for errors
		if(rsp.hasErrors()) {
		   throw new IllegalArgumentException("Could not calculate route. Check coordinates.", rsp.getErrors().get(0));
		}
		pathWrapper = rsp.getBest();
	}
	
	private void loadRouteFromWrapper() {
		this.timeMs = pathWrapper.getTime();
		this.distanceMeters = pathWrapper.getDistance();
		this.ascend = pathWrapper.getAscend();
		this.descend = pathWrapper.getDescend();
		
		List<RouteSegment> tmpSegList = new ArrayList<>(pathWrapper.getInstructions().size());
		for(int s = 0; s < pathWrapper.getInstructions().size(); s++) {
			Instruction inst = pathWrapper.getInstructions().get(s);
			if(inst.getPoints().size() > 1) {
				double dist = inst.getDistance();
				double time = inst.getTime();
				int annotation = inst.getSign();
				double lats[] = new double[inst.getPoints().size()];
				double lons[] = new double[inst.getPoints().size()];
				double eles[] = new double[inst.getPoints().size()];
				for(int i = 0; i < inst.getPoints().size(); i++) {
					lats[i] = inst.getPoints().getLat(i);
					lons[i] = inst.getPoints().getLon(i);
					eles[i] = inst.getPoints().getEle(i);
				}
				RouteSegment segment = new RouteSegment(lats, lons, eles, time, dist, annotation);
				tmpSegList.add(segment);
			} else {
				//TODO: append point to previous segment if position is different
				// from previous point
				LOGGER.warn("Dropped point from instruction list.");
			}
		}
		segments = tmpSegList.toArray(new RouteSegment[0]);
		// wrapper can be garbage collected, it is no longer needed
		pathWrapper = null;
	}
	
	
	public RouteSegment getSegment(int index) {
		return segments[index];
	}
	
	public int getSegmentCount() {
		return segments.length;
	}

	public Position getStart() {
		return start;
	}


	public void setStart(Position start) {
		this.start = start;
	}


	public Position getGoal() {
		return goal;
	}


	public void setGoal(Position goal) {
		this.goal = goal;
	}
	
	/**
	 * 
	 * @return approximate time in milliseconds that is needed to drive the route.
	 */
	public double getTimeMs() {
		return timeMs;
	}

	public void setTimeMs(double time) {
		this.timeMs = time;
	}

	public double getDistanceMeters() {
		return distanceMeters;
	}

	public void setDistanceMeters(double distance) {
		this.distanceMeters = distance;
	}

	public double getAscend() {
		return ascend;
	}

	public void setAscend(double ascend) {
		this.ascend = ascend;
	}

	public double getDescend() {
		return descend;
	}

	public String getId(){
		return this.id;
	}

	public void setId(String id){
		this.id = id;
	}

	public void setDescend(double descend) {
		this.descend = descend;
	}

	public RouteSegment[] getSegments() {
		return segments;
	}
	
	/**
	 * Sets the route's segments and updates start and goal accordingly.
	 * @param segments
	 */
	public void setSegments(RouteSegment... segments) {
		if(segments == null) {
			throw new IllegalArgumentException("segments must not be null");
		}
		this.segments = segments;
		this.start = segments[0].getPoint(0);
		RouteSegment lastSeg = segments[segments.length-1];
		this.goal = lastSeg.getPoint(lastSeg.getSize()-1);
	}

	public String getGhCacheLocation() {
		return ghCacheLocation;
	}

	public void setGhCacheLocation(String ghCacheLocation) {
		this.ghCacheLocation = ghCacheLocation;
	}

	@Override
	public String toString() {
		return String.format("%s -> %s", start, goal);
	}

}
