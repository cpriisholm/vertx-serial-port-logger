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
import io.vertx.core.eventbus.EventBus
import gnu.io.SerialPort
import gnu.io.SerialPortEvent
import gnu.io.SerialPortEventListener
import gnu.io.RXTXPort

/**
 * Prototype script for reading from serial port and push to event bus
 * Run with (adjust class path for environment):
 * CLASSPATH="/usr/share/java/RXTXcomm.jar" vertx run groovy:SerialVerticle.groovy -cluster
 *
 * Use echo to provide input on the port set as devicePath, e.g.:
 * echo "15.3,15.1,14.9,16.3,19.1,0.1,0.1,0.1,30"
 */

String address = 'home.heatpump.test'

EventBus eventBus = vertx.eventBus()

SerialHelper.newListener('/dev/ttyACM2', 9600).handler{ inputLine ->
  println inputLine
  if(eventBus)
    eventBus.publish(address, inputLine)
}

println "*** Heater serial verticel started, publish @ ${address}"


/**
 * Helper class for handling the serial comm, set the handler closure to be notified when data has arrived
 */
class SerialHelper implements SerialPortEventListener {

  private Closure handler
  private SerialPort serialPort
  private BufferedReader input

  /**
   * Get a listener instance for the for
   * @param devicePath Device path of port
   * @param dataRate Baud rate to use
   * @return
   */
  static SerialHelper newListener(String devicePath, int dataRate) {
    SerialHelper helper = new SerialHelper()
    SerialPort serialPort = new RXTXPort(devicePath)
    serialPort.setSerialPortParams(dataRate, SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE)
    serialPort.addEventListener(helper)
    serialPort.notifyOnDataAvailable(true)
    helper.serialPort = serialPort
    helper.input = new BufferedReader(new InputStreamReader(helper.serialPort.getInputStream()))
    return helper
  }

  private SerialHelper() { }

  /**
   * The handler gets called with the inputLine (String)
   * @param handler
   */
  SerialHelper handler(Closure handler) {
    this.handler = handler
    return this
  }

  /**
   * This is called when there is an event on the serial port
   * @param oEvent
   */
  void serialEvent(SerialPortEvent oEvent) {
    if(handler) {
      try {
        // Only event type we care about:
        if (oEvent.getEventType() == SerialPortEvent.DATA_AVAILABLE) {
          String inputLine
          synchronized (input) {
            inputLine = input.readLine()
          }
          handler.call(inputLine)
        }
      } catch (Exception e) {
        println "*** Serial data error: " + e.getMessage()
      }
    }
  }
}
