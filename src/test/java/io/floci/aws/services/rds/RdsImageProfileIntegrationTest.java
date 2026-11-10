package io.floci.aws.services.rds;

import io.floci.aws.services.rds.model.DbInstance;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The {@code floci:image-profile} tag picks an instance's image from
 * {@code floci.services.rds.image-profiles}, keeps it for snapshots and restores, and an unknown
 * name is refused. Mock mode: no container starts, the image is what the service would use.
 */
@QuarkusTest
@TestProfile(RdsImageProfileIntegrationTest.Profile.class)
class RdsImageProfileIntegrationTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.rds.mock", "true",
                    "floci.services.rds.image-profiles",
                    "pgvector=pgvector/pgvector:pg16, plain=postgres:17.11-alpine, broken");
        }
    }

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260921/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    @Inject
    RdsService rdsService;

    private static RequestSpecification query(String action) {
        return given().header("Authorization", AUTH)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    private static RequestSpecification create(String id, String profile) {
        RequestSpecification request = query("CreateDBInstance")
                .formParam("DBInstanceIdentifier", id)
                .formParam("Engine", "postgres")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "master")
                .formParam("MasterUserPassword", "password123")
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("AllocatedStorage", 20);
        if (profile != null) {
            request = request.formParam("Tags.member.1.Key", "floci:image-profile")
                    .formParam("Tags.member.1.Value", profile);
        }
        return request;
    }

    @Test
    void aTaggedInstanceRunsTheProfileImageAndAPlainOneTheEngineDefault() {
        String suffix = Long.toString(System.nanoTime(), 36);
        create("vec-" + suffix, "pgvector").post("/").then().statusCode(200);
        create("plain-" + suffix, null).post("/").then().statusCode(200);

        DbInstance vector = rdsService.getDbInstance("vec-" + suffix);
        DbInstance plain = rdsService.getDbInstance("plain-" + suffix);

        assertEquals("pgvector", vector.getImageProfile());
        assertEquals("pgvector/pgvector:pg16", rdsService.imageFor(vector));
        assertNull(plain.getImageProfile());
        assertEquals("postgres:16.3-alpine", rdsService.imageFor(plain));
    }

    @Test
    void anUnknownProfileIsRefusedAndNamesTheConfiguredOnes() {
        create("bad-" + Long.toString(System.nanoTime(), 36), "nope").post("/").then()
                .statusCode(400)
                .body(containsString("InvalidParameterValue"))
                .body(containsString("pgvector"));
    }

    @Test
    void aMalformedProfileEntryIsIgnored() {
        assertEquals(Map.of("pgvector", "pgvector/pgvector:pg16", "plain", "postgres:17.11-alpine"),
                rdsService.imageProfiles());
    }

    @Test
    void aSnapshotAndTheInstanceRestoredFromItKeepTheProfile() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String source = "snapsrc-" + suffix;
        create(source, "pgvector").post("/").then().statusCode(200);
        query("CreateDBSnapshot").formParam("DBInstanceIdentifier", source)
                .formParam("DBSnapshotIdentifier", "snap-" + suffix).post("/").then().statusCode(200);
        query("RestoreDBInstanceFromDBSnapshot").formParam("DBInstanceIdentifier", "restored-" + suffix)
                .formParam("DBSnapshotIdentifier", "snap-" + suffix).post("/").then().statusCode(200);

        DbInstance restored = rdsService.getDbInstance("restored-" + suffix);

        assertEquals("pgvector", restored.getImageProfile());
        assertEquals("pgvector/pgvector:pg16", rdsService.imageFor(restored));
    }
}
