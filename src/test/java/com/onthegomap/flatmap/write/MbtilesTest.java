package com.onthegomap.flatmap.write;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onthegomap.flatmap.geo.GeoUtils;
import com.onthegomap.flatmap.geo.TileCoord;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Envelope;

public class MbtilesTest {

  private static final int BATCH = 999 / 4;

  public void testWriteTiles(int howMany, boolean deferIndexCreation, boolean optimize)
    throws IOException, SQLException {
    try (Mbtiles db = Mbtiles.newInMemoryDatabase()) {
      db
        .setupSchema()
        .tuneForWrites();
      if (!deferIndexCreation) {
        db.addIndex();
      }
      Set<Mbtiles.TileEntry> expected = new HashSet<>();
      try (var writer = db.newBatchedTileWriter()) {
        for (int i = 0; i < howMany; i++) {
          var entry = new Mbtiles.TileEntry(TileCoord.ofXYZ(i, i, 14), new byte[]{
            (byte) howMany,
            (byte) (howMany >> 8),
            (byte) (howMany >> 16),
            (byte) (howMany >> 24)
          });
          writer.write(entry.tile(), entry.bytes());
          expected.add(entry);
        }
      }
      if (deferIndexCreation) {
        db.addIndex();
      }
      if (optimize) {
        db.vacuumAnalyze();
      }
      var all = getAll(db);
      assertEquals(howMany, all.size());
      assertEquals(expected, all);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, BATCH, BATCH + 1, 2 * BATCH, 2 * BATCH + 1})
  public void testWriteTilesDifferentSize(int howMany) throws IOException, SQLException {
    testWriteTiles(howMany, false, false);
  }

  @Test
  public void testDeferIndexCreation() throws IOException, SQLException {
    testWriteTiles(10, true, false);
  }

  @Test
  public void testVacuumAnalyze() throws IOException, SQLException {
    testWriteTiles(10, false, true);
  }

  @Test
  public void testAddMetadata() throws IOException {
    Map<String, String> expected = new TreeMap<>();
    try (Mbtiles db = Mbtiles.newInMemoryDatabase()) {
      var metadata = db.setupSchema().tuneForWrites().metadata();
      metadata.setName("name value");
      expected.put("name", "name value");

      metadata.setFormat("pbf");
      expected.put("format", "pbf");

      metadata.setAttribution("attribution value");
      expected.put("attribution", "attribution value");

      metadata.setBoundsAndCenter(GeoUtils.toLatLonBoundsBounds(new Envelope(0.25, 0.75, 0.25, 0.75)));
      expected.put("bounds", "-90,-66.51326,90,66.51326");
      expected.put("center", "0,0,1");

      metadata.setDescription("description value");
      expected.put("description", "description value");

      metadata.setMinzoom(1);
      expected.put("minzoom", "1");

      metadata.setMaxzoom(13);
      expected.put("maxzoom", "13");

      metadata.setVersion("1.2.3");
      expected.put("version", "1.2.3");

      metadata.setTypeIsBaselayer();
      expected.put("type", "baselayer");

      assertEquals(expected, metadata.getAll());
    }
  }

  @Test
  public void testAddMetadataWorldBounds() throws IOException {
    Map<String, String> expected = new TreeMap<>();
    try (Mbtiles db = Mbtiles.newInMemoryDatabase()) {
      var metadata = db.setupSchema().tuneForWrites().metadata();
      metadata.setBoundsAndCenter(GeoUtils.WORLD_LAT_LON_BOUNDS);
      expected.put("bounds", "-180,-85.05113,180,85.05113");
      expected.put("center", "0,0,0");

      assertEquals(expected, metadata.getAll());
    }
  }

  @Test
  public void testAddMetadataSmallBounds() throws IOException {
    Map<String, String> expected = new TreeMap<>();
    try (Mbtiles db = Mbtiles.newInMemoryDatabase()) {
      var metadata = db.setupSchema().tuneForWrites().metadata();
      metadata.setBoundsAndCenter(new Envelope(-73.6632, -69.7598, 41.1274, 43.0185));
      expected.put("bounds", "-73.6632,41.1274,-69.7598,43.0185");
      expected.put("center", "-71.7115,42.07295,7");

      assertEquals(expected, metadata.getAll());
    }
  }

  private void testMetadataJson(Mbtiles.MetadataJson object, String expected) throws IOException {
    var objectMapper = new ObjectMapper();
    try (Mbtiles db = Mbtiles.newInMemoryDatabase()) {
      var metadata = db.setupSchema().tuneForWrites().metadata();
      metadata.setJson(object);
      var actual = metadata.getAll().get("json");
      assertEquals(
        objectMapper.readTree(expected),
        objectMapper.readTree(actual)
      );
    }
  }

  @Test
  public void testMetadataJsonNoLayers() throws IOException {
    testMetadataJson(new Mbtiles.MetadataJson(), """
      {
        "vector_layers": []
      }
      """);
  }

  @Test
  public void testFullMetadataJson() throws IOException {
    testMetadataJson(new Mbtiles.MetadataJson(
      new Mbtiles.MetadataJson.VectorLayer(
        "full",
        Map.of(
          "NUMBER_FIELD", Mbtiles.MetadataJson.FieldType.NUMBER,
          "STRING_FIELD", Mbtiles.MetadataJson.FieldType.STRING,
          "boolean field", Mbtiles.MetadataJson.FieldType.BOOLEAN
        )
      ).withDescription("full description")
        .withMinzoom(0)
        .withMaxzoom(5),
      new Mbtiles.MetadataJson.VectorLayer(
        "partial",
        Map.of()
      )
    ), """
      {
        "vector_layers": [
          {
            "id": "full",
            "description": "full description",
            "minzoom": 0,
            "maxzoom": 5,
            "fields": {
              "NUMBER_FIELD": "Number",
              "STRING_FIELD": "String",
              "boolean field": "Boolean"
            }
          },
          {
            "id": "partial",
            "fields": {}
          }
        ]
      }
      """);
  }

  private static Set<Mbtiles.TileEntry> getAll(Mbtiles db) throws SQLException {
    Set<Mbtiles.TileEntry> result = new HashSet<>();
    try (Statement statement = db.connection().createStatement()) {
      ResultSet rs = statement.executeQuery("select zoom_level, tile_column, tile_row, tile_data from tiles");
      while (rs.next()) {
        result.add(new Mbtiles.TileEntry(
          TileCoord.ofXYZ(
            rs.getInt("tile_column"),
            rs.getInt("tile_row"),
            rs.getInt("zoom_level")
          ),
          rs.getBytes("tile_data")
        ));
      }
    }
    return result;
  }
}