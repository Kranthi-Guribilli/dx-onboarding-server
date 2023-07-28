package iudx.onboarding.server.apiserver;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.TimeoutHandler;
import iudx.onboarding.server.catalogue.CatalogueUtilService;
import iudx.onboarding.server.common.Api;
import iudx.onboarding.server.common.CatalogueType;
import iudx.onboarding.server.common.HttpStatusCode;
import iudx.onboarding.server.token.TokenService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.stream.Stream;

import static iudx.onboarding.server.apiserver.util.Constants.*;
import static iudx.onboarding.server.apiserver.util.Util.errorResponse;
import static iudx.onboarding.server.common.Constants.*;

/**
 * The Onboarding Server API Verticle.
 *
 * <h1>Onboarding Server API Verticle</h1>
 *
 * <p>
 * The API Server verticle implements the IUDX Onboarding Server APIs. It handles the API requests
 * from the clients and interacts with the associated Service to respond.
 *
 * @version 1.0
 * @see io.vertx.core.Vertx
 * @see AbstractVerticle
 * @see HttpServer
 * @see Router
 * @see io.vertx.servicediscovery.ServiceDiscovery
 * @see io.vertx.servicediscovery.types.EventBusService
 * @see io.vertx.spi.cluster.hazelcast.HazelcastClusterManager
 * @since 2023-07-27
 */
public class ApiServerVerticle extends AbstractVerticle {

  private static final Logger LOGGER = LogManager.getLogger(ApiServerVerticle.class);

  private HttpServer server;
  private Router router;
  private int port;
  private boolean isSSL;
  private String dxApiBasePath;
  private TokenService tokenService;
  private CatalogueUtilService catalogueService;

  /**
   * This method is used to start the Verticle. It deploys a verticle in a cluster, reads the
   * configuration, obtains a proxy for the Event bus services exposed through service discovery,
   * start an HTTPs server at port 8443 or an HTTP server at port 8080.
   *
   * @throws Exception which is a startup exception TODO Need to add documentation for all the
   */
  @Override
  public void start() throws Exception {
    /* Create a reference to HazelcastClusterManager. */

    router = Router.router(vertx);

    /* Get base paths from config */
    dxApiBasePath = config().getString("dxApiBasePath");
    Api api = Api.getInstance(dxApiBasePath);

    /* Define the APIs, methods, endpoints and associated methods. */

    router = Router.router(vertx);
    configureCorsHandler(router);

    putCommonResponseHeaders();

    // attach custom http error responses to router
    configureErrorHandlers(router);

    router.route().handler(BodyHandler.create());
    router.route().handler(TimeoutHandler.create(10000, 408));

    /* NGSI-LD api endpoints */

    // item API
    router.post(api.getOnboardingUrl()).handler(this::createItem);
    router.get(api.getOnboardingUrl()).handler(this::getItem);
    router.patch(api.getOnboardingUrl()).handler(this::updateItem);
    router.delete(api.getOnboardingUrl()).handler(this::deleteItem);

    // adapter API
    router.post(api.getIngestionUrl()).handler(this::registerAdapter);

    // TODO : test URL - will be deleted later
    router.post(api.getTokenUrl()).handler(this::handleTokenRequest);

    /* Read ssl configuration. */
    HttpServerOptions serverOptions = new HttpServerOptions();
    setServerOptions(serverOptions);
    serverOptions.setCompressionSupported(true).setCompressionLevel(5);
    server = vertx.createHttpServer(serverOptions);
    server.requestHandler(router).listen(port);

    tokenService = TokenService.createProxy(vertx, TOKEN_ADDRESS);
    catalogueService = CatalogueUtilService.createProxy(vertx, CATALOGUE_ADDRESS);
    /* Print the deployed endpoints */
    LOGGER.info("API server deployed on: " + port);
  }

  /**
   * Configures the CORS handler on the provided router.
   *
   * @param router The router instance to configure the CORS handler on.
   */
  private void configureCorsHandler(Router router) {
    router
        .route()
        .handler(CorsHandler
            .create("*")
            .allowedHeaders(ALLOWED_HEADERS)
            .allowedMethods(ALLOWED_METHODS));
  }

  /**
   * Configures error handlers for the specified status codes on the provided router.
   *
   * @param router The router instance to configure the error handlers on.
   */
  private void configureErrorHandlers(Router router) {
    HttpStatusCode[] statusCodes = HttpStatusCode.values();
    Stream.of(statusCodes).forEach(code -> {
      router.errorHandler(code.getValue(), errorHandler -> {
        HttpServerResponse response = errorHandler.response();
        if (response.headWritten()) {
          try {
            response.close();
          } catch (RuntimeException e) {
            LOGGER.error("Error: " + e);
          }
          return;
        }
        response
            .putHeader(CONTENT_TYPE, APPLICATION_JSON)
            .setStatusCode(code.getValue())
            .end(errorResponse(code));
      });
    });
  }

