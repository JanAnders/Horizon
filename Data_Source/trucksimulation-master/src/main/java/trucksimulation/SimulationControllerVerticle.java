package trucksimulation;

import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import com.google.gson.Gson;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.shareddata.LocalMap;
import io.vertx.core.shareddata.SharedData;
import io.vertx.ext.mongo.FindOptions;
import io.vertx.ext.mongo.MongoClient;
import trucksimulation.routing.Route;
import trucksimulation.traffic.TrafficIncident;
import trucksimulation.trucks.Truck;

/**
 * Verticle for initializing, starting and stopping simulations.
 */
public class SimulationControllerVerticle extends AbstractVerticle {

	private static final Logger LOGGER = LoggerFactory.getLogger(SimulationControllerVerticle.class);
	private MongoClient mongo;
	private int intervalMS;
	private int msgInterval;
	private String kafkaURI;
	private String kafkaTopic;
	private String kafkaArrivalTopic;
	private String kafkaStartTopic;
	private boolean useKafka;

	/**
	 * Maps simulation id's to the running state of the simulation (true when
	 * running).
	 */
	private LocalMap<String, Boolean> simulationStatus;

	private HashMap<String, Simulation> simulations = new HashMap<String, Simulation>();

	@Override
	public void start() throws Exception {
		mongo = MongoClient.createShared(vertx, config().getJsonObject("mongodb", new JsonObject()));
		intervalMS = config().getJsonObject("simulation", new JsonObject()).getInteger("interval_ms", 1000);
		msgInterval = config().getJsonObject("simulation", new JsonObject()).getInteger("msgInterval", 1);
		kafkaURI = config().getJsonObject("kafka", new JsonObject()).getString("connection_string", "localhost:9092");
		kafkaTopic = config().getJsonObject("kafka", new JsonObject()).getString("topic", "simulation");
		kafkaArrivalTopic = config().getJsonObject("kafka", new JsonObject()).getString("arrivalTopic", "truckarrived");
		kafkaStartTopic = config().getJsonObject("kafka", new JsonObject()).getString("startTopic", "truckstart");
		useKafka = config().getJsonObject("kafka", new JsonObject()).getBoolean("postData", false);
		SharedData sd = vertx.sharedData();
		simulationStatus = sd.getLocalMap("simStatusMap");
		vertx.eventBus().consumer(Bus.START_SIMULATION.address(), this::startSimulation);
		vertx.eventBus().consumer(Bus.STOP_SIMULATION.address(), this::stopSimulation);
		vertx.eventBus().consumer(Bus.SIMULATION_STATUS.address(), this::getSimulationStatus);
		vertx.eventBus().consumer(Bus.SIMULATION_ENDED.address(), this::handleSimulationEnded);
		LOGGER.info("Started Simulation Controller Verticle");
	}

	/**
	 * Loads all trucks from the db which belong to this simulation and starts
	 * moving them as soon as their corresponding routes are loaded.
	 * @param msg
	 */
	private void startSimulation(Message<JsonObject> msg) {
		JsonObject simulationJson = msg.body();
		String simId = simulationJson.getString("_id");
		if (isSimulationRunning(simId)) {
			msg.fail(400, "Simulation is already running.");
			return;
		}
		Simulation simulation = new Simulation(simId, vertx);
		simulation.setIntervalMs(intervalMS);
		simulation.setPublishInterval(msgInterval);
		simulation.setEndlessMode(simulationJson.getBoolean("endless", false));
		simulation.setKafkaInfo(kafkaURI, kafkaTopic, kafkaArrivalTopic, kafkaStartTopic, useKafka);
		simulations.put(simId, simulation);


		JsonObject trucksQuery = new JsonObject().put("simulation", simId);
		mongo.find("trucks", trucksQuery, res -> {
			if (res.failed()) {
			    LOGGER.warn(res.cause().getMessage());
				msg.fail(500, res.cause().getMessage());
			} else {
				msg.reply("ok");
				setRunningStatus(simId, true);
				simulations.get(simId).setTruckCount(res.result().size());
				for (JsonObject truckJson : res.result()) {
					Truck truck = new Truck(truckJson.getString("_id"),
											truckJson.getString("licensePlate"),
											truckJson.getInteger("truckType"),
											truckJson.getInteger("year"),
											truckJson.getInteger("massEmpty"),
											truckJson.getDouble("surface"),
											truckJson.getDouble("cw"));
					truck.setRouteId(truckJson.getString("route"));
					LOGGER.info("Gave truck `{0}` route id `{1}`", truck.getId(), truckJson.getString("route"));
					simulation.addTruck(truck);
					assignRoute(simId, truck);
				}
				loadTrafficIncidents(simId);
				simulation.start();
			}
		});
	}

