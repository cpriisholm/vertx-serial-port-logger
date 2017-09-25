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

import io.vertx.core.*;


import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.commons.cli.*;

/**
 * Acts a the "main" entry point, starts the verticles that are defined/enabled in the configuration,
 * passing the verticle specific configuration on to the relevant verticles.
 *
 * <p>Looks for a list named <b>"deploy_verticles"</b> in the configuration and start the verticles in the
 * order they are listed (possibly skipping those with zero instances). Each entry has the format:</p>
 *
 * <ul>
 * <li><b>"label" : "Heat Pump Logger"</b> -- An descriptive label</li>
 * <li><b>"instances" : 0 | 1 | ...</b> -- Zero or positive number. If zero then nothing gets started, otherwise the
 *        given number of instances gets started</li>
 * <li><b>"verticle" : "dk.codedroids.home.heatpump.LoggerVerticle"</b> -- typically the class name or other naming
 *        Vert.x can use to identify and deploy a verticle</li>
 * <li><b>"config" : { ... }</b> -- A JSON object defining the configuration passed along to the verticle.</li>
 * </li>
 *
 * @author Claus Priisholm.
 */
public class MainVerticle extends AbstractVerticle {

  private static final Logger LOG = LoggerFactory.getLogger(MainVerticle.class);

  @Override
  public void start(Future<Void> startFuture) {

    LOG.info("Starting main verticle");
    if(LOG.isDebugEnabled())
      LOG.debug("With configuration:\n" + config().encodePrettily());

    JsonArray deployVerticles = config().getJsonArray("deploy_verticles");

    if(deployVerticles != null) {
      for(Object o : deployVerticles) {
        JsonObject conf = (JsonObject)o;
        int instances = conf.getInteger("instances", Integer.valueOf(0));
        if(instances < 0)
          instances = 0;
        String name = conf.getString("verticle");
        if(name != null && instances > 0) {
          String label = conf.getString("label", name); // Use name as label if one is not provided
          JsonObject verticleConf = conf.getJsonObject("config");
          if(verticleConf == null)
            verticleConf = new JsonObject();
          for(int i=0; i<instances; i++) {
            LOG.info("Deploying \"" + label + "\" #" + (i+1) + "...");
            DeploymentOptions deploymentOptions = new DeploymentOptions().setConfig(verticleConf);
            vertx.deployVerticle(name, deploymentOptions);
          }
        }
      }
    }

    LOG.info("Main verticle deployed.");
  }


  /**
   * Enables IDE to run (defaults to use src/conf/test-config.json if not given with option -conf),
   * or run like {@code java -cp heatpump-fat.jar dk.codedroids.home.heatpump.MainVerticle -conf conf.json}
   */
  public static void main(String[] args) {
    if(LOG.isDebugEnabled())
      Stream.of(args).forEach(LOG::debug);

    Options opts = new Options();
    opts
      .addOption(Option.builder("c")
        .longOpt("conf")
        .required(false)
        .build());

    try {
      CommandLine cli = new DefaultParser().parse(opts, args);

      Path confPath = null;
      if(cli.getOptionValue("conf") != null) {
        confPath = FileSystems.getDefault().getPath(cli.getOptionValue("conf"));
      } else {
        confPath = FileSystems.getDefault().getPath("src/conf/test-config.json");
      }

      // Load json from config path
      JsonObject json = null;
      try {
        byte[] data = Files.readAllBytes(confPath);
        json = new JsonObject(new String(data, "UTF-8"));
      } catch (IOException e) {
        LOG.error("Failed to read configuration: " + confPath.toAbsolutePath().toString());
        System.exit(1);
      }

      if(LOG.isInfoEnabled())
        LOG.info("Read configuration from: " + confPath.toAbsolutePath().toString());
      DeploymentOptions deploymentOptions = new DeploymentOptions().setConfig(json);

      Vertx.vertx().deployVerticle(new MainVerticle(), deploymentOptions);

    } catch (ParseException e) {
      new HelpFormatter().printHelp("java -jar ...", opts, true);
    }
  }
}
