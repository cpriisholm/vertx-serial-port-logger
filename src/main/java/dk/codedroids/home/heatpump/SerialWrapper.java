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
 */package dk.codedroids.home.heatpump;

import gnu.io.*;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.TooManyListenersException;
import java.util.function.Consumer;

/**
 * Sets up a serial port and set a handler (Consumer function) which gets invoked when serial input data is ready
 *
 * @author Claus Priisholm.
 */
public class SerialWrapper implements SerialPortEventListener, SerialInput {

  /**
   * Most settings for the port have defaults, but we need to the device path.
   * This builder reflects that.
   */
  public static class Builder {
    private final String devicePath;
    private int dataRate;
    private int dataBits;
    private int stopBits;
    private int parity;
    private int openTimeout;

    /** Constructor with required parameters */
    public Builder(String devicePath) {
      this.devicePath = devicePath;
      this.dataRate = 9600;
      this.dataBits = SerialPort.DATABITS_8;
      this.stopBits = SerialPort.STOPBITS_1;
      this.parity = SerialPort.PARITY_NONE;
      this.openTimeout = 0;
    }
    /** Serial port data rate, defaults to 9600 baud */
    public Builder dataRate(int value) { this.dataRate = value; return this; }
    /** Serial port data bit, defaults to 8 bit */
    public Builder dataBits(int value) { this.dataBits = value; return this; }
    /** Serial port stop bit, default to 1 */
    public Builder stopBits(int value) { this.stopBits = value; return this; }
    /** Serial port parity, default to none */
    public Builder parity(int value) { this.parity = value; return this; }
    /** Serial port open timeout - milliseconds to block while waiting for port open. default to 2000 msec */
    public Builder openTimeout(int value) { this.openTimeout = value; return this; }
    /** Build SerialLister */
    public SerialWrapper build() { return new SerialWrapper(this); }
  }

  private final Logger LOG = LoggerFactory.getLogger(SerialWrapper.class);

  private final String devicePath;
  private Consumer<String> handler;

  private SerialPort serialPort;
  private BufferedReader input;
  /** The output stream to the port */
  private OutputStream output;


  public SerialWrapper(Builder builder) {
    super();
    devicePath = builder.devicePath;

    try {
      if(builder.openTimeout > 0) {
        // To be able to set timeout explicitly, we need to use the portId for the device,
        // open serial port, and use class name for the appName and set port params.
        CommPortIdentifier portId = CommPortIdentifier.getPortIdentifier(devicePath);
        serialPort = (SerialPort) portId.open(this.getClass().getName(), builder.openTimeout);
      } else{
        serialPort = new RXTXPort(devicePath);
      }
      serialPort.setSerialPortParams(builder.dataRate, builder.dataBits, builder.stopBits, builder.parity);
      input = new BufferedReader(new InputStreamReader(serialPort.getInputStream()));
      output = serialPort.getOutputStream();
      serialPort.addEventListener(this);
    } catch (NoSuchPortException|PortInUseException|UnsupportedCommOperationException|IOException|TooManyListenersException e) {
      // Generally caused by configuration error or wrong system settings,
      // not much that can be done here so just re-throw
      throw new RuntimeException(e);
    }
    serialPort.notifyOnDataAvailable(true);
  }

  /**
   * Sets the handler that gets invoked when serial data is ready, gets invoked with the input string.
   * If no handler is set input is just logged if log level is TRACE/FINEST
   */
  @Override
  public void setHandler(Consumer<String> handler) {
    this.handler = handler;
  }

  /**
   * Pass input on to the handler (if any)
   * @param input
   */
  void acceptInput(String input) {
    if(handler != null)
      handler.accept(input);
  }

  @Override
  public void serialEvent(SerialPortEvent oEvent) {
    if(LOG.isTraceEnabled()) LOG.trace("Serial port event type: " + oEvent.getEventType());
    if (oEvent.getEventType() == SerialPortEvent.DATA_AVAILABLE) {
      try {
        String inputLine;
        synchronized(input) {
          inputLine = input.readLine();
        }
        if(LOG.isTraceEnabled())
          LOG.trace("Serial data:" + inputLine);
        acceptInput(inputLine);
      } catch (Exception e) {
        LOG.error("Serial data error:", e);
      }
    }
  }

  /**
   * This must be called when you stop using the port.
   * This will prevent port locking on platforms like Linux.
   */
  @Override
  public synchronized void close() {
    if (serialPort != null) {
      serialPort.removeEventListener();
      serialPort.close();
    }
  }
}
