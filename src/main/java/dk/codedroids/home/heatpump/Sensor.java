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

/**
 * @author Claus Priisholm.
 *
 * Since the data from the controller is just list of values in a fixed order,
 * SerialVerticle use this enum to "upgrade" the information for the rest of the system.
 * ReplayVerticle use this as well, to provide more info than is stored in the database.
 */
public enum Sensor {
  pump(1, SensorGroup.fyr, SensorType.temp, "Ambient temperature at the heat pump, celsius"),
  feed(2, SensorGroup.fyr, SensorType.temp, "Feed water temperature, celsius"),
  ret(3, SensorGroup.fyr, SensorType.temp, "Return water temperature, celsius"),
  box(4, SensorGroup.fyr, SensorType.temp, "Ambient temperature at controller, celsius"),
  tank(5, SensorGroup.bry, SensorType.temp, "Buffer tank water temperature, celsius"),
  l1(6, SensorGroup.pump, SensorType.powr, "Pump phase 1 current usage, ampere"),
  l2(7, SensorGroup.pump, SensorType.powr, "Pump phase 2 current usage, ampere"),
  l3(8, SensorGroup.pump, SensorType.powr, "Pump phase 3 current usage, ampere");

  private final int order; // Must match the order used by the controller
  private final SensorGroup group;
  private final SensorType type;
  private final String description;

  Sensor(int order, SensorGroup group, SensorType type, String description) {
    this.order = order;
    this.group = group;
    this.type = type;
    this.description = description;
  }

  public int order() { return order; }
  public String group() { return group.name(); }
  public String type() { return type.name(); }
  public String description() { return description; }

  private enum SensorGroup {
    fyr,
    bry,
    pump
  }

  private enum SensorType {
    temp,
    powr
  }
}
