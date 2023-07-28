package iudx.onboarding.server.catalogue.service;

import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import iudx.onboarding.server.common.CatalogueType;

@VertxGen
@ProxyGen
public interface CatalogueService {
  Future<JsonObject> createItem(final JsonObject request, String token);
  Future<JsonObject> updateItem(final JsonObject request, String token);
  Future<JsonObject> deleteItem(final String id, String token);
  Future<JsonObject> getItem(final String id);

}