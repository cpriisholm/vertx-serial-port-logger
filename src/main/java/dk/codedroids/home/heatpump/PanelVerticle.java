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
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.TemplateHandler;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.bridge.PermittedOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions;
import io.vertx.ext.web.templ.PebbleTemplateEngine;
import io.vertx.ext.web.templ.TemplateEngine;


/**
 * Configuration:</b>:
 *
 * <ul>
 * <li><b>event_bus : "home.heatpump.data"</b> -- Eventbus address</li>
 * <li><b>event_bus_url : "http://localhost:9000/eventbus"</b> -- Client-side event bus url</li>
 * <li><b>reconnect_interval : 10</b> -- Number of seconds in between reconnects attempts (will try max 10 times)</li>
 * <li><b>http_caching : true | false</b> -- Per default http caching is enabled</li>
 * <li><b>http_port : 9000</b> -- Serve content from this port</li>
 * </li>
 *
 * @author Claus Priisholm.
 */
public class PanelVerticle extends AbstractVerticle {

  private final Logger LOG = LoggerFactory.getLogger(PanelVerticle.class);

  private String eventBusAddress;
  private int httpPort;

  @Override
  public void start() {

    if(LOG.isDebugEnabled())
      LOG.debug("Panel verticle starting with config: " + config().encodePrettily());

    eventBusAddress = config().getString("event_bus","home.heatpump.playback");
    httpPort = config().getInteger("http_port", 9000);
    boolean httpCaching = config().getBoolean("http_caching", true);
    String eventBusUrl = config().getString("event_bus_url","http://localhost:9000/eventbus");
    int reconnectInterval = config().getInteger("reconnect_interval", 10) * 1000;

    SockJSHandlerOptions sockJSHandlerOpts = new SockJSHandlerOptions().setHeartbeatInterval(2000);
    SockJSHandler sockJSHandler = SockJSHandler.create(vertx, sockJSHandlerOpts);

    // http://vertx.io/docs/vertx-web/java/#_sockjs_event_bus_bridge
    // Let through any messages coming from exact address:
    PermittedOptions outboundPermitted = new PermittedOptions().setAddress(eventBusAddress);
    // Let through any messages from addresses starting with "news." (e.g. news.europe, news.usa, etc)
    //PermittedOptions outboundPermitted2 = new PermittedOptions().setAddressRegex("news\\..+");

    BridgeOptions options = new BridgeOptions()
      .addOutboundPermitted(outboundPermitted);
    sockJSHandler.bridge(options);

    TemplateEngine engine = PebbleTemplateEngine.create(vertx);
    TemplateHandler templateHandler = TemplateHandler.create(engine); // Handles html type of templates

    Router router = Router.router(vertx);
    router.route().handler(BodyHandler.create());
    router.route("/eventbus/*").handler(sockJSHandler);
    // Need to inject data into routing context prior to letting the template engine do its thing
    router.get("/").handler(routingContext -> {
      routingContext.put("eventBusAddress", eventBusAddress);
      routingContext.put("eventBusUrl", eventBusUrl);
      routingContext.put("reconnectInterval", reconnectInterval);
      routingContext.next();
    });
    router.get("/").handler(templateHandler);
    router.get("/*").handler(StaticHandler.create().setCachingEnabled(httpCaching)); // serves files from .../resources/webroot/

    vertx.createHttpServer().requestHandler(router::accept).listen(httpPort);

    LOG.info("Panel verticle started, listens on " + eventBusAddress + ", serving on port " + httpPort);
  }

  @Override
  public void stop() {
    LOG.info("Panel verticle stopped listening on " + eventBusAddress);
  }

}
