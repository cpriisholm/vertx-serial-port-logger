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

import java.util.function.Consumer;

/**
 * A way to test the handler provided by SerialVerticle without actually connecting to a device port
 * as SerialWrapper does.
 *
 * @author Claus Priisholm.
 */
public class MockSerialInput implements SerialInput {

  Consumer<String> handler;

  @Override
  public void setHandler(Consumer<String> handler) {
    this.handler = handler;
  }

  @Override
  public void close() {
    return; // does nothing
  }

  /**
   * Pass input on to the handler (if any)
   * @param input
   */
  void acceptInput(String input) {
    if(handler != null)
      handler.accept(input);
  }


}