  /**
   * Sets common response headers to be included in HTTP responses.
   */
  private void putCommonResponseHeaders() {
    router.route().handler(requestHandler -> {
      requestHandler
          .response()
          .putHeader("Cache-Control", "no-cache, no-store,  must-revalidate,max-age=0")
          .putHeader("Pragma", "no-cache")
          .putHeader("Expires", "0")
          .putHeader("X-Content-Type-Options", "nosniff");
      requestHandler.next();
    });
  }

  /**
   * Sets the server options based on the configuration settings. If SSL is enabled, starts an HTTPS
   * server with the specified HTTP port. If SSL is disabled, starts an HTTP server with the
   * specified HTTP port. If the HTTP port is not specified in the configuration, default ports
   * (8080 for HTTP and 8443 for HTTPS) will be used.
   *
   * @param serverOptions The server options to be configured.
   */
  private void setServerOptions(HttpServerOptions serverOptions) {
    isSSL = config().getBoolean("ssl");
    if (isSSL) {
      LOGGER.debug("Info: Starting HTTPs server");
      port = config().getInteger("httpPort") == null ? 8443 : config().getInteger("httpPort");
    } else {
      LOGGER.debug("Info: Starting HTTP server");
      serverOptions.setSsl(false);
      port = config().getInteger("httpPort") == null ? 8080 : config().getInteger("httpPort");
    }
  }

  private void handleTokenRequest(RoutingContext routingContext) {
    HttpServerResponse response = routingContext.response();
    response.putHeader(CONTENT_TYPE, APPLICATION_JSON);
    tokenService.createToken()
        .onSuccess(successHandler -> {
          response.setStatusCode(200)
              .end(successHandler.toString());
        })
        .onFailure(failureHandler -> {
          response.setStatusCode(400)
              .end(failureHandler.getMessage());
        });
  }

  private void createItem(RoutingContext routingContext) {
    MultiMap tokenHeadersMap = routingContext.request().headers();
    HttpServerResponse response = routingContext.response();
    JsonObject requestBody = routingContext.body().asJsonObject();
    response.putHeader(CONTENT_TYPE, APPLICATION_JSON);
    catalogueService
        .createItem(requestBody, tokenHeadersMap.get(TOKEN), CatalogueType.LOCAL)
        .onSuccess(
            localItem -> {
              JsonObject itemBodyWithId = localItem.getJsonObject(RESULTS);
              LOGGER.debug(
                  "item uploaded in local cat {}", itemBodyWithId);

              catalogueService
                  .createItem(itemBodyWithId, tokenHeadersMap.get(TOKEN), CatalogueType.CENTRAL)
                  .onSuccess(
                      centralItem -> {
                        response
                            .setStatusCode(201)
                            .end(
                                centralItem
                                    .toString());
                      })
                  .onFailure(centralCatItemFailure -> {
                    // This is after 3 retries and delete of item from local
                    // TODO: notify user to try again
                    response.setStatusCode(500).end("Upload failed, try again later");
                  });
            })
        .onFailure(
            createLocalItemFailureHandler -> {
              Throwable cause = createLocalItemFailureHandler.getCause();
              LOGGER.info("Local Handler Failed {}", cause);
            });
  }

  private void updateItem(RoutingContext routingContext) {
    MultiMap tokenHeadersMap = routingContext.request().headers();
    HttpServerResponse response = routingContext.response();
    JsonObject requestBody = routingContext.body().asJsonObject();
    response.putHeader(CONTENT_TYPE, APPLICATION_JSON);
    catalogueService
        .updateItem(requestBody, tokenHeadersMap.get(TOKEN), CatalogueType.LOCAL)
        .onSuccess(
            updateLocalItemSuccessHandler -> {
              JsonObject localUpdateResponse = updateLocalItemSuccessHandler;
              LOGGER.info("Response {}", localUpdateResponse);
              LOGGER.info(
                  "item updated in local cat {}", localUpdateResponse.getJsonArray(RESULTS));

              catalogueService
                  .updateItem(requestBody, tokenHeadersMap.get(TOKEN), CatalogueType.CENTRAL)
                  .onSuccess(
                      updateCentralCatItemSuccess -> {
                        response
                            .setStatusCode(200)
                            .end(
                                updateCentralCatItemSuccess
//                                    .getJsonObject(RESULTS)
                                    .toString());
                      })
                  .onFailure(centralCatItemFailure -> {
                    // TODO: undo changes on local
//                          handleResponse(response, createCentralCatItemSuccess);
//                    Throwable cause = centralCatItemFailure.getCause();
                    LOGGER.info("Central Handler Failed {}", centralCatItemFailure.getMessage());
                  });
            })
        .onFailure(
            createLocalItemFailureHandler -> {
              Throwable cause = createLocalItemFailureHandler.getCause();
              LOGGER.info("Lccal Handler Failed {}", cause);
            });
  }

