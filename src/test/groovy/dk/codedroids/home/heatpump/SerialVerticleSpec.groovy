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
package dk.codedroids.home.heatpump

import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.eventbus.EventBus
import io.vertx.core.json.JsonObject
import spock.lang.Specification
import spock.lang.Unroll
import spock.lang.Shared
import io.vertx.core.json.JsonArray
import spock.util.concurrent.AsyncConditions

/**
 * @author Claus Priisholm.
 */
class SerialVerticleSpec extends Specification {
  @Shared csvTestData = []
  @Shared jsonTestData = []
  @Shared String eventBusAddress = "home.heatpump.data.testing"
  @Shared Vertx vertx
  @Shared MockSerialInput mock

  def setupSpec() {
    File csvFile = new File('src/test/resources/two-hours-of-serial-data.csv')
    csvFile.eachLine{ line ->
      csvTestData << [ line, line.substring(0, line.lastIndexOf(',')) ]
    }
    File busMsgsFile = new File('src/test/resources/two-hours-of-bus-msgs.txt')
    int offset = 0
    busMsgsFile.eachLine{ msg ->
      jsonTestData << [ csvTestData[offset++][0], msg ]
    }

    vertx = Vertx.vertx()

    JsonObject verticleConf =  new JsonObject()
    verticleConf.put("event_bus", eventBusAddress)

    mock = new MockSerialInput()
    SerialVerticle verticle = new SerialVerticle(mock)

    DeploymentOptions deploymentOptions = new DeploymentOptions().setConfig(verticleConf)
    vertx.deployVerticle(verticle, deploymentOptions)
  }

  def cleanupSpec() {
    mock.close()
    vertx.close()
  }

  def 'verifiedInput on serial data test input'() {
    given:
    SerialVerticle holder = new SerialVerticle()

    expect:
    holder.verifiedInput(input) == result

    where:
    [ input, result ] << csvTestData
  }

  @Unroll
  def 'verifiedInput on various input: #desc'() {
    given:
    SerialVerticle holder = new SerialVerticle()

    expect:
    holder.verifiedInput(input) == result

    where:
    desc             | input                                    || result
    'ok crc'         | '-0.7,37.2,28.6,8.8,33.7,1.2,1.2,1.2,37' || '-0.7,37.2,28.6,8.8,33.7,1.2,1.2,1.2'
    'bad crc'        | '-0.7,37.2,28.6,8.8,33.7,1.2,1.2,1.2,36' || null
    'missing column' | '37.2,28.6,8.8,33.7,1.2,1.2,1.2,37'      || null
    'empty'          | ''                                       || null
  }

  def 'verifiedInput on null'() {
    given:
    SerialVerticle instance = new SerialVerticle()

    when:
    instance.verifiedInput(null)

    then:
    thrown(AssertionError)
  }

  def 'jsonFromCsv on serial data test input'() {
    given:
    SerialVerticle instance = new SerialVerticle()

    expect:
    compareJsonArrays(instance.jsonFromCsv(instance.verifiedInput(input)), new JsonArray(result))

    where:
    [ input, result ] << jsonTestData
  }

  def 'jsonFromCsv on null'() {
    given:
    SerialVerticle instance = new SerialVerticle()

    when:
    instance.jsonFromCsv(null)

    then:
    thrown(AssertionError)
  }

  AsyncConditions conditions
  def 'serial csv input to event bus json message'() {
    setup:
    conditions = new AsyncConditions(1)
    EventBus eventBus = vertx.eventBus()
    String inp = "-0.7,37.2,28.6,8.8,33.7,1.2,1.2,1.2,37"
    String res = "[{\"t\":\"powr\",\"g\":\"pump\",\"s\":\"l1\",\"d\":1.2},{\"t\":\"powr\",\"g\":\"pump\",\"s\":\"l2\",\"d\":1.2},{\"t\":\"powr\",\"g\":\"pump\",\"s\":\"l3\",\"d\":1.2},{\"t\":\"temp\",\"g\":\"fyr\",\"s\":\"box\",\"d\":8.8},{\"t\":\"temp\",\"g\":\"fyr\",\"s\":\"feed\",\"d\":37.2},{\"t\":\"temp\",\"g\":\"fyr\",\"s\":\"pump\",\"d\":-0.7},{\"t\":\"temp\",\"g\":\"fyr\",\"s\":\"ret\",\"d\":28.6},{\"t\":\"temp\",\"g\":\"bry\",\"s\":\"tank\",\"d\":33.7}]"


    expect:
    // localConsumer() is does not propagate address across cluster so it returns faster
    eventBus.localConsumer(eventBusAddress,  { message ->
      conditions.evaluate {
        compareJsonArrays(new JsonArray(jsonTestData[0][1]), new JsonArray(message.body().toString())) == true
      }
    })

    // Use first line of test data...
    mock.acceptInput(jsonTestData[0][0])

    conditions.await(1d)
  }


  /**
   * JsonArray equals() does not do what we want, this method compares the elements
   * independent of the order in which they appear - as long all elements of the first
   * are present in the second, and number of elements are the same, this will return true.
   *
   * @param first non-null array of JsonObjects
   * @param second non-null array of JsonObjects
   * @return true if arrays contains same number of elements and all elements of <b>first</b> are present in
   * <b>second</b> (independent of ordering).
   */
  private boolean compareJsonArrays(JsonArray first, JsonArray second) {
    assert first
    assert second
    if(first.is(second))
      return true
    else if(first.size() != second.size())
      return false
    else
      return first.inject(true) { res, obj -> res && elementPresentInArray(second, obj) }
  }

  /**
   * JsonObject equality works, but JsonArray contains() does not find matching elements...
   * TODO check JsonArray.contains() implementation...
   *
   * @param array non-null array of JsonObjects
   * @param element non-null JsonObject
   * @return true if <b>element</b> equals any element in <b>array</b>.
   */
  private boolean elementPresentInArray(JsonArray array, JsonObject element) {
    assert array
    assert element
    return array.find { obj -> element == obj }
  }
}
