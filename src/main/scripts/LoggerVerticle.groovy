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
import groovy.transform.Field
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.Logger
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.jdbc.JDBCClient
import io.vertx.ext.sql.SQLConnection

import java.text.SimpleDateFormat

/**
 *
 * Prototype script that listens for messages on the event bus and logs them to stdout and, if
 * a database client is defined, stores data to sql database
 *
 * Run script like:
 * CLASSPATH=".:some-jdbc-connector.jar" vertx run groovy:LoggerVerticle.groovy -cluster -cluster-host 127.0.0.1
 */

@Field final SimpleDateFormat timestampFormatter = new SimpleDateFormat('yyyy-MM-dd HH:mm:ss')

@Field address = 'home.heatpump.test'
@Field def client = null

println "LoggerVerticle starting"

/* uncomment to store the data
client = JDBCClient.createShared(vertx, [
  url          : 'jdbc:mariadb://localhost:3306/forbrug_test',
  driver_class : 'org.mariadb.jdbc.Driver',
  user         : 'test',
  password     : '',
  max_pool_size: 10
])
*/

def eventBus = vertx.eventBus()

eventBus.consumer(address) { message ->
  println "@${new Date()}: ${message.body()}"
  // If client is given, store the data
  if(client) {
    def dataSet = new JsonArray(message.body())

    saveData(dataSet) { ar ->
      if (ar.failed()) {
        println "Error saveData() failed: ${ar.cause().message}"
      }
    }
  }
}

println "LoggerVerticle started - ${client == null ? 'just logging' : 'with persistence'}, listens @ ${address}"


//
// The message on the event bus is described in the ReplayVerticle, here we assume that it is
// valid and then stores the values in the database
//

@Field final String SQL_TEMP = 'insert into pump_temperature (ts, sensor, data) values (?, ?, ?)'
@Field final String SQL_POWR = 'insert into pump_current (ts, sensor, data) values (?, ?, ?)'

/**
 * Set the timestamp and create a batch insert
 */
void saveData(JsonArray dataSet, Handler<AsyncResult<Void>> handler) {
  Future<JsonArray> future = Future.future().setHandler(handler)

  def ts = timestampFormatter.format(new Date())

  def tempParams = []
  def powrParams = []

  // Array with JSON objects like {"t":"temp","g":"fyr","s":"pump","d":19.6}
  dataSet.each { JsonObject data ->
    switch(data.getString('t')) {
      case 'temp':
        tempParams << [ ts, data.getString('s'), data.getDouble('d') ]
        break
      case 'powr':
        powrParams << [ ts, data.getString('s'), data.getDouble('d') ]
        break
      default:
        println "*** Invalid data format received: ${data.encode()}"
    }
  }

  client.getConnection() { arConnection ->
    if (arConnection.failed()) {
      future.fail(arConnection.cause().message)
    } else {
      SQLConnection connection = arConnection.result()

      // If rows for both tables do them nested, else just do the one that has rows
      if(tempParams.size() > 0 && powrParams.size() > 0) {
        connection.batchWithParams(SQL_TEMP, tempParams) { arBatchTempResult ->
          if (arBatchTempResult.failed()) {
            future.fail("Batch temp insert failed - ${arBatchTempResult.cause().message}")
            arBatchTempResult.cause().printStackTrace()
            connection.close()
          } else {
            connection.batchWithParams(SQL_POWR, powrParams) { arBatchPowrResult ->
              if (arBatchPowrResult.failed()) {
                future.fail("Batch powr insert failed - ${arBatchPowrResult.cause().message}")
                arBatchPowrResult.cause().printStackTrace()
                connection.close()
              } else {
                connection.close() { done ->
                  if (done.failed()) {
                    throw new java.lang.RuntimeException(done.cause())
                  }
                }
                future.complete()
              }
            }
          }
        }
      }
      else if(tempParams.size() > 0) {
        connection.batchWithParams(SQL_TEMP, tempParams) { arBatchResult ->
          if (arBatchResult.failed()) {
            future.fail("Batch temp insert failed - ${arBatchResult.cause().message}")
            arBatchResult.cause().printStackTrace()
            connection.close()
          } else {
            connection.close() { done ->
              if (done.failed()) {
                throw new java.lang.RuntimeException(done.cause())
              }
            }
            future.complete()
          }
        }
      }
      else { // powrParams.size() > 0
        connection.batchWithParams(SQL_POWR, powrParams) { arBatchResult ->
          if (arBatchResult.failed()) {
            future.fail("Batch powr insert failed - ${arBatchResult.cause().message}")
            arBatchResult.cause().printStackTrace()
            connection.close()
          } else {
            connection.close() { done ->
              if (done.failed()) {
                throw new java.lang.RuntimeException(done.cause())
              }
            }
            future.complete()
          }
        }
      }
    }
  } // get connection
}