	/**
	 * Stops the simulation if it is running in this verticle and updates the
	 * running status in the shared status map {@link #simulationStatus}.
	 * 
	 * @param msg
	 *            a JsonObject with an "_id" field containing the id string fo
	 *            the simulation.
	 */
	private void stopSimulation(Message<JsonObject> msg) {
		String simId = msg.body().getString("_id");
		if (simulations.containsKey(simId)) {
			LOGGER.info("simulation `{0}` is being stopped in verticle `{1}`", simId, this.deploymentID());
			Simulation simulation = simulations.get(simId);
			simulation.stop();
			simulations.remove(simId);
			setRunningStatus(simId, false);
		}
	}

	private void getSimulationStatus(Message<String> msg) {
		String simId = msg.body();
		msg.reply(isSimulationRunning(simId));
	}

	private void handleSimulationEnded(Message<JsonObject> msg) {
		String simId = msg.body().getString("id");
		simulations.remove(simId);
		setRunningStatus(simId, false);
	}

	/**
	 * Resolves reference to the truck's route and assigns route objects to the
	 * truck.
	 * 
	 * @param simulationId
	 * @param truck
	 */
	private void assignRoute(String simulationId, Truck truck) {
		Gson gson = Serializer.get();
		JsonObject routeQuery = new JsonObject().put("_id", truck.getRouteId());

		mongo.findOne("routes", routeQuery, new JsonObject(), r -> {
			Route route = gson.fromJson(r.result().toString(), Route.class);
			route.setId(truck.getRouteId());
			simulations.get(simulationId).addRoute(truck.getRouteId(), route);
		});
	}

	/**
	 * Loads all traffic incidents which belong to the simulation and assigns
	 * incidents to trucks which are affected by those incidents.
	 * 
	 * @param simId
	 *            the simulation id
	 */
	private void loadTrafficIncidents(String simId) {
		Gson gson = Serializer.get();
		JsonObject query = new JsonObject().put("simulation", simId).put("active", true);
		Simulation simulation = simulations.get(simId);

		mongo.find("traffic", query, trafficResult -> {
			if (trafficResult.result() != null) {
				simulation.setIncidentCount(trafficResult.result().size());

				for (JsonObject incidentJson : trafficResult.result()) {
					JsonObject intersectionQuery = buildIntersectionQuery(incidentJson, simId);
					FindOptions idFieldOnly = new FindOptions().setFields(new JsonObject().put("_id", true));

					mongo.findWithOptions("routes", intersectionQuery, idFieldOnly, routes -> {
						if (routes.result() != null && !routes.result().isEmpty()) {
							TrafficIncident trafficIncident = gson.fromJson(incidentJson.toString(),
									TrafficIncident.class);
							List<String> routeIds = routes.result().stream().map(c -> c.getString("_id"))
									.collect(Collectors.toList());
							simulation.addTrafficIncident(trafficIncident, routeIds);
						}
					});
				}
			}
		});
	}

	private JsonObject buildIntersectionQuery(JsonObject traffic, String simId) {
		JsonObject startGeometry = new JsonObject().put("$geometry", traffic.getJsonObject("start"));
		JsonObject endGeometry = new JsonObject().put("$geometry", traffic.getJsonObject("end"));
		JsonObject intersectsStartAndEnd = new JsonObject().put("$geoIntersects", startGeometry).put("$geoIntersects",
				endGeometry);
        return new JsonObject().put("segments", intersectsStartAndEnd).put("simulation", simId);
	}

	/**
	 * Sets the status of the simulation so that all local verticles can see it.
	 * 
	 * @param simulationId
	 * @param status
	 */
	private void setRunningStatus(String simulationId, boolean status) {
		simulationStatus.put(simulationId, status);
	}

	private boolean isSimulationRunning(String simulationId) {
		Boolean isRunning = simulationStatus.get(simulationId);
		return isRunning != null && isRunning;
	}

}
