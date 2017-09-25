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
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.sql.SQLConnection;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Configuration (the database entry is omitted it will run but not do any persistence of data):
 *
 * <ul>
 * <li><b>event_bus : "home.heatpump.data"</b> -- Eventbus address</li>
 * <li><b>database :</b>
 *    <ul>
 *        <li><b>url : "jdbc:mariadb://server:3306/mydb"</b></li>
 *        <li><b>user : "username"</b></li>
 *        <li><b>password : "password"</b></li>
 *        <li><b>driver_class : "org.mariadb.jdbc.Driver"</b> -- or other jdbc drives such as com.mysql.jdbc.Driver</li>
 *        <li><b>max_pool_size : 10</b> -- max. size of the connection pool, defaults to 10</li>
 *    </ul>
 * </li>
 *
 * @author Claus Priisholm.
 */
public class LoggerVerticle extends AbstractVerticle {

  private final SimpleDateFormat timestampFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
  private final Logger LOG = LoggerFactory.getLogger(this.getClass());

  private String eventBusAddress;
  private JDBCClient client = null;

  private static final String SQL_TEMP = "insert into pump_temperature (ts, sensor, data) values (?, ?, ?)";
  private static final String SQL_POWR = "insert into pump_current (ts, sensor, data) values (?, ?, ?)";

  @Override
  public void start() {

    if(LOG.isDebugEnabled())
      LOG.debug("LoggerVerticle starting with config: " + config().encodePrettily());

    eventBusAddress = config().getString("event_bus","home.heatpump.playback");

    // if no database config, this simply print events to stdout
    JsonObject databaseConfig = config().getJsonObject("database");
    if(databaseConfig != null) {
      client = JDBCClient.createShared(vertx, new JsonObject()
        .put("url", databaseConfig.getString("url"))
        .put("user", databaseConfig.getString("user"))
        .put("password", databaseConfig.getString("password"))
        .put("driver_class", databaseConfig.getString("driver_class"))
        .put("max_pool_size", databaseConfig.getInteger("pool_size", 10))
      );
    }

    EventBus eventBus = vertx.eventBus();

    eventBus.consumer(eventBusAddress, message -> {
      // If there is a database client store the values, otherwise just print the message
      if(client != null) {
        if(LOG.isDebugEnabled())
          LOG.debug("Event bus message @" + new Date() + ": " + message.body());

        JsonArray dataSet = new JsonArray(message.body().toString());

        saveData(dataSet).setHandler(ar -> {
          if (ar.failed()) {
            LOG.error("saveData() failed: " + ar.cause().getMessage());
          } else {
            if (LOG.isTraceEnabled())
              LOG.trace(dataSet);
          }
        });
      } else {
        System.out.println("Logger received @" + new Date() + ": " + message.body());
      }
    });

    LOG.info("LoggerVerticle started - " + (client == null ? "just logging" : "with persistence") + ", listens on " + eventBusAddress);
  }

  @Override
  public void stop() {
    LOG.info("LoggerVerticle stopped listening on " + eventBusAddress);
  }


  Future<Void> saveData(JsonArray dataSet) {
    System.out.println("XXX");
    return saveData(dataSet, client);
  }
  /**
   * Set the timestamp and create a batch insert
   */
  Future<Void> saveData(JsonArray dataSet, JDBCClient client) {
    System.out.println("ENRTY");

    Future<Void> future = Future.future();

    System.out.println("ENRTY2");

    String ts = timestampFormatter.format(new Date());

    // Array with JSON objects like {"t":"temp","g":"fyr","s":"pump","d":19.6}

    List<JsonArray> tempParams = dataSet.stream()
      .filter(data -> "temp".equals(((JsonObject)data).getString("t")))
      .map(data -> new JsonArray()
        .add(ts)
        .add(((JsonObject)data).getString("s"))
        .add(((JsonObject)data).getDouble("d")) )
      .collect(Collectors.toList());

    List<JsonArray> powrParams = dataSet.stream()
      .filter(data -> "powr".equals(((JsonObject)data).getString("t")))
      .map(data -> new JsonArray()
        .add(ts)
        .add(((JsonObject)data).getString("s"))
        .add(((JsonObject)data).getDouble("d")) )
      .collect(Collectors.toList());

    System.out.println("START");
    client.getConnection( arConnection -> {
      if (arConnection.failed()) {
        LOG.error(arConnection.cause().getMessage());
        future.fail(arConnection.cause().getMessage());
      } else {
        System.out.println("GET CONN");

        SQLConnection connection = arConnection.result();
        // If rows for both tables do them nested, else just do the one that has rows
        if(tempParams.size() > 0 && powrParams.size() > 0) {
          connection.batchWithParams(SQL_TEMP, tempParams, arBatchTempResult -> {
            if (arBatchTempResult.failed()) {
              future.fail("Batch temp insert failed - " + arBatchTempResult.cause().getMessage());
              LOG.error("Batch temp insert failed - " + arBatchTempResult.cause().getMessage());
              arBatchTempResult.cause().printStackTrace();
              connection.close();
            } else {
              connection.batchWithParams(SQL_POWR, powrParams, arBatchPowrResult -> {
                if (arBatchPowrResult.failed()) {
                  future.fail("Batch powr insert failed - " + arBatchPowrResult.cause().getMessage());
                  LOG.error("Batch powr insert failed - " + arBatchPowrResult.cause().getMessage());
                  arBatchPowrResult.cause().printStackTrace();
                  connection.close();
                } else {
                  connection.close( done -> {
                    if (done.failed()) {
                      throw new java.lang.RuntimeException(done.cause());
                    }
                  });
                  System.out.println("COPLETE 1");

                  future.complete();
                }
              });
            }
          });
        }
        else if(tempParams.size() > 0) {
          connection.batchWithParams(SQL_TEMP, tempParams, arBatchResult -> {
            if (arBatchResult.failed()) {
              future.fail("Batch temp insert failed - " + arBatchResult.cause().getMessage());
              LOG.error("Batch temp insert failed - " + arBatchResult.cause().getMessage());
              arBatchResult.cause().printStackTrace();
              connection.close();
            } else {
              connection.close( done -> {
                if (done.failed()) {
                  throw new java.lang.RuntimeException(done.cause());
                }
              });
              future.complete();
            }
          });
        }
        else { // powrParams.size() > 0
          connection.batchWithParams(SQL_POWR, powrParams, arBatchResult -> {
            if (arBatchResult.failed()) {
              future.fail("Batch powr insert failed - " + arBatchResult.cause().getMessage());
              LOG.error("Batch powr insert failed - " + arBatchResult.cause().getMessage());
              arBatchResult.cause().printStackTrace();
              connection.close();
            } else {
              connection.close( done -> {
                if (done.failed()) {
                  throw new java.lang.RuntimeException(done.cause());
                }
              });
              future.complete();
            }
          });
        }
      }
    }); // get connection

    return future;
  }

}
