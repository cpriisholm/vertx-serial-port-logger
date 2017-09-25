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

/**
 * Reads the input from the serial port, assuming a CSV formatted line like this:
 *
 * <p>{@code PumpTemp,FeedTemp,ReturnTemp,BoardTemp,TankTemp,PumpL1Current,PumpL2Current,PumpL3Current,CRC}</p>
 *
 * <p>and converts the data into JSON array like this:</p>
 *
 * <pre>{@code [
 *   { "t":"temp", "g":"fyr", "s":"pump", "d":13.5" },
 *   { "t":"temp", "g":"fyr", "s":"feed", "d":13.5" },
 *   { "t":"temp", "g":"fyr", "s":"ret", "d":13.5" },
 *   { "t":"temp", "g":"fyr", "s":"box", "d":13.5" },
 *   { "t":"temp", "g":"bry", "s":"tank", "d":13.5" },
 *   { "t":"powr", "g":"pump", "s":"l1", "d":10.9" },
 *   { "t":"powr", "g":"pump", "s":"l2", "d":2.3" },
 *   { "t":"powr", "g":"pump", "s":"l3", "d":2.3" }
 * ]}</pre>
 *
 * and publishes it on the event bus.
 *
 * <p>Configuration:</p>
 *
 * <ul>
 * <li><b>event_bus : "home.heatpump.data"</b> -- Eventbus address</li>
 * <li><b>serial_port : "/dev/ttyACM0"</b> -- Serial port to listen on</li>
 * <li><b>echo : true | false</b> -- if true data (without CRC column) is echoed to the bus
 *                                    (using the event_bus address with ".echo" appended)
 * <li><b>verbose : true | false</b> -- If true serial data is printed to stdout, default to false
 * </ul>
 *
 * @author Claus Priisholm.
 */
public class SerialVerticle extends AbstractVerticle {

  private static final Logger LOG = LoggerFactory.getLogger(SerialVerticle.class);

  private String eventBusAddress;
  private String echoEventBusAddress;
  private SerialInput serialWrapper;

  /** To allow for explicit wrapper to be injected, used for testing */
  SerialVerticle(SerialInput serialWrapper) {
    super();
    this.serialWrapper = serialWrapper;
  }

  @Override
  public void start() {

    if(LOG.isDebugEnabled())
      LOG.debug("SerialVerticle starting with config: " + config().encodePrettily());

    eventBusAddress = config().getString("event_bus", "home.heatpump.data");
    echoEventBusAddress = eventBusAddress + ".echo";
    String devicePath = config().getString("serial_port","/dev/ttyACM0");
    boolean echo = config().getBoolean("echo",false);
    boolean verbose = config().getBoolean("verbose",false);

    final EventBus eventBus = vertx.eventBus();

    // Typically this is not provided (unless under test)
    if(serialWrapper==null)
      serialWrapper = new SerialWrapper.Builder(devicePath).build();

    serialWrapper.setHandler(inputLine -> {
      if (verbose)
        System.out.println(inputLine);
      if(inputLine != null) {
        String data = verifiedInput(inputLine);
        if (data != null) {
          if (eventBus != null) {
            JsonArray json = jsonFromCsv(data);
            if (json != null)
              eventBus.publish(eventBusAddress, json.encode());
            if (echo)
              eventBus.publish(echoEventBusAddress, data); // use the stripped
          }
        }
      }
    });

    LOG.info("Heater serial verticle started, publish on " + eventBusAddress);
  }

  @Override
  public void stop() {
    LOG.info("Heater serial verticle stopping");
    serialWrapper.close();
  }

  /**
   * This wraps knowledge about what data is sent from the arduino controller, changes to the number and order of
   * columns must be reflected here. See class description for the format.
   *
   * @param data CSV string with the values in the order described above
   * @return Json array, or if any error occurred with data, null
   */
  protected JsonArray jsonFromCsv(String data) {
    assert data != null;

    String[] columns = data.split(",");
    // We expect a certain number columns, return null if that is not the case
    int expectedNumberOfCols = Sensor.values().length;
    if(columns.length != expectedNumberOfCols) {
      // If it passed CRC test and still get into this part, it is an error
      LOG.error("Serial input data contained the wrong number of columns (expected " +expectedNumberOfCols+ "): \"" + data+ "\"");
      return null;
    }
    try {
      JsonArray arr = new JsonArray();
      for(Sensor s : Sensor.values()) {
        String col = columns[s.order()-1];
        // all columns are numbers so we just need to convert them to doubles
        JsonObject measurement = new JsonObject();
        measurement.put("t", s.type());
        measurement.put("g", s.group());
        measurement.put("s", s.name());
        measurement.put("d", Double.parseDouble(col));
        arr.add(measurement);
      }
      return arr;
    }
    catch(NumberFormatException e) {
      // In case of an error consider the whole input corrupted:
      LOG.error("Serial input data contained non-numeric value: \"" + data+ "\"", e);
      return null;
    }
  }

  /**
   * This validates the CRC of the input and returns the input without the CRC part
   * @param input CSV string where the last column is the CRC of the bytes of the rest of the string
   * @return The input minus the CRC column, or null if CRC check failed
   */
  protected String verifiedInput(String input) {
    assert input != null;

    String retval = null;
    int mark = input.lastIndexOf(",");
    if(mark != -1) {
      try {
        int given = Integer.parseInt(input.substring(mark + 1));
        String data = input.substring(0, mark);
        int crc = crc8(data.getBytes());
        retval = (given == crc) ? data : null;
      }
      catch(NumberFormatException e) {
        LOG.error("Serial input data CRC not a number: \"" + input+ "\"", e);
      }
    }

    // This may happen during restart and other situations, kind of expected
    // hence it is not logged as an error
    if(retval == null && LOG.isDebugEnabled())
      LOG.debug("Serial input data failed CRC test: \"" + input+ "\"");

    return retval;
  }

  /**
   * CRC is better than a simple checksum, the corresponding C implementation for the micro controller:
   * <pre>{@code
   * uint8_t crc8(char* pointer, uint16_t len) {
   *   uint8_t CRC = 0x00;
   *   uint16_t tmp;
   *   while(len > 0) {
   *     tmp = CRC << 1;
   *     tmp += *pointer;
   *     CRC = (tmp & 0xFF) + (tmp >> 8);
   *     pointer++;
   *     --len;
   *   }
   *   return CRC;
   * }
   * }</pre>
   *
   * @param data
   * @return
   */
  private int crc8(byte[] data) {
    assert data != null;

    int tmp;
    int res = 0;
    for(int i = 0; i < data.length; i++) {
      tmp = res << 1;
      tmp += 0xff & data[i];
      res = ((tmp & 0xff) + (tmp >> 8)) & 0xff;
    }
    return res;
  }
}
