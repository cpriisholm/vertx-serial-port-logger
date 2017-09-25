/*
 * Copyright (c) 2017 CodeDroids ApS (http://www.codedroids.dk)
 *
 * This file is part of the HeatPump Vert.x example
 *
 * The HeatPump Vert.x example is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * The HeatPump Vert.x example is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the HeatPump Vert.x example. If not, see <http://www.gnu.org/licenses/>.
 */
package dk.codedroids.home.heatpump;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.mqtt.MqttServer;
import io.vertx.mqtt.MqttServerOptions;

import java.text.SimpleDateFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ideally this should not act as a broker, but since there is no broker at the moment, it does
 * act as one rather than subscribing to an external broker. Another hack is that the thermo motor
 * is reported as a temperatur with 0.0 when the motor is off, and 10.0 when the motor is on.
 *
 * In any case it relavant topics and transforms converts the data into JSON array like this:</p>
 *
 * <pre>{@code [
 *   { "t":"temp", "g":"stue", "s":"room", "d":22.5" },
 *   { "t":"temp", "g":"stue", "s":"thmo", "d":10.0" }
 * ]}</pre>
 *
 * <p>and publishes it on the event bus.</p>
 **
 * <p></p>Configuration (the database entry is omitted it will run but not do any persistence of data):</p>
 *
 * <ul>
 * <li><b>event_bus : "home.heatpump.data"</b> -- Eventbus address</li>
 * <li><b>topics : "stue/(thermomotor|temperature)"</b> -- topics it listen for, reg.ex. which defaults to ".*"</li>
 * <li><b>verbose : true | false</b> -- If true serial data is printed to stdout, default to false
 * <li><b>server :</b> -- If not provided it defaults to the default shown below, if provided all values must be defined:
 *    <ul>
 *        <li><b>port : 1883</b> -- "broker" listens on this port, default to 1883</li>
 *        <li><b>host : "0.0.0.0"</b> -- host, defaults to "0.0.0.0"</li>
 *    </ul>
 * </li>*
 * </ul>
 *
 * @author Claus Priisholm.
 */
public class MqttVerticle extends AbstractVerticle {

  private final SimpleDateFormat timestampFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
  private final Logger LOG = LoggerFactory.getLogger(this.getClass());

  private String eventBusAddress;
  private Pattern topic;
  private JDBCClient client = null;

  @Override
  public void start() {
    if(LOG.isDebugEnabled())
      LOG.debug("SerialVerticle starting with config: " + config().encodePrettily());

    JsonObject serverConfig = config().getJsonObject("server");
    if (serverConfig == null) {
      serverConfig = new JsonObject().put("port", 1883).put("host", "0.0.0.0");
    }

    eventBusAddress = config().getString("event_bus", "home.heatpump.data");
    String topicStr = config().getString("topic", ".*");
    topic = Pattern.compile(topicStr);
    boolean verbose = config().getBoolean("verbose",false);

    final EventBus eventBus = vertx.eventBus();

    MqttServerOptions options = new MqttServerOptions(serverConfig);
    MqttServer server = MqttServer.create(vertx, options);

    server.endpointHandler(endpoint -> {

      if(LOG.isDebugEnabled())
        LOG.debug("connected client " + endpoint.clientIdentifier());

      endpoint.publishHandler(message -> {

        if(LOG.isTraceEnabled())
          LOG.trace("Received message on [" + message.topicName() +"] payload [" + message.payload() +"] with QoS ["+ message.qosLevel() +"]");

        String payload = message.payload().toString(); // only string messages

        if (verbose)
          System.out.println(payload);

        Matcher m = topic.matcher( message.topicName());
        if(m.matches()) {
          JsonArray dataSet = new JsonArray();

          // Hard coded logic based on sensors
          switch(message.topicName()) {
            case "stue/thermomotor":
              int val = "1".equals(payload) ? 10 : 0; // Hack to get it into the temp graph
              dataSet.add(new JsonObject().put("t", "temp").put("g", "stue").put("s", "thmo").put("d", val));
              break;
            case "stue/temperature":
              try {
                double t = Double.parseDouble(payload);
                dataSet.add(new JsonObject().put("t", "temp").put("g", "stue").put("s", "room").put("d", t));
              }
              catch(NumberFormatException e) {
                LOG.error("Mqqt topic 'stue/temperature' data contained non-numeric value: \"" + payload+ "\"", e);
              }
              break;
          }

          eventBus.publish(eventBusAddress, dataSet.encode());
        }

      });

      endpoint.accept(false);
    });

    server.listen(ar -> {
      if (ar.succeeded()) {
        LOG.debug("MQTT server started and listening on port ${server.actualPort()}");
      } else {
        System.err.println("MQTT server error on start${ar.cause().getMessage()}");
      }
    });

    LOG.info("Mqqt verticle started, listens for topic(s): " + topicStr + " and publish on " + eventBusAddress);
  }

  @Override
  public void stop() {
    LOG.info("Mqtt verticle stopping");
  }

}
