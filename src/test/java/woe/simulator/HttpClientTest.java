package woe.simulator;

import static akka.http.javadsl.server.Directives.complete;
import static akka.http.javadsl.server.Directives.concat;
import static akka.http.javadsl.server.Directives.entity;
import static akka.http.javadsl.server.Directives.get;
import static akka.http.javadsl.server.Directives.path;
import static akka.http.javadsl.server.Directives.post;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static woe.simulator.WorldMap.regionAtLatLng;
import static woe.simulator.WorldMap.regionForZoom0;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import org.junit.ClassRule;
import org.junit.Test;

import akka.actor.ActorSystem;
import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.Route;

public class HttpClientTest {
  @ClassRule
  public static final TestKitJunitResource testKit = new TestKitJunitResource(config());

  private static Config config() {
    return ConfigFactory.parseString(
        String.format("akka.cluster.seed-nodes = [ \"akka://%s@127.0.0.1:25520\" ] %n", HttpServerTest.class.getSimpleName())
            + String.format("akka.persistence.snapshot-store.local.dir = \"%s-%s\" %n", "target/snapshot", UUID.randomUUID().toString())
    ).withFallback(ConfigFactory.load("application-test.conf"));
  }

  @Test
  public void postToResponseWorks() {
    httpServer("localhost", 28080);
    final HttpClient httpClient = new HttpClient(testKit.system(), "http://localhost:28080/telemetry");
    // London across Westminster Bridge at Park Plaza Hotel
    final WorldMap.Region region = regionAtLatLng(18, new WorldMap.LatLng(51.50079211, -0.11682093));
    final Region.SelectionCreate selectionCreate = new Region.SelectionCreate(region, Instant.now(), false, null);
    final Telemetry.TelemetryResponse telemetryResponse = httpClient.post(selectionCreate)
        .toCompletableFuture().join();

    assertEquals(201, telemetryResponse.httpStatusCode);

    assertEquals(selectionCreate.region.zoom, telemetryResponse.telemetryRequest.zoom);
    assertEquals(selectionCreate.region.topLeft.lat, telemetryResponse.telemetryRequest.topLeftLat);
    assertEquals(selectionCreate.region.topLeft.lng, telemetryResponse.telemetryRequest.topLeftLng);
    assertEquals(selectionCreate.region.botRight.lat, telemetryResponse.telemetryRequest.botRightLat);
    assertEquals(selectionCreate.region.botRight.lng, telemetryResponse.telemetryRequest.botRightLng);
  }

  private static CompletionStage<ServerBinding> httpServer(String host, int port) {
    ActorSystem actorSystem = testKit.system().classicSystem();

    return Http.get(actorSystem.classicSystem()).newServerAt(host, port).bind(route());
  }

  private static Route route() {
    return concat(
        path("telemetry", () -> concat(
            get(() -> {
              WorldMap.Region selection = regionForZoom0();
              Region.SelectionCreate selectionCreate = new Region.SelectionCreate(selection, Instant.now(), false, null);
              return complete(StatusCodes.OK, selectionCreate, Jackson.marshaller());
            }),
            post(() -> entity(
                Jackson.unmarshaller(Telemetry.TelemetryRequest.class),
                telemetryRequest -> {
                  final Telemetry.TelemetryResponse telemetryResponse = new Telemetry.TelemetryResponse("ok", StatusCodes.CREATED.intValue(), telemetryRequest);
                  return complete(StatusCodes.CREATED, telemetryResponse, Jackson.marshaller());
                })
            )
        ))
    );
  }
}
