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
 * The wrapper needs a handler which get invoked with the input string from the serial port when data available.
 *
 * @author Claus Priisholm.
 */
public interface SerialInput {

  /** Sets the handler that gets invoked when serial data is ready, gets invoked with the input string */
  void setHandler(Consumer<String> handler);

  /** Implementors may need to release resources when finished, so call this to make sure it is done*/
  void close();
}
