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
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.AsyncResult;
import io.vertx.core.json.JsonObject;
import io.vertx.core.parsetools.RecordParser;
import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.sql.SQLConnection;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;

import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

/**
 * Intended for replaying recorded data from the database or from a text file. Mostly for testing.
 * <p>
 *     The configuration must define either a <b>database</b> or a <b>text_file</b>.
 * </p>
 * Configuration :
 *
 * <ul>
 * <li><b>event_bus : "home.heatpump.data"</b> -- Eventbus address</li>
 * <li><b>sample_interval : 10</b> -- Number of seconds between events - defaults to 10 seconds which is the actually sampling interval used by the microcontroller.</li>
 * <li><b>start_time : "2017-01-01 00:00:00"</b> -- Start replay from the given timestamp (defaults to "2017-01-01 00:00:00")</li>
 * <li><b>database :</b>
 *    <ul>
 *        <li><b>url : "jdbc:mariadb://server:3306/mydb"</b></li>
 *        <li><b>user : "username"</b></li>
 *        <li><b>password : "password"</b></li>
 *        <li><b>driver_class : "org.mariadb.jdbc.Driver"</b> -- or other jdbc drives such as com.mysql.jdbc.Driver</li>
 *        <li><b>max_pool_size : 10</b> -- max. size of the connection pool, defaults to 10</li>
 *    </ul>
 * </li>
 * <li><b>text_file : "/tmp/records.txt"</b> -- file with one message per line (send to event bus as is)</li>
 * </ul>
 *
 * @author Claus Priisholm.
 */
public class ReplayVerticle extends AbstractVerticle {


  private final String SQL_TEMP = "select sensor, data from pump_temperature where ts >= ? and ts < ?";
  private final String SQL_POWR = "select sensor, data from pump_current where ts >= ? and ts < ?";

  private final SimpleDateFormat timestampFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

  private final Logger LOG = LoggerFactory.getLogger(this.getClass());

  private String eventBusAddress;
  private String startTime;
  private int sampleInterval;
  private long time; // set to startTime and then incremented by 'interval' during the timer callback
  private JDBCClient client = null;
  private BufferedReader textFileReader = null;

  private long timerID;

  @Override
  public void start() {

    if (LOG.isDebugEnabled())
      LOG.debug("ReplayVerticle starting with config: " + config().encodePrettily());

    JsonObject databaseConfig = config().getJsonObject("database");
    String textFile = config().getString("text_file");
    if (databaseConfig == null && textFile == null)
      throw new IllegalArgumentException("Invalid configuration, either 'database' or 'text_file' is missing for ReplayVerticle");
    else if (databaseConfig != null && textFile != null)
      throw new IllegalArgumentException("Invalid configuration, only one of 'database' or 'text_file' must be given for ReplayVerticle");

    eventBusAddress = config().getString("event_bus", "home.heatpump.playback");
    startTime = config().getString("start_time", "2017-01-01 00:00:00");
    sampleInterval = config().getInteger("sample_interval", 10);
    try {
      time = timestampFormatter.parse(startTime).getTime();
    } catch (java.text.ParseException e) {
      throw new RuntimeException("Failed to parse start_time: " + startTime, e);
    }

    EventBus eventBus = vertx.eventBus();

    if(databaseConfig != null) {
      client = JDBCClient.createShared(vertx, new JsonObject()
        .put("url", databaseConfig.getString("url"))
        .put("user", databaseConfig.getString("user"))
        .put("password", databaseConfig.getString("password"))
        .put("driver_class", databaseConfig.getString("driver_class"))
        .put("max_pool_size", databaseConfig.getInteger("pool_size", 10))
      );
      timerID = vertx.setPeriodic(sampleInterval * 1000L,  tid -> {
        getNextData( arDataSet -> {
          if (arDataSet.failed()) {
            LOG.error("getNextData() failed: " + arDataSet.cause().getMessage());
          } else {
            JsonArray dataSet = arDataSet.result();
            if (LOG.isTraceEnabled())
             LOG.trace(dataSet);
            eventBus.publish(eventBusAddress, dataSet.encode());
          }
        });
      });
    }

    if(textFile != null) {
      // Since it is kind of setup we take a chance and open the file even though it is blocking
      Path textFilePath = FileSystems.getDefault().getPath(textFile);
      try {
        textFileReader = Files.newBufferedReader(textFilePath);

        timerID = vertx.setPeriodic(sampleInterval * 1000L,  tid -> {
          vertx.executeBlocking((Future<String> future) -> {
            try {
              future.complete(textFileReader.readLine());
            } catch(IOException e) {
              future.fail(e);
            }
          }, arTextLine -> {
            if (arTextLine.succeeded()) {
              String dataLine = arTextLine.result();
              if (LOG.isTraceEnabled())
                LOG.trace(dataLine);
              if(dataLine!=null) {
                eventBus.publish(eventBusAddress, dataLine);
              } else {
                LOG.info("Read to end of file, stopped sending to the event bus");
                vertx.cancelTimer(timerID);
              }
            } else {
              LOG.error("Failed to read line: " + arTextLine.cause().getMessage());
            }
          });
        });

      } catch(IOException e) {
        LOG.error("Failed to open \""+textFilePath.toAbsolutePath()+"\": " + e.getMessage());
      }
    }

    LOG.info("ReplayVerticle started, publishing to '" + eventBusAddress + "'");
  }

