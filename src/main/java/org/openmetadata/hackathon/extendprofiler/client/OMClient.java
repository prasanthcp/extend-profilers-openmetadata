package org.openmetadata.hackathon.extendprofiler.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

/*
 * Thin wrapper around the OpenMetadata REST API.
 * Handles auth + the handful of endpoints we actually need
 * for pushing profiler results.
 */
public class OMClient {

    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private final String baseUrl;
    private String authToken;
    private static final Logger log = LoggerFactory.getLogger(OMClient.class);

    public OMClient(String serverUrl) {
        this.baseUrl = serverUrl.replaceAll("/+$", "") + "/api/v1";
    }

    // Bearer Authentication - Get access token from OM
    public void login(String email, String pwd) throws Exception {

        String payload = json.createObjectNode()
                .put("email", email)
                .put("password", pwd)
                .toString();

        Request req = buildPost(baseUrl + "/users/login", payload);

        try (Response resp = http.newCall(req).execute()) {
            checkResp(resp, "login");
            JsonNode body = json.readTree(resp.body().string());
            this.authToken = body.get("accessToken").asText();
            log.info("Logged in as " + email);
        }
    }

    public String token() { return authToken; }

    // Get Table Info from OM
    public JsonNode fetchTable(String fqn) throws Exception {
        Request req = buildGet(baseUrl + "/tables/name/" + fqn + "?fields=columns,sampleData,customMetrics");
        try (Response resp = http.newCall(req).execute()) {
            checkResp(resp, "fetchTable(" + fqn + ")");
            return json.readTree(resp.body().string());
        }
    }

    // Get existing table profiles from OM
    public JsonNode latestProfile(String fqn) throws Exception {
        Request req = buildGet(baseUrl + "/tables/" + fqn + "/tableProfile/latest");
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) return null;   // no profile is fine !
            return json.readTree(resp.body().string());
        }
    }

    // Fetch historical profile data points for seasonality analysis
    public JsonNode fetchProfileHistory(String fqn, long startTs, long endTs) throws Exception {
        String url = baseUrl + "/tables/" + fqn + "/tableProfile?startTs=" + startTs + "&endTs=" + endTs;
        Request req = buildGet(url);
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) return null;
            return json.readTree(resp.body().string());
        }
    }

    // Register Custom Metrics before pushing profiles !
    public void addCustomMetric(String tableId, String name, String desc,
                                String columnName) throws Exception {
        
        ObjectNode node = json.createObjectNode().put("name", name).put("description", desc);
        
        if (columnName != null)
            node.put("columnName", columnName);
        
        // dummy expression, as its a mandatory field. The actual value will come from profile payload.
        node.put("expression", "SELECT 0 as " + name); 

        String payload = node.toString();
        RequestBody body = RequestBody.create(payload, MediaType.parse("application/json"));
        Request req = new Request.Builder()
                .url(baseUrl + "/tables/" + tableId + "/customMetric")
                .header("Authorization", "Bearer " + authToken)
                .put(body)
                .build();

        try (Response resp = http.newCall(req).execute()) {
            checkResp(resp, "addCustomMetric(" + name + ")");
            String target = (columnName != null) ? columnName : "table-level";
            log.info("Registered metric " + name + " on " + target);
        }
    }

    // Push Profile details to OM
    public void putProfile(String tableId, String profileJson) throws Exception {
        RequestBody body = RequestBody.create(profileJson, MediaType.parse("application/json"));

        Request req = new Request.Builder().url(baseUrl + "/tables/" + tableId + "/tableProfile")
                .header("Authorization", "Bearer " + authToken)
                .put(body).build();

        try (Response resp = http.newCall(req).execute()) {
            checkResp(resp, "putProfile");
            log.info("Pushed profile details to OM for table ->" + tableId);
        }
    }

    // Helper functions
    private Request buildGet(String url) {
        return new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + authToken)
                .get()
                .build();
    }

    private Request buildPost(String url, String jsonPayload) {
        return new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + authToken)
                .post(RequestBody.create(jsonPayload, MediaType.parse("application/json")))
                .build();
    }

    private void checkResp(Response resp, String ctx) throws Exception {
        if (!resp.isSuccessful()) {
            String detail = (resp.body() != null) ? resp.body().string() : "no body";
            throw new OMClientException(resp.code(), ctx + " failed: " + detail);
        }
    }
}
