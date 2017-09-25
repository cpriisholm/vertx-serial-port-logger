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
import io.vertx.core.json.JsonArray
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.AsyncResult
import io.vertx.ext.jdbc.JDBCClient
import io.vertx.ext.sql.SQLConnection
import java.text.SimpleDateFormat
import io.vertx.core.logging.Logger
import io.vertx.core.logging.LoggerFactory

/**
 * Prototype script for replaying existing data from database.
 * Mimics the SerialVerticle by publishing JSON messages at given interval (normally 10 sec.)
 *
 * Run script like:
 * CLASSPATH=".:some-jdbc-connector.jar" vertx run groovy:ReplayVerticle.groovy -cluster
 */


@Field SimpleDateFormat timestampFormatter = new SimpleDateFormat('yyyy-MM-dd HH:mm:ss')

@Field String address = 'home.heatpump.test'
@Field String startTime = '2017-01-16 00:00:00' // Provided test data is from this day
@Field int interval = 10 // set to 1 for a 10x speed up of replay
@Field long time // set to startTime and then incremented by 'interval' during the timer callback
@Field def client = null

println "*** ReplayVerticle starting"

time = timestampFormatter.parse(startTime).getTime()

client = JDBCClient.createShared(vertx, [
  url          : 'jdbc:mariadb://localhost:3306/forbrug_test',
  driver_class : 'org.mariadb.jdbc.Driver',
  user         : 'test',
  password     : '',
  max_pool_size: 10
])

def eventBus = vertx.eventBus()

long timerID = vertx.setPeriodic(interval * 1000L) { tid ->
  // getNextData provides the dataset that is used as the message for the event bus (json in this case)
  getNextData() { arDataSet ->
    if (arDataSet.failed()) {
      println "*** Error - getNextData() failed: ${arDataSet.cause().message}"
    } else {
      def dataSet = arDataSet.result
      println dataSet
      eventBus.publish(address, groovy.json.JsonOutput.toJson(dataSet))
    }
  }
}

println "*** ReplayVerticle started, publishing @ '${address}'"


//
// This payload is what we distribute on the eventBus:
//
// [
//   { "t":"temp", "g":"fyr", "s":"pump", "d":13.5" },
//   { "t":"temp", "g":"fyr", "s":"feed", "d":13.5" },
//   { "t":"temp", "g":"fyr", "s":"ret", "d":13.5" },
//   { "t":"temp", "g":"fyr", "s":"box", "d":13.5" },
//   { "t":"temp", "g":"bry", "s":"tank", "d":13.5" },
//   { "t":"powr", "g":"pump", "s":"l1", "d":10.9" },
//   { "t":"powr", "g":"pump", "s":"l2", "d":2.3" },
//   { "t":"powr", "g":"pump", "s":"l3", "d":2.3" }
// ]
//
// CSV files have been exported like this:
// select ts,sensor,data into outfile '.../pump_current.csv'
//        fields terminated by ',' optionally enclosed by '"' lines terminated by '\n' from pump_current;
// So to import do something similar for your db - e.g. for mariadb:
// load data infile 'pump_current.csv' into table pump_current
//        fields terminated by ',' optionally enclosed by '"' lines terminated by '\n';
//

@Field final String SQL_TEMP = 'select sensor, data from pump_temperature where ts >= ? and ts < ?'
@Field final String SQL_POWR = 'select sensor, data from pump_current where ts >= ? and ts < ?'

/**
 * Gets a set of data as shown above based on the "next 10 seconds", formats data to mimic
 * the serial output from the arduino program.
 */
void getNextData(Handler<AsyncResult<JsonArray>> handler) {
  Future<JsonArray> future = Future.future().setHandler(handler)

  // Note, the interval may be set to less than 10 seconds, but the actual recorded data is still based on
  // 10 seconds implemented in the hardware so we still need to increment the "time" by 10 seconds (and
  // thus effectively speed up the replay by x10 if interval is set to 1 second
  def from = timestampFormatter.format(new Date(time))
  time += 10000L
  def to = timestampFormatter.format(new Date(time))

  client.getConnection() { arConnection ->
    if (arConnection.failed()) {
      println "Error: ${arConnection.cause().message}"
      future.fail(arConnection.cause().message)
    } else {
      SQLConnection connection = arConnection.result()

      // First query temps and then power - most likely only on set of measurements in the interval,
      // if not the case some 'random' combination is returned
      connection.queryWithParams(SQL_TEMP, [ from, to ]) { arTempsResult ->
        if (arTempsResult.succeeded()) {

          def dataSet = []

          arTempsResult.result().results.each { line ->
            switch(line[0]) {
              case 'box':
                dataSet << [ 't':'temp', 'g':'fyr', 's':'box', 'd':line[1] ]
                break
              case 'feed':
                dataSet << [ 't':'temp', 'g':'fyr', 's':'feed', 'd':line[1] ]
                break
              case 'pump':
                dataSet << [ 't':'temp', 'g':'fyr', 's':'pump', 'd':line[1] ]
                break
              case 'ret':
                dataSet << [ 't':'temp', 'g':'fyr', 's':'ret', 'd':line[1] ]
                break
              case 'tank':
                dataSet << [ 't':'temp', 'g':'bry', 's':'tank', 'd':line[1] ]
                break;
            }
          }

          connection.queryWithParams(SQL_POWR, [ from, to ]) { arPowrsResult ->
            if (arPowrsResult.succeeded()) {

              arPowrsResult.result().results.each { line ->
                // Old data may not have l2 and l3 explicitly so we ignore them entirely, and
                // then calculates them same way as the controller does
                switch(line[0]) {
                  case 'l1':
                    float amps = line[1];
                    if(amps > 14.0) {
                      dataSet << ['t': 'powr', 'g': 'pump', 's': 'l1', 'd': amps]
                      dataSet << ['t': 'powr', 'g': 'pump', 's': 'l2', 'd': amps-8.6]
                      dataSet << ['t': 'powr', 'g': 'pump', 's': 'l3', 'd': amps-8.6]
                    } else {
                      dataSet << ['t': 'powr', 'g': 'pump', 's': 'l1', 'd': amps]
                      dataSet << ['t': 'powr', 'g': 'pump', 's': 'l2', 'd': amps]
                      dataSet << ['t': 'powr', 'g': 'pump', 's': 'l3', 'd': amps]
                    }
                    break;
                }
              }
              connection.close() { done ->
                if (done.failed()) {
                  throw new java.lang.RuntimeException(done.cause())
                }
              }

              future.complete(dataSet)

            } else {
              future.fail("Select - ${SQL_POWR} - ${arPowrsResult.cause().message}")
              println "Error select - ${SQL_POWR} - ${arPowrsResult.cause().message}"
              arPowrsResult.cause().printStackTrace()
              connection.close()
            }
          } // query powr
        } else {
          future.fail("Select - ${SQL_TEMP} - ${arTempsResult.cause().message}")
          println "Select - ${SQL_TEMP} - ${arTempsResult.cause().message}"
          arTempsResult.cause().printStackTrace()
          connection.close()
        }
      } // query temps
    }
  } // get connection
}