  @Override
  public void stop() {
    vertx.cancelTimer(timerID);
    if(textFileReader != null) {
      try {
        textFileReader.close();
      } catch (IOException e) {
      }
    }
    if(client != null)
      client.close();
    LOG.info("ReplayVerticle stopped publishing to '" + eventBusAddress + "'");
  }

  /**
   * Gets a set of data as shown above based on the "next 10 seconds"
   */
  void getNextData(Handler<AsyncResult<JsonArray>> handler) {
    Future<JsonArray> future = Future.<JsonArray>future().setHandler(handler);

    // Note, the interval may be set to less than 10 seconds, but the actual recorded data is still based on
    // 10 seconds implemented in the hardware so we still need to increment the "time" by 10 seconds (and
    // thus effectively speed up the replay by x10 if interval is set to 1 second
    String from = timestampFormatter.format(new Date(time));
    time += 10000L;
    String to = timestampFormatter.format(new Date(time));

    client.getConnection( arConnection -> {
      if (arConnection.failed()) {
        LOG.error(arConnection.cause().getMessage());
        future.fail(arConnection.cause().getMessage());
      } else {
        SQLConnection connection = arConnection.result();

        // First query temps and then power - most likely only on set of measurements in the interval,
        // if not the case some 'random' combination is returned
        JsonArray periodParams = new JsonArray().add(from).add(to);
        connection.queryWithParams(SQL_TEMP, periodParams,  arTempsResult -> {
          if (arTempsResult.succeeded()) {

            JsonArray dataSet = new JsonArray();

            arTempsResult.result().getResults().forEach ( (JsonArray line) -> {
              try {
                Sensor sensor = Sensor.valueOf(line.getString(0));
                dataSet.add(new JsonObject().put("t", sensor.type()).put("g", sensor.group()).put("s", sensor).put("d", line.getFloat(1)));
              } catch(IllegalArgumentException e) {
                LOG.error("ReplayVerticle unknown sensor: '" + line.getString(0) + "'", e);
              }
            });

            connection.queryWithParams(SQL_POWR, periodParams, arPowrsResult -> {
              if (arPowrsResult.succeeded()) {

                arPowrsResult.result().getResults().forEach ( (JsonArray line) -> {
                  try {
                    Sensor sensor = Sensor.valueOf(line.getString(0));
                    dataSet.add(new JsonObject().put("t", sensor.type()).put("g", sensor.group()).put("s", sensor).put("d", line.getFloat(1)));
                    // For a period the database does not contain l2 and l3, we apply the same "hack" as on the controller
                    // here and ignore l2 and l3 if actually in the db
                    switch(sensor) {
                      case l1:
                        float amps = line.getFloat(1);
                        if(amps > 14.0) {
                          dataSet.add(new JsonObject().put("t", sensor.type()).put("g", sensor.group()).put("s", sensor.name()).put("d", amps));
                          dataSet.add(new JsonObject().put("t", sensor.type()).put("g", sensor.group()).put("s", Sensor.l2.name()).put("d", amps-8.6));
                          dataSet.add(new JsonObject().put("t", sensor.type()).put("g", sensor.group()).put("s", Sensor.l3.name()).put("d", amps-8.6));
                        } else {
                          dataSet.add(new JsonObject().put("t", sensor.type()).put("g", sensor.group()).put("s", sensor.name()).put("d", amps));
                          dataSet.add(new JsonObject().put("t", sensor.type()).put("g", sensor.group()).put("s", Sensor.l2.name()).put("d", amps));
                          dataSet.add(new JsonObject().put("t", sensor.type()).put("g", sensor.group()).put("s", Sensor.l3.name()).put("d", amps));

                        }
                        break;
                    }
                  } catch(IllegalArgumentException e) {
                    LOG.error("ReplayVerticle unknown sensor: '" + line.getString(0) + "'", e);
                  }
                });

                connection.close( done -> {
                  if (done.failed()) {
                    throw new java.lang.RuntimeException(done.cause());
                  }
                });

                // Hmm, doing new JsonArray(dataSet) does not turn it into a JsonArray anyway, must be the groovy stuff kicking in
                future.complete(dataSet);

              } else {
                future.fail("Select - " + SQL_POWR + " - " + arPowrsResult.cause().getMessage());
                LOG.error("Select - " + SQL_POWR + " - " + arPowrsResult.cause().getMessage());
                arPowrsResult.cause().printStackTrace();
                connection.close();
              }
            }); // query powr
          } else {
            future.fail("Select - " + SQL_TEMP + " - " + arTempsResult.cause().getMessage());
            LOG.error("Select - " + SQL_TEMP + " - " + arTempsResult.cause().getMessage());
            arTempsResult.cause().printStackTrace();
            connection.close();
          }
        }); // query temps
      }
    }); // get connection
  }
}
