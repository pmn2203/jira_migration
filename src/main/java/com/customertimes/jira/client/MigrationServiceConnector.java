package com.customertimes.jira.client;

import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.SearchRestClient;
import com.atlassian.jira.rest.client.api.domain.Issue;
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory;
import org.apache.commons.codec.binary.Base64;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class MigrationServiceConnector {
    private Logger logger = Logger.getLogger(MigrationService.class);
    private Map<String, String> initParams = new HashMap<String, String>();
    private Map<String, JiraRestClient> jiraRestClientMap = new HashMap<String, JiraRestClient>();

    public MigrationServiceConnector(Map<String, String> initParams) {
        this.initParams = initParams;
    }

    public JiraRestClient getJiraRestClient(String jiraInstance) {
        return jiraRestClientMap.get(jiraInstance);
    }

    public void init() {
        String jiraInstances [] = new String[] {"source", "target"};
        for (String jiraInstance : jiraInstances) {
            jiraRestClientMap.put(jiraInstance, new AsynchronousJiraRestClientFactory()
                    .createWithBasicHttpAuthentication(URI.create(initParams.get(jiraInstance + "-jira-url")),
                            initParams.get(jiraInstance + "-jira-username"), initParams.get(jiraInstance + "-jira-password")));
        }
    }

    public byte [] restGet(String jiraInstance, String path) {
        String auth = initParams.get(jiraInstance + "-jira-username") + ":" + initParams.get(jiraInstance + "-jira-password");
        byte[] encodedAuth = Base64.encodeBase64(auth.getBytes());
        HttpClient httpclient = HttpClientBuilder.create().build();
        HttpGet httpget = new HttpGet(path);
        httpget.setHeader("Authorization", "Basic " + new String(encodedAuth));
        HttpResponse response = null;
        while (response == null) {
            try {
                response = httpclient.execute(httpget);
            } catch (IOException e) {
                logger.error(e);
            }
        }
        if (response.getStatusLine().getStatusCode() == 200) {
            InputStream is = null;
            while (is == null) {
                try {
                    is = response.getEntity().getContent();
                    byte b [] = new byte[16384];
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    int num = 0;
                    while (num >= 0) {
                        num = is.read(b);
                        if (num > 0)
                            bos.write(b, 0, num);
                    }
                    return bos.toByteArray();
                } catch (IOException e) {
                    logger.error(e);
                }
            }
        }
        logger.info("rest http get error " + response.getStatusLine().getStatusCode());
        return null;
    }

    public JSONObject restGetJson(String jiraInstance, String path) {
        try {
            return new JSONObject(new String(restGet(jiraInstance, path)));
        } catch (JSONException e) {
            logger.error(e);
        }
        return null;
    }

    public boolean restPutJson(String jiraInstance, String path, String jsonRequest) throws Exception {
        String auth = initParams.get(jiraInstance + "-jira-username") + ":" + initParams.get(jiraInstance + "-jira-password");
        byte[] encodedAuth = Base64.encodeBase64(auth.getBytes());
        HttpClient httpclient = HttpClientBuilder.create().build();
        HttpPut httpput = new HttpPut(path);
        httpput.setHeader("Authorization", "Basic " + new String(encodedAuth));
        httpput.setHeader("Content-Type", "application/json");
        httpput.setEntity(new StringEntity(jsonRequest));
        HttpResponse response = httpclient.execute(httpput);

        if (response.getStatusLine().getStatusCode() == 200) {
            return true;
        } else {
            return false;
        }
    }

    public void restPostJsonNative(String jiraInstance, String path, String jsonRequest) throws Exception {
        String auth = initParams.get(jiraInstance + "-jira-username") + ":" + initParams.get(jiraInstance + "-jira-password");
        byte[] encodedAuth = Base64.encodeBase64(auth.getBytes());
        String authHeaderValue = "Basic " + new String(encodedAuth);
        URL url = new URL(path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestProperty("Authorization", authHeaderValue);
        connection.setDoOutput(true);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("body", jsonRequest);
        String s = jsonObject.toString();
        connection.getOutputStream().write(s.getBytes());
        connection.getInputStream();
    }

    public boolean restPostJson(String jiraInstance, String path, String jsonRequest) throws Exception {
        String auth = initParams.get(jiraInstance + "-jira-username") + ":" + initParams.get(jiraInstance + "-jira-password");
        byte[] encodedAuth = Base64.encodeBase64(auth.getBytes());
        HttpClient httpclient = HttpClientBuilder.create().build();
        HttpPost httppost = new HttpPost(path);
        httppost.setHeader("Authorization", "Basic " + new String(encodedAuth));
        httppost.setHeader("Content-Type", "application/json");
        httppost.setEntity(new StringEntity(jsonRequest));
        HttpResponse response = httpclient.execute(httppost);

        if (response.getStatusLine().getStatusCode() == 200 || response.getStatusLine().getStatusCode() == 201) {
            return true;
        } else {
            return false;
        }
    }

    public boolean restPostFile(String jiraInstance, String path, byte content [], String filename) {
        String auth = initParams.get(jiraInstance + "-jira-username") + ":" + initParams.get(jiraInstance + "-jira-password");
        byte[] encodedAuth = Base64.encodeBase64(auth.getBytes());
        HttpClient httpclient = HttpClientBuilder.create().build();
        HttpPost httppost = new HttpPost(path);
        httppost.setHeader("Authorization", "Basic " + new String(encodedAuth));
        httppost.setHeader("X-Atlassian-Token", "no-check");
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.addBinaryBody("file", content, ContentType.APPLICATION_OCTET_STREAM, filename);
        builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);
        HttpEntity entity = builder.build();
        httppost.setEntity(entity);
        HttpResponse response = null;
        try {
            response = httpclient.execute(httppost);
        } catch (Exception e) {
            logger.error(e);
            return false;
        }
        if (response.getStatusLine().getStatusCode() == 200) {
            return true;
        } else {
            return false;
        }
    }

    public Iterator<Issue> searchIssues(String jiraInstance, String query, int startPosition, int limit) {
        JiraRestClient restClient = jiraRestClientMap.get(jiraInstance);
        SearchRestClient searchRestClient = restClient.getSearchClient();
        Iterator<Issue> it = null;
        while (it == null) {
            try {
                it = searchRestClient.searchJql(query, limit, startPosition, null).get().getIssues().iterator();
            } catch (Exception e) {
                logger.error(e);
            }
        }
        return it;
    }
}