  private void deleteItem(RoutingContext routingContext) {
    MultiMap tokenHeadersMap = routingContext.request().headers();
    HttpServerRequest request = routingContext.request();
    HttpServerResponse response = routingContext.response();
    JsonObject requestBody = new JsonObject()
        .put(ID, request.getParam(ID));
    response.putHeader(CONTENT_TYPE, APPLICATION_JSON);

    catalogueService
        .deleteItem(requestBody, tokenHeadersMap.get(TOKEN), CatalogueType.LOCAL)
        .onSuccess(
            deleteLocalItemSuccessHandler -> {
              JsonObject localCreateResponse = deleteLocalItemSuccessHandler;
              LOGGER.info("Response {}", localCreateResponse);
              LOGGER.info(
                  "item deleted in local cat {}", localCreateResponse.getJsonArray(RESULTS));

              catalogueService
                  .deleteItem(requestBody, tokenHeadersMap.get(TOKEN), CatalogueType.CENTRAL)
                  .onSuccess(
                      deleteCentralCatItemSuccess -> {
                        LOGGER.info(
                            "item deleted in central cat {}",
                            deleteCentralCatItemSuccess.getJsonArray(RESULTS));
                        response
                            .setStatusCode(200)
                            .end(
                                deleteCentralCatItemSuccess
//                                    .getJsonObject(RESULTS)
                                    .toString());
                      })
                  .onFailure(centralCatItemFailure -> {
                    handleInconsistentDelete(tokenHeadersMap, request, response);
//                    Throwable cause = centralCatItemFailure.getCause();
                    LOGGER.info(
                        "Handler Failed to upload item in local cat, item not deleted from central {}",
                        centralCatItemFailure.getMessage());
                  });
            })
        .onFailure(
            createLocalItemFailureHandler -> {
              Throwable cause = createLocalItemFailureHandler.getCause();
              LOGGER.info("Local Handler Failed {}", cause);
            });
  }

  private void handleInconsistentDelete(MultiMap tokenHeadersMap, HttpServerRequest request, HttpServerResponse response) {
    LOGGER.warn("Item not deleted from cat central");
    catalogueService
        .getItem(request.getParam(ID), CatalogueType.CENTRAL)
        .onSuccess(
            getFromCentralHandler -> {
              if (getFromCentralHandler.getInteger(STATUS_CODE).equals(200)) {
                LOGGER.info("getting item from central cat to upload to cop");
                JsonObject requestbody =
                    getFromCentralHandler
                        .getJsonObject(RESULTS)
                        .getJsonArray(RESULTS)
                        .getJsonObject(0);
                requestbody.remove("itemStatus");
                requestbody.remove("resourceServerHTTPAccessURL");
                Future.future(f -> restoreItemOnLocal(requestbody, tokenHeadersMap.get(TOKEN), response));
              } else {
                LOGGER.info(
                    "item not present in central");
                handleResponse(response, getFromCentralHandler);
              }
            })
        .onFailure(
            uploadLocalItem -> {
              Throwable cause = uploadLocalItem.getCause();
              LOGGER.info(
                  "Handler Failed to upload item in local cat, item not deleted from central {}",
                  cause);
            });
  }

  private Future<JsonObject> restoreItemOnLocal(
      JsonObject itemBody, String token, HttpServerResponse response) {
    Promise<JsonObject> promise = Promise.promise();
    catalogueService
        .createItem(itemBody, token, CatalogueType.LOCAL)
        .onSuccess(
            createItem -> {
              if (createItem.getInteger(STATUS_CODE).equals(201)) {
                LOGGER.info("item created again in local cat");
                response.setStatusCode(201).end(createItem.getJsonObject(RESULTS).toString());
              } else {
                handleResponse(response, createItem);
              }
            });

    return promise.future();
  }

  private void handleResponse(HttpServerResponse response, JsonObject responseObject) {
    int statusCode = responseObject.getInteger(STATUS_CODE);
    response.setStatusCode(statusCode).end(responseObject.getJsonObject(RESULTS).toString());
  }

  private void getItem(RoutingContext routingContext) {
    HttpServerRequest request = routingContext.request();
    HttpServerResponse response = routingContext.response();
    response.putHeader(CONTENT_TYPE, APPLICATION_JSON);
    catalogueService.getItem(request.getParam(ID), CatalogueType.LOCAL)
        .onSuccess(
            getLocalItemSuccessHandler -> {
              LOGGER.info("Response {}", getLocalItemSuccessHandler);
              LOGGER.info(
                  "item taken from local cat {}", getLocalItemSuccessHandler.getJsonArray(RESULTS));
              // call only if response is 200 -success
              response
                  .setStatusCode(200)
                  .end(
                      getLocalItemSuccessHandler
//                            .getJsonObject(RESULTS)
                          .toString());
            })
        .onFailure(
            getLocalItemFailureHandler -> {
//                handleResponse(response, getLocalItemSuccessHandler);
//              Throwable cause = createLocalItemFailureHandler.getCause();
              LOGGER.info("Handler Failed {}", getLocalItemFailureHandler.getMessage());
            });
  }

  private void registerAdapter(RoutingContext routingContext) {
  }

  private void updateAdapter(RoutingContext routingContext) {
  }

  private void deleteAdapter(RoutingContext routingContext) {
  }

  private void getAdapter(RoutingContext routingContext) {
  }
}
